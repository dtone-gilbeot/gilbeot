package ai.gilbeot.app.ui

import ai.gilbeot.app.App
import ai.gilbeot.app.core.HeadlampState
import ai.gilbeot.app.core.Notice
import ai.gilbeot.app.core.PromiseClock
import ai.gilbeot.app.core.PromiseEngine
import ai.gilbeot.app.core.RouteStartGate
import ai.gilbeot.app.core.RouteStartType
import ai.gilbeot.app.core.SunCalc
import ai.gilbeot.app.core.mapLowBeamState
import ai.pleos.playground.navi.constants.RouteOption
import android.util.Log
import android.content.SharedPreferences
import android.view.View
import android.widget.Button
import android.widget.TextView
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** M3 약속 엔진을 차량 입력, 공용 안내 큐와 약속 카드에 연결한다. */
class PromiseController(
    private val app: App,
    private val views: Views,
    private val enqueue: (Notice) -> Unit,
) {
    data class Views(
        val sunsetPromise: TextView,
        val breakPromise: TextView,
        val demoScale: TextView,
        val restButton: Button,
        val subtitle: TextView,
    )

    data class Config(val injectedNow: String?, val timeScale: Double)

    private var clock = PromiseClock.fromIntentExtra(null, System.currentTimeMillis())
    private var engine = PromiseEngine()
    private val routeGate = RouteStartGate()
    private var latitude = SEOUL_LAT
    private var longitude = SEOUL_LNG
    private var latestHeadlamp = HeadlampState.OTHER
    private var speedMps = 0.0
    private var restButtonRequested = false
    private var routeBeforeSunset: Boolean? = null
    private var routeSunset: ZonedDateTime? = null
    private var routeArrival: ZonedDateTime? = null
    private var lastLoggedDrivingMinute = -1L
    private var headlampTurnedOn = false
    private var comfortableRequested = false
    private val preferences = app.getSharedPreferences(PromisePreferences.FILE_NAME, 0)
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PromisePreferences.SUNSET_ENABLED || key == PromisePreferences.BREAK_ENABLED) {
            applyPreferences()
        }
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        views.restButton.setOnClickListener {
            views.subtitle.text = REST_SEARCH_NEXT_VERSION
        }
    }

    /** 새 목적지 요청에 맞춰 약속 시계와 경로당 상태를 초기화한다. */
    fun beginRoute(config: Config) {
        val realNow = System.currentTimeMillis()
        clock = try {
            PromiseClock.fromIntentExtra(config.injectedNow, realNow)
        } catch (error: IllegalArgumentException) {
            log("invalid promise now=${config.injectedNow}; using real clock")
            PromiseClock.fromIntentExtra(null, realNow)
        }
        val scale = config.timeScale.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        engine = PromiseEngine(demoTimeScale = scale)
        applyPreferences(render = false)
        engine.onHeadlamp(latestHeadlamp, 0L)
        routeGate.onFreshRouteRequested()
        restButtonRequested = false
        routeBeforeSunset = null
        routeSunset = null
        routeArrival = null
        lastLoggedDrivingMinute = -1L
        headlampTurnedOn = false
        comfortableRequested = false
        views.demoScale.visibility = if (scale == 1.0) View.GONE else View.VISIBLE
        views.demoScale.text = "데모 배속 ×${formatScale(scale)}"
        renderCards()
        renderRestButton()
    }

    /** 설정 변경 수신을 해제해 Activity 참조가 남지 않게 한다. */
    fun stop() {
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
    }

    /** 최신 위치를 다음 첫 경로 시작의 일몰 계산에 사용한다. */
    fun onLocation(lat: Double, lng: Double) {
        latitude = lat
        longitude = lng
    }

    /** 첫 경로 시작만 일몰 약속을 판정하고 후속 콜백은 ETA만 갱신한다. */
    fun onRouteStarted(durationSec: Int, distanceM: Int) {
        val realNow = System.currentTimeMillis()
        val promiseNow = clock.nowMs(realNow)
        val startType = routeGate.onRouteStarted()
        if (startType != RouteStartType.NEW_ROUTE) {
            routeArrival = Instant.ofEpochMilli(promiseNow + durationSec * 1_000L).atZone(KST)
            log("promise route followUp type=$startType duration=$durationSec distance=$distanceM")
            renderCards()
            return
        }
        val now = Instant.ofEpochMilli(promiseNow).atZone(KST)
        val sunset = SunCalc.sunset(now.toLocalDate(), latitude, longitude, KST)
        if (sunset == null) {
            log("promise sunset unavailable lat=$latitude lng=$longitude date=${now.toLocalDate()}")
            views.sunsetPromise.text = "해 지기 전 도착 · 일몰 계산 불가"
            return
        }
        val result = engine.onRouteStarted(promiseNow, durationSec.toLong(), sunset)
        routeBeforeSunset = result.beforeSunset
        routeSunset = result.sunset
        routeArrival = result.arrival
        log(
            "promise sunset now=${hhmm(now)} arrival=${hhmm(result.arrival)} " +
                "sunset=${hhmm(result.sunset)} before=${result.beforeSunset} " +
                "requestComfortable=${result.requestComfortable}",
        )
        result.notice?.let(enqueue)
        if (result.requestComfortable && !comfortableRequested) changeComfortable(REASON_SUNSET)
        renderCards()
    }

    /** 저빔 문자열을 약속 상태로 바꿔 경로당 한 번만 처리한다. */
    fun onLowBeam(rawState: String, elapsedMs: Long) {
        latestHeadlamp = mapLowBeamState(rawState)
        val result = engine.onHeadlamp(latestHeadlamp, elapsedMs)
        log(
            "promise headlamp state=$latestHeadlamp notices=${result.notices.size} " +
                "requestComfortable=${result.requestComfortable}",
        )
        result.notices.forEach(enqueue)
        if (result.notices.isNotEmpty()) headlampTurnedOn = true
        if (result.requestComfortable && !comfortableRequested) changeComfortable(REASON_HEADLAMP)
        if (result.showRestButtonWhenStopped) restButtonRequested = true
        renderRestButton()
    }

    /** 차속 이벤트를 휴식 약속에 전달하고 정차 전용 버튼을 갱신한다. */
    fun onSpeed(mps: Double, elapsedMs: Long) {
        speedMps = mps
        engine.onSpeed(mps, elapsedMs)?.let(enqueue)
        renderRestButton()
        renderDrivingCard()
    }

    /** 200ms 주기로 연속 운전을 누적하고 분 단위 근거 로그를 남긴다. */
    fun tick(elapsedMs: Long) {
        engine.pollDriving(elapsedMs)?.let(enqueue)
        val minute = engine.continuousDrivingScaledMinutes
        if (minute != lastLoggedDrivingMinute) {
            lastLoggedDrivingMinute = minute
            log("promise break drivenScaledMin=$minute")
            renderDrivingCard()
        }
    }

    /** 상태 줄에 붙일 시각 주입 상태를 반환한다. */
    fun statusSuffix(): String = clock.injectedLabel?.let { " · 시각 주입 $it" } ?: ""

    /** SDK가 요청을 받은 사실만 기록하며 실제 경로가 바뀌었다고 간주하지 않는다. */
    private fun changeComfortable(reason: String) {
        if (!app.naviSource.changeRouteOption(RouteOption.COMFORTABLE, reason)) return
        routeGate.onRouteOptionChangeRequested()
        comfortableRequested = true
        // 이미 요청한 경로에서 전조등이 켜지면 "요청할게요" 대신 "이미 요청했어요"로 말하도록 엔진에 알린다.
        engine.onRouteOptionChanged(true)
        renderCards()
    }

    /** 저장된 두 약속 스위치를 엔진에 즉시 적용하고 카드를 다시 그린다. */
    private fun applyPreferences(render: Boolean = true) {
        engine.sunsetPromiseEnabled = preferences.getBoolean(PromisePreferences.SUNSET_ENABLED, true)
        engine.breakPromiseEnabled = preferences.getBoolean(PromisePreferences.BREAK_ENABLED, true)
        if (render) renderCards()
    }

    /** 일몰과 휴식 약속 카드를 함께 갱신한다. */
    private fun renderCards() {
        val sunset = routeSunset
        val arrival = routeArrival
        views.sunsetPromise.text = when {
            !engine.sunsetPromiseEnabled -> "해 지기 전 도착 · 꺼짐"
            headlampTurnedOn && comfortableRequested -> "전조등 켜짐 · 편안한 경로로 요청함"
            headlampTurnedOn -> "전조등 켜짐 · 편안한 경로 요청 불가"
            routeBeforeSunset == true -> "해 지기 전 도착 ✓ · 일몰 ${displayTime(sunset)} · 도착 ${displayTime(arrival)}"
            routeBeforeSunset == false && comfortableRequested ->
                "해 진 뒤 도착 · 편안한 경로로 요청함 · 도착 ${displayTime(arrival)}"
            routeBeforeSunset == false -> "해 진 뒤 도착 · 편안한 경로 요청 불가 · 도착 ${displayTime(arrival)}"
            else -> "해 지기 전 도착 · 경로 대기 중"
        }
        renderDrivingCard()
    }

    /** 배속이 반영된 연속 운전 시간을 약속 카드에 표시한다. */
    private fun renderDrivingCard() {
        if (!engine.breakPromiseEnabled) {
            views.breakPromise.text = "2시간마다 쉬기 · 꺼짐"
            return
        }
        val total = engine.continuousDrivingScaledMinutes
        val duration = if (total >= 60) "${total / 60}시간 ${total % 60}분" else "${total}분"
        views.breakPromise.text = "2시간마다 쉬기 · 연속 운전 $duration"
    }

    /** 쉬기 안내 뒤 실제 정차 중일 때만 범위 제한 버튼을 표시한다. */
    private fun renderRestButton() {
        views.restButton.visibility = if (restButtonRequested && speedMps <= 1.0) View.VISIBLE else View.GONE
    }

    /** 로그용 시각을 24시간제 HH:mm으로 만든다. */
    private fun hhmm(time: ZonedDateTime?): String = time?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "-"

    /** 카드용 시각을 오전/오후가 있는 한국어 시각으로 만든다. */
    private fun displayTime(time: ZonedDateTime?): String {
        time ?: return "-"
        val half = if (time.hour < 12) "오전" else "오후"
        val hour = ((time.hour + 11) % 12) + 1
        return "$half ${hour}시 ${time.minute}분"
    }

    /** 정수 배속은 소수점 없이, 그 밖에는 원래 숫자로 표시한다. */
    private fun formatScale(scale: Double): String = if (scale % 1.0 == 0.0) scale.toLong().toString() else scale.toString()

    /** GILBEOT 태그로 검증 가능한 약속 상태를 기록한다. */
    private fun log(message: String) = Log.i(TAG, message)

    private companion object {
        const val TAG = "GILBEOT"
        const val SEOUL_LAT = 37.5665
        const val SEOUL_LNG = 126.9780
        const val REASON_SUNSET = "sunset"
        const val REASON_HEADLAMP = "headlamp"
        const val REST_SEARCH_NEXT_VERSION = "쉬어 갈 곳 찾기는 다음 버전에서 제공합니다."
        val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
