package ai.champi.context

import ai.champi.core.context.ContextSnapshot
import ai.champi.core.context.toSystemMessage
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM unit tests for [toSystemMessage] reachable from `:context`'s test source set.
 * Complements the identical set in `:core` — kept here so `:context`'s own test run
 * catches any accidental breakage at the module boundary.
 */
class ContextSnapshotToSystemMessageTest {

    @Test
    fun emptySnapshot_returnsNull() {
        assertNull(ContextSnapshot().toSystemMessage())
    }

    @Test
    fun snapshotWithAnyField_returnsNonNull() {
        assertNotNull(ContextSnapshot(connectivityType = "WiFi").toSystemMessage())
        assertNotNull(ContextSnapshot(batteryPercent = 99, isCharging = false).toSystemMessage())
        assertNotNull(ContextSnapshot(foregroundAppPackage = "com.foo").toSystemMessage())
        assertNotNull(ContextSnapshot(latitude = 0.0, longitude = 0.0).toSystemMessage())
    }
}
