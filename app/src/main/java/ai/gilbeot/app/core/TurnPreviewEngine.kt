package ai.gilbeot.app.core

enum class DistanceMode { CUMULATIVE, ABSOLUTE }

/** UI가 표시하거나 안내 판단에 쓸 다음 회전과 남은 거리다. */
data class NextTurn(val tbt: Tbt, val remainingM: Double)

/** 큰 카드에 표시할 대상 회전과 그보다 먼저 만나는 일반 회전이다. */
data class TurnCardSelection(val primary: NextTurn, val before: NextTurn?)

/** 차속을 적분해 각 회전까지 남은 거리와 안내 시점을 판단한다. */
class TurnPreviewEngine(
    val distanceMode: DistanceMode = DistanceMode.CUMULATIVE,
) {
    private data class PositionedTurn(val index: Int, val tbt: Tbt, val positionM: Double)

    private var turns = emptyList<PositionedTurn>()
    var traveledM = 0.0
        private set
    var speedMps = 0.0
        private set
    private var lastIntegrationMs: Long? = null
    private var routeGeneration = 0L
    private val announced500 = mutableSetOf<Int>()
    private val announced300 = mutableSetOf<Int>()
    private val praised = mutableSetOf<Int>()

    /** 새 TBT 목록을 출발점 기준으로 배치하고 주행·안내 이력을 초기화한다. */
    fun onTbtList(list: List<Tbt>, atMs: Long) {
        routeGeneration += 1
        var cumulative = 0.0
        turns = list.mapIndexed { index, tbt ->
            val position = when (distanceMode) {
                DistanceMode.CUMULATIVE -> {
                    cumulative += tbt.distanceM
                    cumulative
                }
                DistanceMode.ABSOLUTE -> tbt.distanceM
            }
            PositionedTurn(index, tbt, position)
        }
        traveledM = 0.0
        lastIntegrationMs = atMs
        announced500.clear()
        announced300.clear()
        praised.clear()
    }

    /** 새 차속까지 사다리꼴로 적분하고 현재 차속을 바꾼다. */
    fun onSpeed(mps: Double, atMs: Long) {
        val safeSpeed = mps.coerceAtLeast(0.0)
        val last = lastIntegrationMs
        if (last != null && atMs > last) {
            val seconds = (atMs - last) / 1_000.0
            traveledM += (speedMps + safeSpeed) * 0.5 * seconds
        }
        speedMps = safeSpeed
        lastIntegrationMs = maxOf(atMs, last ?: atMs)
    }

    /** 마지막 차속으로 현재 시각까지 적분한 뒤 새 거리 안내를 돌려준다. */
    fun poll(nowMs: Long): List<Notice> {
        integrateConstantSpeed(nowMs)
        val notices = mutableListOf<Notice>()
        val target = nextTargetPositioned() ?: return notices
        val remaining = target.positionM - traveledM
        if (remaining < 0.0) return notices

        if (remaining <= 300.0 && target.index !in announced300) {
            // 500m 안내를 못 하고 300m 안에 들어왔으면(출발 시 이미 가까움 등) 300m 안내만 한다.
            announced500 += target.index
            announced300 += target.index
            notices += notice300(target)
        } else if (remaining <= 500.0 && target.index !in announced500) {
            announced500 += target.index
            notices += notice500(target)
        }
        return notices
    }

    /** 현재 UI 카드에 표시할 다음 모든 종류의 회전을 돌려준다. */
    fun nextTurn(): NextTurn? = turns.firstOrNull { it.positionM - traveledM >= 0.0 }
        ?.let { NextTurn(it.tbt, it.positionM - traveledM) }

    /** 안내 판정에 쓰는 다음 대상 회전을 돌려준다. */
    fun nextTargetTurn(): NextTurn? = nextTargetPositioned()
        ?.let { NextTurn(it.tbt, it.positionM - traveledM) }

    /** 대상 회전을 우선하되, 대상이 없으면 다음 일반 회전을 카드 대상으로 고른다. */
    fun turnCardSelection(): TurnCardSelection? {
        val any = turns.firstOrNull { it.positionM - traveledM >= 0.0 } ?: return null
        val target = nextTargetPositioned()
        val primary = target ?: any
        val before = any.takeIf { target != null && it.index != target.index }
        return TurnCardSelection(
            primary = NextTurn(primary.tbt, primary.positionM - traveledM),
            before = before?.let { NextTurn(it.tbt, it.positionM - traveledM) },
        )
    }

    /** 호환용으로 다음 모든 종류 회전과 남은 거리를 Pair로 돌려준다. */
    fun remainingToNext(): Pair<Tbt, Double>? = nextTurn()?.let { it.tbt to it.remainingM }

    /** 300m 안내 뒤 150m 안에서 방향이 맞으면 칭찬을 한 번 돌려준다. */
    fun onTurnSignal(dir: TurnDirection, nowMs: Long): List<Notice> {
        integrateConstantSpeed(nowMs)
        val target = nextTargetPositioned() ?: return emptyList()
        val remaining = target.positionM - traveledM
        val expected = TurnPhrases.directionOf(target.tbt.type)
        if (target.index !in announced300 || remaining !in 0.0..150.0) return emptyList()
        if (expected == TurnDirection.NONE || dir != expected || target.index in praised) return emptyList()
        praised += target.index
        return listOf(
            Notice(
                TurnPhrases.SIGNAL_PRAISE,
                Notice.TURN_300,
                "route-$routeGeneration-turn-${target.index}-signal",
            ),
        )
    }

    /** 아직 지나지 않은 첫 안내 대상의 내부 위치 정보를 찾는다. */
    private fun nextTargetPositioned(): PositionedTurn? = turns.firstOrNull {
        TurnPhrases.isTarget(it.tbt.type) && it.positionM - traveledM >= 0.0
    }

    /** 새 차속이 없던 구간을 마지막 차속으로 적분한다. */
    private fun integrateConstantSpeed(nowMs: Long) {
        val last = lastIntegrationMs ?: run {
            lastIntegrationMs = nowMs
            return
        }
        if (nowMs <= last) return
        traveledM += speedMps * (nowMs - last) / 1_000.0
        lastIntegrationMs = nowMs
    }

    /** 회전별 중복 방지 키가 있는 500m 안내를 만든다. */
    private fun notice500(turn: PositionedTurn) = Notice(
        TurnPhrases.at500(turn.tbt),
        Notice.TURN_500,
        "route-$routeGeneration-turn-${turn.index}-500",
    )

    /** 회전별 중복 방지 키가 있는 300m 안내를 만든다. */
    private fun notice300(turn: PositionedTurn) = Notice(
        TurnPhrases.at300(turn.tbt),
        Notice.TURN_300,
        "route-$routeGeneration-turn-${turn.index}-300",
    )
}
