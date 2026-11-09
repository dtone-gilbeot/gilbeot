package ai.gilbeot.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeQueueTest {
    @Test
    fun `priority order and fifo within same priority`() {
        val queue = NoticeQueue()
        queue.accept(Notice("promise", Notice.PROMISE, "p"))
        queue.accept(Notice("500 first", Notice.TURN_500, "500-a"))
        queue.accept(Notice("300", Notice.TURN_300, "300"))
        queue.accept(Notice("500 second", Notice.TURN_500, "500-b"))

        assertEquals("300", queue.poll()?.text)
        assertEquals("500 first", queue.poll()?.text)
        assertEquals("500 second", queue.poll()?.text)
        assertEquals("promise", queue.poll()?.text)
    }

    @Test
    fun `duplicate accepted key is dropped`() {
        val queue = NoticeQueue()
        assertTrue(queue.accept(Notice("first", Notice.PROMISE, "same")))
        assertFalse(queue.accept(Notice("second", Notice.TURN_300, "same")))
        assertEquals("first", queue.poll()?.text)
        assertEquals(null, queue.poll())
    }
}
