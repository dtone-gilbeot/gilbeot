package ai.gilbeot.app.core

/** SDK 없이 회전 정보를 표현한다. distanceM은 구간 또는 출발점 기준 거리다. */
data class Tbt(val type: String, val distanceM: Double, val description: String?)

enum class TurnDirection { LEFT, RIGHT, NONE }

/** 회전 종류를 운전자가 바로 이해할 수 있는 문장으로 바꾼다. */
object TurnPhrases {
    const val SIGNAL_PRAISE = "지시등 좋습니다."

    private val targetTypes = setOf(
        "TURN_LEFT",
        "TURN_SHARP_L",
        "TURN_UTURN",
        "TURN_ENTER_IC_L",
        "TURN_ENTER_IC_R",
        "TURN_ENTER_IC_C",
    )

    /** 회전 미리 안내 대상인지 확인한다. */
    fun isTarget(type: String): Boolean = type in targetTypes

    /** 500m 단계의 안전한 안내 문구를 만든다. */
    fun at500(tbt: Tbt): String = prefix(tbt.description) + when (tbt.type) {
        "TURN_UTURN" -> "곧 유턴합니다. 천천히 속도를 줄이세요."
        "TURN_ENTER_IC_L" -> "곧 왼쪽 나들목으로 진입합니다. 미리 차로를 확인하세요."
        "TURN_ENTER_IC_R" -> "곧 오른쪽 나들목으로 진입합니다. 미리 차로를 확인하세요."
        "TURN_ENTER_IC_C" -> "곧 나들목으로 진입합니다. 미리 차로를 확인하세요."
        else -> "곧 왼쪽으로 돕니다. 지금 왼쪽 차로로 천천히 옮기세요."
    }

    /** 300m 단계의 안전한 안내 문구를 만든다. */
    fun at300(tbt: Tbt): String = prefix(tbt.description) + when (tbt.type) {
        "TURN_UTURN" -> "이제 유턴합니다. 맞은편 차를 확인하고 안전하게 도세요."
        "TURN_ENTER_IC_L" -> "이제 왼쪽 나들목으로 진입합니다. 안내 차로를 따라가세요."
        "TURN_ENTER_IC_R" -> "이제 오른쪽 나들목으로 진입합니다. 안내 차로를 따라가세요."
        "TURN_ENTER_IC_C" -> "이제 나들목으로 진입합니다. 안내 차로를 따라가세요."
        else -> "이제 왼쪽으로 돕니다. 맞은편 차가 지나갈 때까지 기다려도 괜찮습니다."
    }

    /** 회전 종류가 요구하는 방향지시등 방향을 돌려준다. */
    fun directionOf(type: String): TurnDirection = when (type) {
        "TURN_LEFT", "TURN_SHARP_L", "TURN_UTURN", "TURN_ENTER_IC_L" -> TurnDirection.LEFT
        "TURN_ENTER_IC_R" -> TurnDirection.RIGHT
        else -> TurnDirection.NONE
    }

    /** 빈 설명은 생략하고 목적지 설명은 자연스러운 방면 접두어로 만든다. */
    private fun prefix(description: String?): String {
        val value = description?.trim().orEmpty()
        if (value.isBlank()) return ""
        val destination = if (value.endsWith("방면")) value else "$value 방면"
        return "${destination}으로 "
    }
}
