package ai.champi.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [NotificationReplyReceiver.filterReplyText] — the pure extraction helper that
 * converts raw [CharSequence] input from a [RemoteInput] bundle to a non-blank [String].
 *
 * The full [RemoteInput.getResultsFromIntent] path requires an Android context and is verified
 * via the manual acceptance-criterion check on device.
 */
class NotificationReplyReceiverTest {

    @Test
    fun `filterReplyText returns text for normal input`() {
        val result = NotificationReplyReceiver.filterReplyText("Hello champi!")
        assertEquals("Hello champi!", result)
    }

    @Test
    fun `filterReplyText returns null for null input`() {
        assertNull(NotificationReplyReceiver.filterReplyText(null))
    }

    @Test
    fun `filterReplyText returns null for blank string`() {
        assertNull(NotificationReplyReceiver.filterReplyText("   "))
    }

    @Test
    fun `filterReplyText returns null for empty string`() {
        assertNull(NotificationReplyReceiver.filterReplyText(""))
    }

    @Test
    fun `filterReplyText preserves text with surrounding whitespace`() {
        val result = NotificationReplyReceiver.filterReplyText("  hello  ")
        assertEquals("  hello  ", result)
    }

    @Test
    fun `filterReplyText converts CharSequence to String`() {
        val cs: CharSequence = StringBuilder("from StringBuilder")
        val result = NotificationReplyReceiver.filterReplyText(cs)
        assertEquals("from StringBuilder", result)
    }
}
