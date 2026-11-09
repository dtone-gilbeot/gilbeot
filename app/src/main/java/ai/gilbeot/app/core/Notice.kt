package ai.gilbeot.app.core

/** 화면과 음성으로 전달할 안내 한 건이다. */
data class Notice(val text: String, val priority: Int, val key: String) {
    companion object {
        const val TURN_300 = 300
        const val TURN_500 = 200
        const val PROMISE = 100
    }
}

/** 우선순위와 같은 우선순위의 입력 순서를 보존하는 작은 안내 큐다. */
class NoticeQueue {
    private data class Entry(val notice: Notice, val order: Long)

    private val acceptedKeys = mutableSetOf<String>()
    private val entries = mutableListOf<Entry>()
    private var nextOrder = 0L

    /** 처음 본 키의 안내만 큐에 넣는다. */
    fun accept(notice: Notice): Boolean {
        if (!acceptedKeys.add(notice.key)) return false
        entries += Entry(notice, nextOrder++)
        return true
    }

    /** 가장 급한 안내를 꺼내며, 동률이면 먼저 들어온 안내를 택한다. */
    fun poll(): Notice? {
        val best = entries.minWithOrNull(
            compareByDescending<Entry> { it.notice.priority }.thenBy { it.order },
        ) ?: return null
        entries.remove(best)
        return best.notice
    }
}
