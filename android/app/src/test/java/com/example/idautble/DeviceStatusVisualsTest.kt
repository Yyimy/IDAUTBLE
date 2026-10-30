package com.example.idautble

import org.junit.Assert.*
import org.junit.Test

class DeviceStatusVisualsTest {
    @Test
    fun previewCadenceMatchesPublishedFirmwareIntervals() {
        // Contract from firmware ledPatternOn: period and half-open ON windows.
        val patterns =
            mapOf(
                "DISCONNECTED" to (1000L to listOf(0L until 500L)),
                "BAT_LOW" to (4000L to listOf(0L until 200L, 400L until 600L)),
                "BAT_CRITICAL" to
                    (2000L to listOf(0L until 200L, 400L until 600L, 800L until 1000L)),
                "ERROR" to (400L to listOf(0L until 200L)),
                "OTA" to (1000L to listOf(0L until 800L)),
            )
        for ((mode, pattern) in patterns) {
            val (period, windows) = pattern
            for (t in 0L..period * 2) {
                assertEquals(
                    "$mode at $t ms",
                    windows.any { t % period in it },
                    ledPreviewOn(mode, t, true),
                )
            }
            assertEquals(
                windows.any { Long.MAX_VALUE % period in it },
                ledPreviewOn(mode, Long.MAX_VALUE, true),
            )
        }
        assertTrue(ledPreviewOn("READY", 0, true))
        assertTrue(ledPreviewOn("READY", Long.MAX_VALUE, true))
    }

    @Test
    fun staleSnapshotsAndUnknownModesNeverFlash() {
        for (mode in listOf("READY", "DISCONNECTED", "BAT_LOW", "BAT_CRITICAL", "ERROR", "OTA")) {
            for (t in listOf(0L, 199L, 400L, 1000L)) assertFalse(ledPreviewOn(mode, t, false))
            assertEquals(StatusTone.NEUTRAL, ledStatusTone(mode, false))
        }
        assertFalse(ledPreviewOn("UNKNOWN", 0, true))
    }

    @Test
    fun unavailableMeasurementsHaveNoBatteryLevelEvenWithCachedPercent() {
        for (state in listOf("ABSENT", "INVALID", "STALE")) {
            assertNull(batteryBarLevel(BatteryStatus(state, 18.0, 66, 0)))
        }
        assertNull(batteryBarLevel(BatteryStatus("PRESENT", 18.0, null, 0)))
        assertNull(batteryBarLevel(BatteryStatus("PRESENT", 18.0, 100, 0)))
        assertEquals(0f, batteryBarLevel(BatteryStatus("PRESENT", 14.0, 0, 0))!!, 0f)
        assertEquals(0.15f, batteryBarLevel(BatteryStatus("PRESENT", 15.0, 15, 0))!!, 0.001f)
        assertEquals(0.99f, batteryBarLevel(BatteryStatus("PRESENT", 20.0, 99, 0))!!, 0.001f)
    }

    @Test
    fun batteryColoursFollowLevelsAndBecomeNeutralWhenOld() {
        for ((pct, tone) in
            listOf(
                0 to StatusTone.FAULT,
                10 to StatusTone.FAULT,
                11 to StatusTone.WARNING,
                20 to StatusTone.WARNING,
                21 to StatusTone.GOOD,
                99 to StatusTone.GOOD,
            )) {
            val battery = BatteryStatus("PRESENT", 18.0, pct, 0)
            assertEquals(tone, batteryStatusTone(battery, true))
            assertEquals(StatusTone.NEUTRAL, batteryStatusTone(battery, false))
        }
        assertEquals(
            StatusTone.NEUTRAL,
            batteryStatusTone(BatteryStatus("ABSENT", 0.0, null, 0), true),
        )
        assertEquals(
            StatusTone.FAULT,
            batteryStatusTone(BatteryStatus("INVALID", null, null, null), true),
        )
        assertEquals(
            StatusTone.WARNING,
            batteryStatusTone(BatteryStatus("STALE", null, null, 3001), true),
        )
    }

    @Test
    fun freshnessRequiresConnectionAndExpiresAfter15Seconds() {
        assertFalse(deviceStatusIsFresh(null, 1000, true))
        assertFalse(deviceStatusIsFresh(1000, 1000, false))
        assertTrue(deviceStatusIsFresh(1000, 1000, true))
        assertTrue(deviceStatusIsFresh(1000, 16000, true))
        assertFalse(deviceStatusIsFresh(1000, 16001, true))
    }
}
