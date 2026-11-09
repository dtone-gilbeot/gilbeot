package ai.gilbeot.app.core

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromiseEngineTest {
    private val kst = ZoneId.of("Asia/Seoul")
    private val sunset = ZonedDateTime.of(2026, 11, 5, 17, 30, 0, 0, kst)
    private val now = ZonedDateTime.of(2026, 11, 5, 16, 0, 0, 0, kst).toInstant().toEpochMilli()

    @Test
    fun `arrival before sunset buffer reports before sunset without route request`() {
        val result = PromiseEngine().onRouteStarted(now, 4_000, sunset)
        assertEquals(
            "해 지는 시각은 오후 5시 30분입니다. 지금 가면 해 지기 전, 오후 5시 6분에 도착합니다.",
            result.notice?.text,
        )
        assertFalse(result.requestComfortable)
    }

    @Test
    fun `arrival after sunset buffer reports after and requests comfortable`() {
        val result = PromiseEngine().onRouteStarted(now, 5_000, sunset)
        assertEquals(
            "도착은 오후 5시 23분, 해가 진 뒤입니다. 약속하신 대로 편안한 경로를 요청할게요.",
            result.notice?.text,
        )
        assertTrue(result.requestComfortable)
    }

    @Test
    fun `headlamp off to on is handled once per route`() {
        val engine = PromiseEngine()
        engine.onRouteStarted(now, 4_000, sunset)
        assertTrue(engine.onHeadlamp(HeadlampState.OFF, now).notices.isEmpty())
        val first = engine.onHeadlamp(HeadlampState.ON, now + 1)
        assertEquals(2, first.notices.size)
        assertTrue(first.requestComfortable)
        assertTrue(first.showRestButtonWhenStopped)
        assertTrue(engine.onHeadlamp(HeadlampState.ON, now + 2).notices.isEmpty())
    }

    @Test
    fun `headlamp off reported before route start still counts when on arrives during route`() {
        val engine = PromiseEngine()
        engine.onHeadlamp(HeadlampState.OFF, now - 60_000)
        engine.onRouteStarted(now, 4_000, sunset)
        val result = engine.onHeadlamp(HeadlampState.ON, now + 1)
        assertEquals("전조등이 켜졌어요. 약속하신 대로 편안한 경로를 요청할게요.", result.notices.first().text)
    }

    @Test
    fun `headlamp on while comfortable uses going branch`() {
        val engine = PromiseEngine()
        engine.onRouteStarted(now, 4_000, sunset)
        engine.onRouteOptionChanged(true)
        engine.onHeadlamp(HeadlampState.OFF, now)
        val result = engine.onHeadlamp(HeadlampState.ON, now + 1)
        assertEquals("전조등이 켜졌어요. 편안한 경로는 이미 요청했어요.", result.notices.first().text)
        assertFalse(result.requestComfortable)
    }

    @Test
    fun `headlamp after sunset request does not promise a second request`() {
        val engine = PromiseEngine()
        engine.onHeadlamp(HeadlampState.OFF, now)
        val route = engine.onRouteStarted(now, 40_000, sunset)
        assertTrue(route.requestComfortable)
        engine.onRouteOptionChanged(true) // PromiseController.changeComfortable 성공 시 호출
        val result = engine.onHeadlamp(HeadlampState.ON, now + 1)
        assertEquals("전조등이 켜졌어요. 편안한 경로는 이미 요청했어요.", result.notices.first().text)
        assertFalse(result.requestComfortable)
    }

    @Test
    fun `break promise announces at one hour fifty once`() {
        val engine = PromiseEngine()
        engine.onSpeed(2.0, 0)
        assertNull(engine.pollDriving(6_599_000))
        assertEquals(
            "곧 두 시간째 운전입니다. 다음 휴게소에서 쉬어 가요.",
            engine.pollDriving(6_600_000)?.text,
        )
        assertNull(engine.pollDriving(7_000_000))
    }

    @Test
    fun `three minute stop resets continuous driving`() {
        val engine = PromiseEngine()
        engine.onSpeed(2.0, 0)
        engine.pollDriving(6_500_000)
        engine.onSpeed(0.0, 6_500_000)
        engine.pollDriving(6_680_000)
        engine.onSpeed(2.0, 6_680_000)
        assertNull(engine.pollDriving(6_780_000))
    }

    @Test
    fun `scale sixty triggers and resets in real seconds`() {
        val engine = PromiseEngine(demoTimeScale = 60.0)
        engine.onSpeed(2.0, 0)
        assertEquals(
            "곧 두 시간째 운전입니다. 다음 휴게소에서 쉬어 가요.",
            engine.pollDriving(110_000)?.text,
        )
        engine.onSpeed(0.0, 110_000)
        engine.pollDriving(113_000)
        engine.onSpeed(2.0, 113_000)
        assertNull(engine.pollDriving(114_000))
    }

    @Test
    fun `new driving cycle uses a new notice key after stop reset`() {
        val engine = PromiseEngine(demoTimeScale = 60.0)
        val queue = NoticeQueue()
        engine.onSpeed(2.0, 0)
        val first = engine.pollDriving(110_000)!!
        assertTrue(queue.accept(first))
        queue.poll()
        engine.onSpeed(0.0, 110_000)
        engine.pollDriving(113_000)
        engine.onSpeed(2.0, 113_000)
        val second = engine.pollDriving(223_000)!!

        org.junit.Assert.assertNotEquals(first.key, second.key)
        assertTrue(queue.accept(second))
    }

    @Test
    fun `disabled promises do not produce notices`() {
        val engine = PromiseEngine(sunsetPromiseEnabled = false, breakPromiseEnabled = false)
        assertNull(engine.onRouteStarted(now, 5_000, sunset).notice)
        engine.onSpeed(2.0, 0)
        assertNull(engine.pollDriving(7_000_000))
    }
}
