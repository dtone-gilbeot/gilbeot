package ai.gilbeot.app.core

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** 경로 시작 콜백이 현재 경로에서 어떤 의미인지 나타낸다. */
enum class RouteStartType {
    NEW_ROUTE,
    OPTION_CHANGE_FOLLOW_UP,
    SAME_ROUTE_FOLLOW_UP,
}

/** 실제 시계에 고정 오프셋을 적용해 약속 판정용 현재 시각을 제공한다. */
class PromiseClock private constructor(
    private val offsetMs: Long,
    val injectedLabel: String?,
) {
    /** 주어진 실제 epoch 밀리초에 주입 오프셋을 적용한다. */
    fun nowMs(realNowMs: Long): Long = realNowMs + offsetMs

    companion object {
        private val KST = ZoneId.of("Asia/Seoul")
        private val STRICT_TIME = DateTimeFormatter.ofPattern("HH:mm")
            .withResolverStyle(ResolverStyle.STRICT)

        /** 선택적 HH:mm extra로 오늘 KST 시각을 고정하고, 없으면 실제 시계를 만든다. */
        fun fromIntentExtra(raw: String?, realNowMs: Long): PromiseClock {
            if (raw == null) return PromiseClock(0L, null)
            require(TIME_PATTERN.matches(raw)) { "now extra must be strict HH:mm: $raw" }
            val injectedTime = try {
                LocalTime.parse(raw, STRICT_TIME)
            } catch (error: RuntimeException) {
                throw IllegalArgumentException("now extra must be strict HH:mm: $raw", error)
            }
            val realNow = Instant.ofEpochMilli(realNowMs).atZone(KST)
            val injectedNow = realNow.toLocalDate().atTime(injectedTime).atZone(KST)
            return PromiseClock(injectedNow.toInstant().toEpochMilli() - realNowMs, raw)
        }

        private val TIME_PATTERN = Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")
    }
}

/** 명시적인 새 경로 요청과 같은 경로의 후속 콜백을 구분한다. */
class RouteStartGate {
    private var routeStarted = false
    private var optionChangeAwaitingRouteStart = false

    /** 다음 경로 시작 콜백을 새 경로의 첫 콜백으로 초기화한다. */
    fun onFreshRouteRequested() {
        routeStarted = false
        optionChangeAwaitingRouteStart = false
    }

    /** 경로 옵션 변경 뒤 올 수 있는 후속 경로 시작 콜백을 표시한다. */
    fun onRouteOptionChangeRequested() {
        optionChangeAwaitingRouteStart = true
    }

    /** 현재 경로 상태에 따라 경로 시작 콜백의 종류를 한 번 판정한다. */
    fun onRouteStarted(): RouteStartType {
        if (!routeStarted) {
            routeStarted = true
            optionChangeAwaitingRouteStart = false
            return RouteStartType.NEW_ROUTE
        }
        if (optionChangeAwaitingRouteStart) {
            optionChangeAwaitingRouteStart = false
            return RouteStartType.OPTION_CHANGE_FOLLOW_UP
        }
        return RouteStartType.SAME_ROUTE_FOLLOW_UP
    }
}

/** 차량 저빔 문자열 ON/OFF만 약속 엔진 상태로 정확히 변환한다. */
fun mapLowBeamState(raw: String?): HeadlampState = when (raw) {
    "ON" -> HeadlampState.ON
    "OFF" -> HeadlampState.OFF
    else -> HeadlampState.OTHER
}
