package ai.champi.core.context

import ai.champi.core.persistence.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for [ContextSnapshot.toSystemMessage]. */
class ContextSnapshotTest {

    @Test
    fun allNullSnapshot_returnsNull() {
        assertNull(ContextSnapshot().toSystemMessage())
    }

    @Test
    fun locationOnlySnapshot_returnsNonNullSystemMessage() {
        val snapshot = ContextSnapshot(latitude = 37.422, longitude = -122.084)
        val msg = snapshot.toSystemMessage()
        assertNotNull(msg)
        assertEquals(MessageRole.SYSTEM, msg!!.role)
        assert(msg.content.contains("37.422")) { "Expected latitude in message: ${msg.content}" }
        assert(msg.content.contains("-122.084")) { "Expected longitude in message: ${msg.content}" }
    }

    @Test
    fun batterySnapshotCharging_includesPercentAndChargingLabel() {
        val snapshot = ContextSnapshot(batteryPercent = 80, isCharging = true)
        val msg = snapshot.toSystemMessage()
        assertNotNull(msg)
        assert(msg!!.content.contains("80%")) { "Expected 80% in message: ${msg.content}" }
        assert(msg.content.contains("charging")) { "Expected 'charging' in message: ${msg.content}" }
    }

    @Test
    fun batterySnapshotNotCharging_includesNotChargingLabel() {
        val snapshot = ContextSnapshot(batteryPercent = 42, isCharging = false)
        val msg = snapshot.toSystemMessage()
        assertNotNull(msg)
        assert(msg!!.content.contains("42%")) { "Expected 42% in message: ${msg.content}" }
        assert(msg.content.contains("not charging")) { "Expected 'not charging' in message: ${msg.content}" }
    }

    @Test
    fun connectivityOnlySnapshot_includesType() {
        val snapshot = ContextSnapshot(connectivityType = "WiFi")
        val msg = snapshot.toSystemMessage()
        assertNotNull(msg)
        assert(msg!!.content.contains("WiFi")) { "Expected 'WiFi' in message: ${msg.content}" }
    }

    @Test
    fun foregroundAppOnlySnapshot_includesPackageName() {
        val snapshot = ContextSnapshot(foregroundAppPackage = "com.example.app")
        val msg = snapshot.toSystemMessage()
        assertNotNull(msg)
        assert(msg!!.content.contains("com.example.app")) { "Expected package name in message: ${msg.content}" }
    }

    @Test
    fun allSignalsPopulated_systemMessageContainsAll() {
        val snapshot = ContextSnapshot(
            latitude = 1.0,
            longitude = 2.0,
            batteryPercent = 50,
            isCharging = true,
            connectivityType = "mobile",
            foregroundAppPackage = "com.test",
        )
        val msg = snapshot.toSystemMessage()
        assertNotNull(msg)
        val content = msg!!.content
        assert(content.contains("1.000")) { "Expected latitude in: $content" }
        assert(content.contains("2.000")) { "Expected longitude in: $content" }
        assert(content.contains("50%")) { "Expected battery in: $content" }
        assert(content.contains("mobile")) { "Expected connectivity in: $content" }
        assert(content.contains("com.test")) { "Expected foreground app in: $content" }
    }

    @Test
    fun locationOnlyWithoutLongitude_omitsLocationPart() {
        // latitude without longitude is meaningless — both must be non-null to appear
        val snapshot = ContextSnapshot(latitude = 37.0, longitude = null)
        val msg = snapshot.toSystemMessage()
        // no other fields set, so the whole message should be null
        assertNull(msg)
    }
}
