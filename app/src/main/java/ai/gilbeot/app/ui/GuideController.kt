package ai.gilbeot.app.ui

import ai.gilbeot.app.App
import ai.gilbeot.app.R
import ai.gilbeot.app.car.Speaker
import ai.gilbeot.app.core.Notice
import ai.gilbeot.app.core.NoticeQueue
import ai.gilbeot.app.core.Tbt
import ai.gilbeot.app.core.TurnDirection
import ai.gilbeot.app.core.TurnPreviewEngine
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.Button
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

/** M2 차량 입력, 회전 엔진, 안내 큐와 단일 화면을 연결한다. */
class GuideController(
    private val app: App,
    private val views: Views,
    private val onStoppedChanged: (Boolean) -> Unit,
) {
    data class Views(
        val status: TextView,
        val subtitle: TextView,
        val voiceOff: TextView,
        val turnArrow: ImageView,
        val turnDistance: TextView,
        val turnDescription: TextView,
        val turnBefore: TextView,
        val sunsetPromise: TextView,
        val breakPromise: TextView,
        val demoScale: TextView,
        val restButton: Button,
        val settingsButton: Button,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val engine = TurnPreviewEngine()
    private var queue = NoticeQueue()
    private val promises = PromiseController(
        app,
        PromiseController.Views(
            views.sunsetPromise,
            views.breakPromise,
            views.demoScale,
            views.restButton,
            views.subtitle,
        ),
        ::enqueue,
    )
    private var speedKmh = 0f
    private var lowBeam = "-"
    private var turnSignal = "-"
    private var ttsState = Speaker.State.INITIALIZING
    private var tbtCount = 0
    private var lastStatusLogMs = 0L
    private var speakingNotice = false

    private val ticker = object : Runnable {
        /** 200ms마다 거리 적분, 안내 큐, 화면과 상태 로그를 갱신한다. */
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            promises.tick(now)
            engine.poll(now).forEach(::enqueue)
            speakNext()
            renderTurnCard()
            if (now - lastStatusLogMs >= LOG_INTERVAL_MS) {
                lastStatusLogMs = now
                logSnapshot()
            }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    /** SDK 콜백을 연결하고 권한과 무관한 소스를 시작한다. */
    fun start(destination: String?, promiseConfig: PromiseController.Config) {
        promises.beginRoute(promiseConfig)
        bindCallbacks()
        app.naviSource.start()
        app.vehicleSignals.start()
        app.speaker.start()
        app.naviSource.requestRoute(destination)
        renderStatus()
        handler.post(ticker)
    }

    /** 권한이 허용된 차속·위치 소스를 시작한다. */
    fun startGrantedSources() {
        app.speedSource.start()
        app.locationSource.start()
    }

    /** 같은 프로세스의 새 목적지 요청을 NaviSource에 전달한다. */
    fun requestRoute(destination: String?, promiseConfig: PromiseController.Config) {
        promises.beginRoute(promiseConfig)
        app.naviSource.requestRoute(destination)
    }

    /** 모든 소스, UI 콜백과 주기 작업을 정리한다. */
    fun stop() {
        handler.removeCallbacksAndMessages(null)
        app.speedSource.stop()
        app.locationSource.stop()
        app.vehicleSignals.stop()
        app.speaker.stop()
        app.naviSource.stop()
        promises.stop()
        clearCallbacks()
    }

    /** SDK 값을 core 입력과 화면 상태로 바꾼다. */
    private fun bindCallbacks() {
        app.speedSource.onSpeed = { mps, kmh -> onMain {
            speedKmh = kmh
            engine.onSpeed(mps.toDouble(), SystemClock.elapsedRealtime())
            promises.onSpeed(mps.toDouble(), SystemClock.elapsedRealtime())
            val stopped = mps <= 1f
            views.settingsButton.visibility = if (stopped) View.VISIBLE else View.GONE
            onStoppedChanged(stopped)
            renderStatus()
        } }
        app.vehicleSignals.onLowBeam = { state -> onMain {
            lowBeam = state
            promises.onLowBeam(state, SystemClock.elapsedRealtime())
            renderStatus()
        } }
        app.vehicleSignals.onTurnSignal = { state -> onMain {
            turnSignal = state
            engine.onTurnSignal(state.toDirection(), SystemClock.elapsedRealtime()).forEach(::enqueue)
            speakNext()
            renderStatus()
        } }
        app.naviSource.onTbtCount = { count -> onMain {
            tbtCount = count
            renderStatus()
        } }
        app.naviSource.onTbtList = { sdkList -> onMain {
            if (sdkList.isNotEmpty()) {
                val list = sdkList.map { Tbt(it.type.name, it.distance, it.description) }
                engine.onTbtList(list, SystemClock.elapsedRealtime())
                renderTurnCard()
            }
        } }
        app.naviSource.onRouteStarted = { duration, distance -> onMain {
            promises.onRouteStarted(duration, distance)
            renderStatus()
        } }
        app.locationSource.onLocation = { lat, lng, _ -> onMain {
            promises.onLocation(lat, lng)
        } }
        app.speaker.onState = { state -> onMain {
            ttsState = state
            views.voiceOff.visibility = if (state == Speaker.State.SUBTITLE_ONLY) View.VISIBLE else View.GONE
            renderStatus()
        } }
        app.speaker.onSubtitle = { text -> onMain { views.subtitle.text = text } }
        app.speaker.onSpeechFinished = { onMain {
            speakingNotice = false
            speakNext()
        } }
    }

    /** 중복이 아닌 안내를 큐에 넣고 근거 로그를 남긴다. */
    private fun enqueue(notice: Notice) {
        if (!queue.accept(notice)) return
        log("notice priority=${notice.priority} key=${notice.key} text=${notice.text}")
    }

    /** 앞 안내가 끝났을 때 큐에서 한 건만 꺼내 자막과 음성으로 보낸다. */
    private fun speakNext() {
        if (speakingNotice || app.speaker.isSpeaking()) return
        val notice = queue.poll() ?: return
        speakingNotice = app.speaker.speak(notice.text)
    }

    /** 다음 대상 회전을 크게, 그 전에 있는 일반 회전을 보조 줄로 표시한다. */
    private fun renderTurnCard() {
        val selection = engine.turnCardSelection()
        if (selection == null) {
            views.turnArrow.setImageResource(R.drawable.ic_turn_straight)
            views.turnDistance.text = "경로 준비 중"
            views.turnDescription.text = "TBT 정보를 기다리고 있습니다"
            views.turnBefore.visibility = View.GONE
            return
        }
        val next = selection.primary
        views.turnArrow.setImageResource(arrowFor(next.tbt.type))
        views.turnDistance.text = "${roundedMeters(next.remainingM)} m"
        views.turnDescription.text = next.tbt.description?.takeIf(String::isNotBlank) ?: next.tbt.type
        val before = selection.before
        if (before == null) {
            views.turnBefore.visibility = View.GONE
        } else {
            views.turnBefore.visibility = View.VISIBLE
            views.turnBefore.text = "그 전에 ${shortDirection(before.tbt.type)} ${roundedMeters(before.remainingM)}m"
        }
    }

    /** 거리를 카드 표시용 10m 단위 정수로 반올림한다. */
    private fun roundedMeters(distanceM: Double): Int =
        (distanceM.coerceAtLeast(0.0) / 10.0).roundToInt() * 10

    /** 보조 줄에 쓸 짧은 회전 방향을 만든다. */
    private fun shortDirection(type: String): String = when {
        type == "TURN_UTURN" -> "유턴"
        type.contains("LEFT") || type.endsWith("_L") -> "좌회전"
        type.contains("RIGHT") || type.endsWith("_R") -> "우회전"
        type.contains("STRAIGHT") -> "직진"
        else -> "다음 안내"
    }

    /** PT 확인용 차량·음성 상태를 한 줄로 표시한다. */
    private fun renderStatus() {
        views.status.text = String.format(
            Locale.KOREA,
            "차속 %.1f km/h · 전조등 %s · 방향지시등 %s · TTS %s · TBT %d%s",
            speedKmh, lowBeam, turnSignal, ttsState.label, tbtCount, promises.statusSuffix(),
        )
    }

    /** 현재 적분 상태를 요구 형식으로 1초마다 기록한다. */
    private fun logSnapshot() {
        val next = engine.nextTurn()
        val target = engine.nextTargetTurn()
        val remaining = next?.remainingM?.roundToInt()?.toString() ?: "-"
        val nextType = next?.tbt?.type ?: "-"
        val targetText = target?.let { "${it.tbt.type}:${it.remainingM.roundToInt()}" } ?: "-"
        log(
            "remaining=$remaining next=$nextType target=$targetText " +
                "traveled=${engine.traveledM.roundToInt()} speedKph=${speedKmh.roundToInt()}",
        )
    }

    /** 회전 종류를 네 가지 직접 그린 화살표 중 하나로 고른다. */
    private fun arrowFor(type: String): Int = when {
        type == "TURN_UTURN" -> R.drawable.ic_turn_uturn
        type.contains("LEFT") || type.endsWith("_L") -> R.drawable.ic_turn_left
        type.contains("RIGHT") || type.endsWith("_R") -> R.drawable.ic_turn_right
        else -> R.drawable.ic_turn_straight
    }

    /** SDK 문자열 방향을 core 방향으로 바꾼다. */
    private fun String.toDirection(): TurnDirection = when (this) {
        "LEFT" -> TurnDirection.LEFT
        "RIGHT" -> TurnDirection.RIGHT
        else -> TurnDirection.NONE
    }

    /** SDK 콜백이 어느 스레드에서 와도 UI 작업을 메인 스레드로 보낸다. */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    /** Activity를 잡은 콜백 참조를 제거한다. */
    private fun clearCallbacks() {
        app.speedSource.onSpeed = null
        app.vehicleSignals.onLowBeam = null
        app.vehicleSignals.onTurnSignal = null
        app.naviSource.onTbtCount = null
        app.naviSource.onTbtList = null
        app.naviSource.onRouteStarted = null
        app.locationSource.onLocation = null
        app.speaker.onState = null
        app.speaker.onSubtitle = null
        app.speaker.onSpeechFinished = null
    }

    /** GILBEOT 태그로 검증 가능한 상태를 기록한다. */
    private fun log(message: String) = Log.i(TAG, message)

    private companion object {
        const val TAG = "GILBEOT"
        const val POLL_INTERVAL_MS = 200L
        const val LOG_INTERVAL_MS = 1_000L
    }
}
