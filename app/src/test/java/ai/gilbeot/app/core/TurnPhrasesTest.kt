package ai.gilbeot.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TurnPhrasesTest {
    @Test
    fun `left phrases match handoff and use direction prefix`() {
        val tbt = Tbt("TURN_LEFT", 600.0, "을지로1가 방면")
        assertEquals(
            "을지로1가 방면으로 곧 왼쪽으로 돕니다. 지금 왼쪽 차로로 천천히 옮기세요.",
            TurnPhrases.at500(tbt),
        )
        assertEquals(
            "을지로1가 방면으로 이제 왼쪽으로 돕니다. 맞은편 차가 지나갈 때까지 기다려도 괜찮습니다.",
            TurnPhrases.at300(tbt),
        )
    }

    @Test
    fun `uturn and ic use separate safe wording`() {
        val uturn = TurnPhrases.at300(Tbt("TURN_UTURN", 200.0, null))
        val ic = TurnPhrases.at300(Tbt("TURN_ENTER_IC_R", 200.0, "부산 방면"))
        assertEquals("이제 유턴합니다. 맞은편 차를 확인하고 안전하게 도세요.", uturn)
        assertEquals("부산 방면으로 이제 오른쪽 나들목으로 진입합니다. 안내 차로를 따라가세요.", ic)
        assertFalse((uturn + ic).contains("비보호"))
        assertFalse((uturn + ic).contains("신호 없는"))
    }

    @Test
    fun `direction maps turn types`() {
        assertEquals(TurnDirection.LEFT, TurnPhrases.directionOf("TURN_ENTER_IC_L"))
        assertEquals(TurnDirection.RIGHT, TurnPhrases.directionOf("TURN_ENTER_IC_R"))
        assertEquals(TurnDirection.NONE, TurnPhrases.directionOf("TURN_ENTER_IC_C"))
    }
}
