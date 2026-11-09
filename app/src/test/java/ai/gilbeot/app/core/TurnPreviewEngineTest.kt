package ai.gilbeot.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnPreviewEngineTest {
    @Test
    fun `target turn emits 500 and 300 exactly once at 50 kph`() {
        val engine = engineWith(Tbt("TURN_LEFT", 700.0, "시청 방면"))
        engine.onSpeed(50.0 / 3.6, 0)

        assertEquals(listOf(Notice.TURN_500), engine.poll(15_000).map { it.priority })
        assertTrue(engine.poll(20_000).isEmpty())
        assertEquals(listOf(Notice.TURN_300), engine.poll(30_000).map { it.priority })
        assertTrue(engine.poll(35_000).isEmpty())
    }

    @Test
    fun `passed turn is skipped without late notice`() {
        val engine = engineWith(Tbt("TURN_LEFT", 100.0, null))
        engine.onSpeed(20.0, 0)
        assertTrue(engine.poll(10_000).isEmpty())
        assertNull(engine.nextTargetTurn())
    }

    @Test
    fun `starting inside 300 emits only 300`() {
        val engine = engineWith(Tbt("TURN_LEFT", 250.0, null))
        assertEquals(listOf(Notice.TURN_300), engine.poll(0).map { it.priority })
        assertTrue(engine.poll(1_000).isEmpty())
    }

    @Test
    fun `late first poll that skips past 500 emits only 300`() {
        val engine = engineWith(Tbt("TURN_LEFT", 700.0, null))
        engine.onSpeed(50.0 / 3.6, 0)

        assertEquals(
            listOf(Notice.TURN_300),
            engine.poll(40_000).map { it.priority },
        )
    }

    @Test
    fun `speed events use trapezoid integration`() {
        val engine = engineWith(Tbt("TURN_LEFT", 1_000.0, null))
        engine.onSpeed(0.0, 0)
        engine.onSpeed(10.0, 10_000)

        assertEquals(950.0, engine.remainingToNext()!!.second, 0.001)
    }

    @Test
    fun `new tbt list resets traveled distance and notice history`() {
        val engine = engineWith(Tbt("TURN_LEFT", 450.0, null))
        val queue = NoticeQueue()
        val first = engine.poll(0).single()
        assertEquals(Notice.TURN_500, first.priority)
        assertTrue(queue.accept(first))
        queue.poll()
        engine.onSpeed(10.0, 0)
        engine.poll(20_000)

        engine.onTbtList(listOf(Tbt("TURN_LEFT", 450.0, null)), 20_000)

        assertEquals(450.0, engine.remainingToNext()!!.second, 0.001)
        val second = engine.poll(20_000).single()
        assertEquals(Notice.TURN_500, second.priority)
        org.junit.Assert.assertNotEquals(first.key, second.key)
        assertTrue(queue.accept(second))
    }

    @Test
    fun `poll integrates last speed without new events and avoids double count`() {
        val engine = engineWith(Tbt("TURN_LEFT", 1_000.0, null))
        engine.onSpeed(10.0, 0)
        engine.poll(10_000)
        assertEquals(900.0, engine.remainingToNext()!!.second, 0.001)

        engine.onSpeed(10.0, 10_000)
        assertEquals(900.0, engine.remainingToNext()!!.second, 0.001)
    }

    @Test
    fun `absolute mode treats each distance as start position`() {
        val engine = TurnPreviewEngine(DistanceMode.ABSOLUTE)
        engine.onTbtList(
            listOf(Tbt("TURN_RIGHT", 700.0, null), Tbt("TURN_LEFT", 990.0, null)),
            0,
        )
        assertEquals(990.0, engine.nextTargetTurn()!!.remainingM, 0.001)
    }

    @Test
    fun `real tbt list keeps non targets in cumulative positions`() {
        val engine = TurnPreviewEngine()
        engine.onTbtList(realSeoulTbt(), 0)

        assertEquals("TURN_RIGHT", engine.nextTurn()!!.tbt.type)
        assertEquals("TURN_ENTER_IC_C", engine.nextTargetTurn()!!.tbt.type)
        assertEquals(860.0, engine.nextTargetTurn()!!.remainingM, 0.001)

        engine.onSpeed(10.0, 0)
        engine.poll(1_000_000)
        assertEquals("TURN_LEFT", engine.nextTargetTurn()!!.tbt.type)
        assertEquals(10_420.0, engine.nextTargetTurn()!!.remainingM, 0.001)
    }

    @Test
    fun `matching turn signal is praised once within 150 after 300 notice`() {
        val engine = engineWith(Tbt("TURN_LEFT", 400.0, null))
        engine.poll(0)
        engine.onSpeed(10.0, 0)
        engine.poll(11_000)
        assertTrue(engine.onTurnSignal(TurnDirection.LEFT, 20_000).isEmpty())
        val praise = engine.onTurnSignal(TurnDirection.LEFT, 25_000)
        assertEquals("지시등 좋습니다.", praise.single().text)
        assertTrue(engine.onTurnSignal(TurnDirection.LEFT, 26_000).isEmpty())
    }

    @Test
    fun `wrong or absent signal never scolds`() {
        val engine = engineWith(Tbt("TURN_LEFT", 300.0, null))
        engine.poll(0)
        engine.onSpeed(10.0, 0)
        assertTrue(engine.onTurnSignal(TurnDirection.RIGHT, 16_000).isEmpty())
        assertTrue(engine.poll(17_000).none { it.text.contains("지시등") })
    }

    private fun engineWith(tbt: Tbt) = TurnPreviewEngine().also {
        it.onTbtList(listOf(tbt), 0)
    }

    private fun realSeoulTbt() = listOf(
        Tbt("TURN_RIGHT", 700.0, "대왕판교IC 방면"),
        Tbt("TURN_RIGHT", 50.0, "양재 방면"),
        Tbt("TURN_ENTER_IC_C", 110.0, "대왕판교IC 방면"),
        Tbt("TURN_TOLL", 40.0, "대왕판교톨게이트"),
        Tbt("TURN_AVOID_OVER_L", 13_000.0, "한남대교,올림픽대로(종합운동장) 방면"),
        Tbt("TURN_ENTER_OVER_L", 2_100.0, "남산1호터널 방면"),
        Tbt("TURN_ENTER_OVER_L", 850.0, "남산1호터널 방면"),
        Tbt("TURN_TOLL", 2_300.0, "남산1호터널톨게이트"),
        Tbt("TURN_ENTER_OVER_L", 280.0, "남대문시장 방면"),
        Tbt("TURN_LEFT", 990.0, "을지로1가 방면"),
        Tbt("TURN_RIGHT", 720.0, "청계광장 방면"),
        Tbt("TURN_LEFT", 90.0, "광화문 방면"),
        Tbt("TURN_ARRIVE", 120.0, "도착"),
    )
}
