package ai.gilbeot.app.core

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

enum class HeadlampState { OFF, ON, OTHER }

data class RoutePromiseResult(
    val notice: Notice?,
    val requestComfortable: Boolean,
    val arrival: ZonedDateTime? = null,
    val sunset: ZonedDateTime? = null,
    val beforeSunset: Boolean? = null,
)

data class HeadlampResult(
    val notices: List<Notice>,
    val requestComfortable: Boolean,
    val showRestButtonWhenStopped: Boolean,
)

/** 일몰 도착과 연속 운전 약속을 시간 주입만으로 판단한다. */
class PromiseEngine(
    var sunsetPromiseEnabled: Boolean = true,
    var breakPromiseEnabled: Boolean = true,
    val demoTimeScale: Double = 1.0,
) {
    var routeIsComfortable: Boolean = false
        private set

    private var lastHeadlampState: HeadlampState? = null
    private var headlampHandled = false
    private var lastDrivingUpdateMs: Long? = null
    private var currentSpeedMps = 0.0
    private var continuousDrivingMs = 0.0
    private var stoppedMs = 0.0
    private var breakAnnounced = false
    private var stopResetApplied = false
    private var routeGeneration = 0L
    private var breakCycle = 0L

    /** 데모 배속이 적용된 현재 연속 운전 시간을 완성된 분 단위로 제공한다. */
    val continuousDrivingScaledMinutes: Long
        get() = (continuousDrivingMs / 60_000.0).toLong()

    /** 경로의 도착 시각을 일몰 15분 전 기준과 비교한다. */
    fun onRouteStarted(nowMs: Long, durationSec: Long, sunset: ZonedDateTime): RoutePromiseResult {
        routeGeneration += 1
        headlampHandled = false
        // lastHeadlampState는 지우지 않는다: 전조등 이벤트는 바뀔 때만 오므로, 지우면 다음 ON을 OFF→ON으로 알아보지 못한다.
        if (!sunsetPromiseEnabled) return RoutePromiseResult(null, false)

        val arrival = Instant.ofEpochMilli(nowMs + durationSec * 1_000L)
            .atZone(KST)
        val beforeSunset = !arrival.isAfter(sunset.withZoneSameInstant(KST).minusMinutes(15))
        val text = if (beforeSunset) {
            "해 지는 시각은 ${formatTime(sunset)}입니다. 지금 가면 해 지기 전, ${formatTime(arrival)}에 도착합니다."
        } else {
            "도착은 ${formatTime(arrival)}, 해가 진 뒤입니다. 약속하신 대로 편안한 경로를 요청할게요."
        }
        return RoutePromiseResult(
            Notice(text, Notice.PROMISE, "route-$routeGeneration-promise-sunset"),
            requestComfortable = !beforeSunset && !routeIsComfortable,
            arrival = arrival,
            sunset = sunset,
            beforeSunset = beforeSunset,
        )
    }

    /** 실제 경로 옵션 변경 결과를 엔진 상태에 반영한다. */
    fun onRouteOptionChanged(isComfortable: Boolean) {
        routeIsComfortable = isComfortable
    }

    /** 전조등 OFF에서 ON 전환을 경로당 한 번만 약속 안내로 바꾼다. */
    fun onHeadlamp(state: HeadlampState, nowMs: Long): HeadlampResult {
        val changedOn = lastHeadlampState == HeadlampState.OFF && state == HeadlampState.ON
        lastHeadlampState = state
        if (!sunsetPromiseEnabled || !changedOn || headlampHandled) return emptyHeadlampResult()
        headlampHandled = true
        val request = !routeIsComfortable
        val firstText = if (routeIsComfortable) {
            "전조등이 켜졌어요. 편안한 경로는 이미 요청했어요."
        } else {
            "전조등이 켜졌어요. 약속하신 대로 편안한 경로를 요청할게요."
        }
        return HeadlampResult(
            notices = listOf(
                Notice(firstText, Notice.PROMISE, "route-$routeGeneration-promise-headlamp-$nowMs"),
                Notice(REST_TEXT, Notice.PROMISE, "route-$routeGeneration-promise-rest-$nowMs"),
            ),
            requestComfortable = request,
            showRestButtonWhenStopped = true,
        )
    }

    /** 새 차속 시각까지 기존 상태를 누적한 뒤 차속을 갱신한다. */
    fun onSpeed(mps: Double, nowMs: Long): Notice? {
        updateDriving(nowMs)
        currentSpeedMps = mps.coerceAtLeast(0.0)
        return announceBreakIfDue()
    }

    /** 차속 이벤트가 없어도 현재 시각까지 연속 주행 또는 정차 시간을 누적한다. */
    fun pollDriving(nowMs: Long): Notice? {
        updateDriving(nowMs)
        return announceBreakIfDue()
    }

    /** 직전 차속 상태에 따라 주행 또는 정차 시간을 배속 적용해 누적한다. */
    private fun updateDriving(nowMs: Long) {
        val last = lastDrivingUpdateMs
        if (last == null) {
            lastDrivingUpdateMs = nowMs
            return
        }
        if (nowMs <= last) return
        val scaledElapsed = (nowMs - last) * demoTimeScale
        if (currentSpeedMps > 1.0) {
            continuousDrivingMs += scaledElapsed
            stoppedMs = 0.0
            stopResetApplied = false
        } else {
            stoppedMs += scaledElapsed
            if (stoppedMs >= STOP_RESET_MS && !stopResetApplied) {
                continuousDrivingMs = 0.0
                breakAnnounced = false
                breakCycle += 1
                stopResetApplied = true
            }
        }
        lastDrivingUpdateMs = nowMs
    }

    /** 연속 운전 기준에 처음 도달했을 때만 휴식 안내를 만든다. */
    private fun announceBreakIfDue(): Notice? {
        if (!breakPromiseEnabled || breakAnnounced || continuousDrivingMs < BREAK_NOTICE_MS) return null
        breakAnnounced = true
        return Notice(BREAK_TEXT, Notice.PROMISE, "promise-break-$breakCycle")
    }

    /** 전조등 약속이 발동하지 않았음을 나타내는 빈 결과를 만든다. */
    private fun emptyHeadlampResult() = HeadlampResult(emptyList(), false, false)

    /** 어떤 입력 시간대든 한국 표준시의 '오전/오후 H시 M분' 문구로 바꾼다. */
    private fun formatTime(time: ZonedDateTime): String {
        val kst = time.withZoneSameInstant(KST)
        val half = if (kst.hour < 12) "오전" else "오후"
        val hour = ((kst.hour + 11) % 12) + 1
        return "$half ${hour}시 ${kst.minute}분"
    }

    companion object {
        private val KST = ZoneId.of("Asia/Seoul")
        private const val BREAK_NOTICE_MS = 110L * 60L * 1_000L
        private const val STOP_RESET_MS = 3L * 60L * 1_000L
        private const val BREAK_TEXT = "곧 두 시간째 운전입니다. 다음 휴게소에서 쉬어 가요."
        private const val REST_TEXT = "쉬어 갈 곳이 필요하면 차를 세웠을 때 알려 주세요."
    }
}
