package ai.gilbeot.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TurnCardSelectionTest {
    /** 대상 전에 일반 회전이 있으면 대상은 큰 카드, 일반 회전은 보조 줄로 고른다. */
    @Test
    fun `target is primary and earlier non target is secondary`() {
        val engine = TurnPreviewEngine()
        engine.onTbtList(
            listOf(
                Tbt("TURN_RIGHT", 90.0, "골목 우회전"),
                Tbt("TURN_LEFT", 210.0, "시청 방면"),
            ),
            0L,
        )

        val card = engine.turnCardSelection()!!

        assertEquals("TURN_LEFT", card.primary.tbt.type)
        assertEquals(300.0, card.primary.remainingM, 0.001)
        assertEquals("TURN_RIGHT", card.before?.tbt?.type)
        assertEquals(90.0, card.before?.remainingM ?: -1.0, 0.001)
    }

    /** 남은 대상 회전이 없으면 다음 일반 회전을 기존처럼 큰 카드에 표시한다. */
    @Test
    fun `next any turn is primary when target is absent`() {
        val engine = TurnPreviewEngine()
        engine.onTbtList(listOf(Tbt("TURN_ARRIVE", 120.0, "도착")), 0L)

        val card = engine.turnCardSelection()!!

        assertEquals("TURN_ARRIVE", card.primary.tbt.type)
        assertEquals(120.0, card.primary.remainingM, 0.001)
        assertNull(card.before)
    }
}
