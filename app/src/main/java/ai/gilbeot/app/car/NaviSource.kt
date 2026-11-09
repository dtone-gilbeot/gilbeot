package ai.gilbeot.app.car

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.Locale

/** NaviHelper의 M0 경로 및 TBT 이벤트를 안전하게 로그로 전달한다. */
class NaviSource(context: Context) {
    private val navi = NaviHelper(context.applicationContext)
    private val handler = Handler(Looper.getMainLooper())
    private var startedDistanceM: Double? = null
    private var optionChangeObservation: OptionChangeObservation? = null
    private var hasRouteTbt = false
    private var lastTbtSignature = ""
    var onTbtCount: ((Int) -> Unit)? = null
    var onTbtList: ((List<TBTInfo>) -> Unit)? = null
    var onRouteStarted: ((durationSec: Int, distanceM: Int) -> Unit)? = null

    /** 11/09 실측: 경로 시작 뒤 비어 있지 않은 TBT 목록이 가끔 오지 않는다. 그때 한 번 직접 요청한다. */
    private val pullTbtIfMissing = Runnable {
        if (hasRouteTbt) return@Runnable
        try {
            navi.getTBTInfo()
            log("getTBTInfo requested: no TBT ${TBT_PULL_DELAY_MS}ms after route start")
        } catch (error: Exception) {
            log("getTBTInfo failed=$error")
        }
    }

    private val listener = object : NaviHelperEventListener {
        /** 경로 시작 시 ETA와 총거리를 기록한다. */
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            startedDistanceM = routeStartInfo.distance.toDouble()
            log("onRouteStarted duration=${routeStartInfo.duration} distance=${routeStartInfo.distance}")
            optionChangeObservation?.let { observation ->
                log(
                    "routeStartedAfterOptionChange duration=${routeStartInfo.duration} " +
                        "distance=${routeStartInfo.distance} " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - observation.startedAtMs}",
                )
            }
            onRouteStarted?.invoke(routeStartInfo.duration, routeStartInfo.distance)
            handler.removeCallbacks(pullTbtIfMissing)
            handler.postDelayed(pullTbtIfMissing, TBT_PULL_DELAY_MS)
        }

        /** 경로 계산 완료를 기록한다. */
        override fun onRoutingCompleted() { log("onRoutingCompleted") }

        /** 경로 옵션 변경 완료 알림을 기록하고, 바뀐 TBT 목록을 다시 요청한다. */
        override fun onRouteOptionChanged(routeOption: RouteOption) {
            log("onRouteOptionChanged option=$routeOption")
            try {
                navi.getTBTInfo()
                log("getTBTInfo requested after option change")
            } catch (error: Exception) {
                log("getTBTInfo failed=$error")
            }
        }

        /** 재탐색 완료를 기록한다. */
        override fun onReRouteEnded() { log("onReRouteEnded") }

        /** 내비게이션 상태를 기록한다. */
        override fun onNavigationStatus(status: NavigationStatus) { log("onNavigationStatus status=$status") }

        /** 모든 TBT와 구간 거리 합계를 기록한다. */
        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
            log("onTBTInfo size=${tbtInfoList.size}")
            val duplicate = hasRouteTbt && tbtInfoList.isNotEmpty() && signatureOf(tbtInfoList) == lastTbtSignature
            if (tbtInfoList.isNotEmpty()) {
                optionChangeObservation?.let { observation ->
                    val elapsed = SystemClock.elapsedRealtime() - observation.startedAtMs
                    val signature = signatureOf(tbtInfoList)
                    if (hasRouteTbt && signature != lastTbtSignature) {
                        observation.sawNonEmptyTbt = true
                        log("newTbtAfterOptionChange size=${tbtInfoList.size} elapsedMs=$elapsed")
                    } else if (hasRouteTbt) {
                        log("sameTbtAfterOptionChange size=${tbtInfoList.size} elapsedMs=$elapsed (route unchanged)")
                    } else {
                        // 옵션 변경 전에 첫 목록을 아직 못 받았다면, 이것은 처음 경로의 목록이 늦게 온 것이다(11/09 실측).
                        log("firstRouteTbtAfterOptionChange size=${tbtInfoList.size} elapsedMs=$elapsed (not counted)")
                    }
                }
                hasRouteTbt = true
                lastTbtSignature = signatureOf(tbtInfoList)
            }
            var sum = 0.0
            tbtInfoList.forEachIndexed { index, item ->
                sum += item.distance
                log("TBT[$index] type=${item.type} distance=${item.distance} description=${item.description}")
            }
            val routeDistance = startedDistanceM
            val difference = routeDistance?.minus(sum)
            log("TBT cumulative=$sum routeDistance=$routeDistance difference=$difference")
            onTbtCount?.invoke(tbtInfoList.size)
            // 같은 경로의 목록을 다시 받은 것이면 엔진에 넘기지 않는다: 넘기면 주행 거리가 0으로 리셋된다.
            if (duplicate) log("duplicate TBT list ignored") else onTbtList?.invoke(tbtInfoList)
        }

        /** 주의 지점 개수를 기록한다. */
        override fun onCautionPointInfo(cautionPointList: List<CautionPoint>) {
            log("onCautionPointInfo size=${cautionPointList.size}")
        }

        /** 목적지 주행 정보를 기록한다. */
        override fun onDrivingInfo(drivingInfo: DrivingInfo) { log("onDrivingInfo $drivingInfo") }

        /** 운행 정보를 기록한다. */
        override fun onTripInfo(tripInfo: TripInfo) { log("onTripInfo $tripInfo") }

        /** 현재 도로 제한속도를 기록한다. */
        override fun onCurrentRoadSpeedLimit(speedLimit: Int) { log("onCurrentRoadSpeedLimit speed=$speedLimit") }

        /** NaviHelper 오류를 기록하고 앱은 유지한다. */
        override fun onError(errorCode: NaviErrorCode) { log("navi onError code=$errorCode") }
    }

    /** NaviHelper를 초기화하고 이벤트 리스너를 붙인다. */
    fun start() {
        try {
            navi.initialize()
            navi.addListener(listener)
            log("NaviHelper initialized")
        } catch (error: Exception) {
            log("NaviHelper start failed=$error")
        }
    }

    /** 인텐트 문자열 또는 서울시청으로 RECOMMENDED 경로를 요청한다. */
    fun requestRoute(rawDestination: String?) {
        val destination = parseDestination(rawDestination)
        try {
            hasRouteTbt = false
            navi.requestRoute(
                RouteInfo(
                    latitude = destination.latitude,
                    longitude = destination.longitude,
                    poiName = destination.name,
                    poiId = "",
                    address = "",
                    poiSubId = "",
                    routeOption = RouteOption.RECOMMENDED,
                ),
            )
            log("requestRoute option=RECOMMENDED name=${destination.name} lat=${destination.latitude} lng=${destination.longitude}")
        } catch (error: Exception) {
            log("requestRoute failed=$error")
        }
    }

    /**
     * 경로 옵션을 바꾸고 이후 30초 동안 새 TBT와 경로 시작 이벤트를 관찰한다.
     * 11/09 실측: requestReRoute()를 덧붙이면 내비 화면이 앞으로 나와 길벗이 가려지고 멈춘다. 쓰지 않는다.
     */
    fun changeRouteOption(option: RouteOption, reason: String): Boolean {
        clearOptionChangeObservation()
        val observation = OptionChangeObservation(SystemClock.elapsedRealtime())
        optionChangeObservation = observation
        observation.timeout = Runnable {
            if (optionChangeObservation === observation) {
                if (!observation.sawNonEmptyTbt) log("noNewTbtAfterOptionChange 30s")
                optionChangeObservation = null
            }
        }
        handler.postDelayed(observation.timeout!!, OPTION_CHANGE_OBSERVATION_MS)
        observation.pull = Runnable {
            // 11/09 실측: 옵션 변경 뒤 onRouteOptionChanged·onTBTInfo가 오지 않아, 5초 뒤 목록을 직접 받아 이전과 비교한다.
            if (optionChangeObservation === observation && !observation.sawNonEmptyTbt) {
                try { navi.getTBTInfo(); log("getTBTInfo requested 5s after option change") } catch (error: Exception) { log("getTBTInfo failed=$error") }
            }
        }
        handler.postDelayed(observation.pull!!, OPTION_PULL_DELAY_MS)
        log("changeRouteOption call option=$option reason=$reason")
        return try {
            navi.changeRouteOption(option)
            log("changeRouteOption call option=$option reason=$reason ok")
            true
        } catch (error: Exception) {
            clearOptionChangeObservation()
            log("changeRouteOption call option=$option reason=$reason failed=$error")
            false
        }
    }

    /** SDK 자원을 해제한다. */
    fun stop() {
        handler.removeCallbacks(pullTbtIfMissing)
        clearOptionChangeObservation()
        try {
            navi.removeListener(listener)
            navi.release()
            log("NaviHelper released")
        } catch (error: Exception) {
            log("NaviHelper release failed=$error")
        }
    }

    /** `lat,lng,name`을 파싱하고 잘못되면 서울시청을 쓴다. */
    private fun parseDestination(raw: String?): Destination {
        if (raw.isNullOrBlank()) return SEOUL_CITY_HALL
        val parts = raw.split(",", limit = 3)
        val latitude = parts.getOrNull(0)?.trim()?.toDoubleOrNull()
        val longitude = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
        val name = parts.getOrNull(2)?.trim()
        return if (latitude != null && longitude != null && !name.isNullOrEmpty()) {
            Destination(latitude, longitude, name)
        } else {
            log("invalid dest=${raw.replace("\n", " ")}; fallback=서울시청")
            SEOUL_CITY_HALL
        }
    }

    /** 진행 중인 옵션 변경 관찰과 예약된 타임아웃을 취소한다. */
    private fun clearOptionChangeObservation() {
        optionChangeObservation?.timeout?.let(handler::removeCallbacks)
        optionChangeObservation?.pull?.let(handler::removeCallbacks)
        optionChangeObservation = null
    }

    /** 같은 경로인지 비교하려고 TBT 목록을 종류·거리 문자열로 줄인다. */
    private fun signatureOf(list: List<TBTInfo>): String =
        list.joinToString("|") { "${it.type}:${it.distance}" }

    /** GILBEOT 태그로 정보 로그를 남긴다. */
    private fun log(message: String) = Log.i(TAG, message)

    /** 경로 목적지 값이다. */
    private data class Destination(val latitude: Double, val longitude: Double, val name: String)

    /** 옵션 변경 뒤 새 경로 이벤트를 관찰하는 30초 창의 상태다. */
    private data class OptionChangeObservation(
        val startedAtMs: Long,
        var sawNonEmptyTbt: Boolean = false,
        var timeout: Runnable? = null,
        var pull: Runnable? = null,
    )

    private companion object {
        const val TAG = "GILBEOT"
        const val OPTION_CHANGE_OBSERVATION_MS = 30_000L
        const val TBT_PULL_DELAY_MS = 3_000L
        const val OPTION_PULL_DELAY_MS = 5_000L
        val SEOUL_CITY_HALL = Destination(37.5665, 126.9780, "서울시청")
    }
}
