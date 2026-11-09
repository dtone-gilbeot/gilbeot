package ai.gilbeot.app.core

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M3 약속 런타임의 순수 시간·경로·신호 변환을 검증한다. */
class PromiseRuntimeTest {
    private val kst = ZoneId.of("Asia/Seoul")
    private val realNow = ZonedDateTime.of(2026, 11, 9, 14, 0, 30, 0, kst)
        .toInstant()
        .toEpochMilli()

    /** 주입하지 않은 시계가 실제 시각을 그대로 반환하는지 검증한다. */
    @Test
    fun `clock without injection follows real clock`() {
        val clock = PromiseClock.fromIntentExtra(null, realNow)

        assertEquals(realNow + 65_000, clock.nowMs(realNow + 65_000))
        assertNull(clock.injectedLabel)
    }

    /** HH:mm 주입 시 오늘 해당 분부터 실제 경과만큼 진행하는지 검증한다. */
    @Test
    fun `injected clock starts at kst time and advances with real elapsed time`() {
        val clock = PromiseClock.fromIntentExtra("15:30", realNow)
        val expectedStart = ZonedDateTime.of(2026, 11, 9, 15, 30, 0, 0, kst)
            .toInstant()
            .toEpochMilli()

        assertEquals(expectedStart, clock.nowMs(realNow))
        assertEquals(expectedStart + 65_000, clock.nowMs(realNow + 65_000))
        assertEquals("15:30", clock.injectedLabel)
    }

    /** 형식과 범위를 벗어난 시각 문자열을 엄격히 거부하는지 검증한다. */
    @Test
    fun `clock rejects invalid time extras`() {
        listOf("7:05", "07:5", "24:00", "12:60", " 07:05", "07:05 ").forEach { raw ->
            val error = runCatching { PromiseClock.fromIntentExtra(raw, realNow) }.exceptionOrNull()
            assertTrue("$raw should fail", error is IllegalArgumentException)
        }
    }

    /** 옵션 변경 뒤 다시 온 경로 시작이 새 경로로 분류되지 않는지 검증한다. */
    @Test
    fun `route start after option change is follow up`() {
        val gate = RouteStartGate()
        gate.onFreshRouteRequested()
        assertEquals(RouteStartType.NEW_ROUTE, gate.onRouteStarted())

        gate.onRouteOptionChangeRequested()

        assertEquals(RouteStartType.OPTION_CHANGE_FOLLOW_UP, gate.onRouteStarted())
    }

    /** 명시적인 새 경로 요청만 다음 경로 시작을 새 경로로 만드는지 검증한다. */
    @Test
    fun `fresh route request resets route gate`() {
        val gate = RouteStartGate()
        assertEquals(RouteStartType.NEW_ROUTE, gate.onRouteStarted())
        assertEquals(RouteStartType.SAME_ROUTE_FOLLOW_UP, gate.onRouteStarted())

        gate.onFreshRouteRequested()

        assertEquals(RouteStartType.NEW_ROUTE, gate.onRouteStarted())
    }

    /** 저빔 문자열이 대소문자 보정 없이 정확히 세 상태로 매핑되는지 검증한다. */
    @Test
    fun `low beam mapping accepts exact on and off only`() {
        assertEquals(HeadlampState.ON, mapLowBeamState("ON"))
        assertEquals(HeadlampState.OFF, mapLowBeamState("OFF"))
        listOf(null, "", "on", "LOW_BEAM", "OTHER").forEach { raw ->
            assertEquals(HeadlampState.OTHER, mapLowBeamState(raw))
        }
    }

    /** 연속 운전 표시 분이 데모 배속을 반영한 누적값인지 검증한다. */
    @Test
    fun `continuous driving scaled minutes exposes accumulated engine time`() {
        val engine = PromiseEngine(demoTimeScale = 60.0)
        engine.onSpeed(2.0, 0)
        engine.pollDriving(72_000)

        assertEquals(72L, engine.continuousDrivingScaledMinutes)
        assertFalse(engine.continuousDrivingScaledMinutes == 1L)
    }

    /** 일몰 판정 결과가 화면과 로그에 재사용할 계산값을 함께 내놓는지 검증한다. */
    @Test
    fun `route result exposes the exact arrival and sunset decision`() {
        val now = ZonedDateTime.of(2026, 11, 5, 16, 0, 0, 0, kst)
            .toInstant()
            .toEpochMilli()
        val sunset = ZonedDateTime.of(2026, 11, 5, 17, 30, 0, 0, kst)

        val result = PromiseEngine().onRouteStarted(now, 5_000, sunset)

        assertEquals(sunset, result.sunset)
        assertEquals(ZonedDateTime.of(2026, 11, 5, 17, 23, 20, 0, kst), result.arrival)
        assertFalse(result.beforeSunset!!)
    }
}
