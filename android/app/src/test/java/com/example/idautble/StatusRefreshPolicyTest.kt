package com.example.idautble

import org.junit.Assert.*
import org.junit.Test

class StatusRefreshPolicyTest {
    @Test
    fun periodicRefreshUsesThirtySecondsSinceAttempt() {
        val policy = StatusRefreshPolicy()
        assertTrue(policy.shouldRefresh(0))
        policy.requested(0)
        assertFalse(policy.shouldRefresh(29_999))
        assertTrue(policy.shouldRefresh(30_000))
        policy.requested(30_000) // Even a timeout must not retry every timer tick.
        assertFalse(policy.shouldRefresh(31_000))
    }

    @Test
    fun waitsForReadingResponseBeforeRefreshing() {
        val policy = StatusRefreshPolicy()
        policy.requested(0)
        policy.readSent(30_000)
        assertFalse(policy.shouldRefresh(31_000))
        assertTrue(policy.readReceived())
        assertTrue(policy.shouldRefresh(32_150))
        policy.requested(32_150)
        assertFalse(policy.shouldRefresh(33_000))
    }

    @Test
    fun recentSuccessfulStatusDefersReadRefreshWithoutLosingIt() {
        val policy = StatusRefreshPolicy()
        policy.requested(0)
        policy.received(1_000)
        policy.readSent(2_000)
        assertTrue(policy.readReceived())
        assertFalse(policy.shouldRefresh(3_150))
        assertFalse(policy.shouldRefresh(5_999))
        assertTrue(policy.shouldRefresh(6_000)) // The deferred update still runs.
        policy.requested(6_000)
        assertFalse(policy.shouldRefresh(6_250))
    }

    @Test
    fun sensorFramesTriggerAfterReadsEvenWithPeriodicRefreshOff() {
        val policy = StatusRefreshPolicy()
        policy.autoRefreshEnabled = false
        policy.requested(0)
        assertTrue(policy.readReceived())
        assertTrue(policy.shouldRefresh(10_150))
        policy.requested(10_150)
        assertFalse(policy.shouldRefresh(10_151))
    }

    @Test
    fun missingReadResponseExpiresWithoutBlockingPeriodicRefresh() {
        val policy = StatusRefreshPolicy()
        policy.requested(0)
        policy.readSent(30_000)
        assertTrue(policy.readPending(37_999))
        assertFalse(policy.readPending(38_000))
        assertTrue(policy.readReceived()) // A late but valid frame still updates counters.
        assertTrue(policy.shouldRefresh(38_001))
    }

    @Test
    fun transportBusyCanDeferRefreshAfterReadWithoutLosingIt() {
        val policy = StatusRefreshPolicy()
        policy.requested(0)
        policy.readSent(10_000)
        assertTrue(policy.readReceived())
        // Activity does not evaluate policy until transport and OTA/foreground gates allow it.
        assertTrue(policy.shouldRefresh(12_000))
    }

    @Test
    fun resetAndOtaCancellationDiscardPendingReading() {
        val policy = StatusRefreshPolicy()
        policy.requested(0)
        policy.readSent(10_000)
        policy.cancelReading()
        assertFalse(policy.readPending(11_000))
        assertFalse(policy.shouldRefresh(11_000))
        policy.readSent(12_000)
        policy.reset()
        assertFalse(policy.readPending(13_000))
        assertTrue(policy.shouldRefresh(13_000))
    }

    @Test
    fun onlySixReadCommandsArmResponseWait() {
        for (cmd in "LBAPDI") assertTrue(
            isReadingCommand(byteArrayOf(27, cmd.code.toByte(), 13, 10))
        )
        for (cmd in "CO") assertFalse(isReadingCommand(byteArrayOf(27, cmd.code.toByte(), 13, 10)))
        assertFalse(isReadingCommand("STATUS?\r\n".toByteArray()))
        assertFalse(isReadingCommand(byteArrayOf(27, 76, 13)))
    }

    @Test
    fun otaPausesAndResumesOnTerminalErrorOrIdle() {
        val policy = StatusRefreshPolicy()
        policy.otaStarted()
        assertFalse(policy.shouldRefresh(30_000))
        policy.otaMessage("OTA=WAIT_PARAMS")
        policy.otaMessage("OTA_ERR=BAD_PARAMS")
        assertFalse(policy.shouldRefresh(60_000))
        policy.otaMessage("OTA_ERR=ABORT_WAIT_PARAMS")
        assertTrue(policy.shouldRefresh(61_000))
        policy.otaMessage("OTA=STATE=2;PCT=0")
        assertFalse(policy.shouldRefresh(62_000))
        policy.otaMessage("OTA=STATE=0;PCT=0")
        assertTrue(policy.shouldRefresh(63_000))
    }

    @Test
    fun otaDoneWaitsForReconnectionAndRejectedStartResumes() {
        val policy = StatusRefreshPolicy()
        policy.otaMessage("OTA=DONE REBOOT")
        assertFalse(policy.shouldRefresh(30_000))
        policy.reset()
        assertTrue(policy.shouldRefresh(31_000))
        policy.otaStarted()
        policy.otaMessage("ERR=NOT_IDLE")
        assertTrue(policy.shouldRefresh(32_000))
    }

    @Test
    fun disablingAfterReadsStillWaitsForReadingButKeepsPeriodicUpdates() {
        val policy = StatusRefreshPolicy()
        policy.refreshAfterReads = false
        policy.requested(0)
        policy.readSent(10_000)
        assertFalse(policy.shouldRefresh(11_000))
        assertFalse(policy.readReceived())
        assertFalse(policy.shouldRefresh(12_150))
        assertTrue(policy.shouldRefresh(30_000))
    }

    @Test
    fun disablingCancelsDeferredReadRefreshAndCanBeReenabled() {
        val policy = StatusRefreshPolicy()
        policy.requested(0)
        policy.readSent(10_000)
        assertTrue(policy.readReceived())
        policy.refreshAfterReads = false
        assertFalse(policy.shouldRefresh(12_000))
        policy.refreshAfterReads = true
        assertFalse(policy.shouldRefresh(13_000)) // Enabling alone sends nothing.
        policy.readSent(14_000)
        assertTrue(policy.readReceived())
        assertTrue(policy.shouldRefresh(15_150))
    }

    @Test
    fun connectionResetRetainsUserPreferenceAndAllowsInitialAndManualStatus() {
        val policy = StatusRefreshPolicy()
        assertTrue(policy.refreshAfterReads)
        policy.refreshAfterReads = false
        policy.reset()
        assertFalse(policy.refreshAfterReads)
        assertTrue(policy.shouldRefresh(0))
        policy.requested(0)
        policy.requested(10_000) // Manual refresh is independent of the preference.
        assertFalse(policy.shouldRefresh(39_999))
        assertTrue(policy.shouldRefresh(40_000))
    }

    @Test
    fun autoRefreshOffKeepsInitialQueryButStopsPeriodicAttempts() {
        val policy = StatusRefreshPolicy()
        assertTrue(policy.autoRefreshEnabled)
        policy.autoRefreshEnabled = false
        assertTrue(policy.shouldRefresh(0))
        policy.requested(0)
        assertFalse(policy.shouldRefresh(30_000))
        assertFalse(policy.shouldRefresh(120_000))
        policy.requested(120_000) // Manual refresh remains independent of the automatic policy.
        assertFalse(policy.shouldRefresh(150_000))
    }

    @Test
    fun allFourToggleCombinationsAreIndependent() {
        for (periodic in listOf(false, true)) for (afterReads in listOf(false, true)) {
            val policy = StatusRefreshPolicy()
            policy.autoRefreshEnabled = periodic
            policy.refreshAfterReads = afterReads
            policy.requested(0)
            policy.received(0)
            policy.readSent(10_000)
            assertEquals(afterReads, policy.readReceived())
            assertEquals(afterReads, policy.shouldRefresh(11_150))
            if (afterReads) policy.requested(11_150)
            assertEquals(periodic, policy.shouldRefresh(41_150))
        }
    }

    @Test
    fun reenablePeriodicRefreshDoesNotAccumulateMissedQueries() {
        val policy = StatusRefreshPolicy()
        policy.requested(0)
        policy.autoRefreshEnabled = false
        assertFalse(policy.shouldRefresh(300_000))
        policy.autoRefreshEnabled = true
        assertTrue(policy.shouldRefresh(300_000))
        policy.requested(300_000)
        assertFalse(policy.shouldRefresh(300_001))
        assertFalse(policy.shouldRefresh(329_999))
        assertTrue(policy.shouldRefresh(330_000))
    }

    @Test
    fun resetRetainsBothPreferencesAndRestoresOnlyInitialQuery() {
        val policy = StatusRefreshPolicy()
        policy.autoRefreshEnabled = false
        policy.refreshAfterReads = false
        policy.requested(0)
        policy.reset()
        assertFalse(policy.autoRefreshEnabled)
        assertFalse(policy.refreshAfterReads)
        assertTrue(policy.shouldRefresh(10_000))
        policy.requested(10_000)
        assertFalse(policy.shouldRefresh(40_000))
    }

    @Test
    fun periodicOffStillHonoursReadWaitFreshnessAndOtaPause() {
        val policy = StatusRefreshPolicy()
        policy.autoRefreshEnabled = false
        policy.requested(0)
        policy.received(0)
        policy.readSent(2_000)
        assertFalse(policy.shouldRefresh(2_500))
        assertTrue(policy.readReceived())
        assertFalse(policy.shouldRefresh(3_150)) // Fresh status suppresses the after-read query.
        policy.readSent(10_000)
        assertTrue(policy.readReceived())
        policy.otaStarted()
        assertFalse(policy.shouldRefresh(30_000))
        policy.otaMessage("OTA=STATE=0;PCT=0")
        assertFalse(policy.shouldRefresh(31_000))
    }

    @Test
    fun sensorRefreshHonoursAllFourToggleCombinations() {
        for (periodic in listOf(false, true)) for (afterReads in listOf(false, true)) {
            val policy = StatusRefreshPolicy()
            policy.autoRefreshEnabled = periodic
            policy.refreshAfterReads = afterReads
            policy.requested(0)
            policy.received(0)
            assertEquals(afterReads, policy.readReceived())
            assertEquals(afterReads, policy.shouldRefresh(10_150))
            if (afterReads) policy.requested(10_150)
            assertEquals(periodic, policy.shouldRefresh(40_150))
        }
    }

    @Test
    fun aStatusReplyCannotEraseAnEventReceivedAfterItsRequest() {
        val policy = StatusRefreshPolicy()
        policy.autoRefreshEnabled = false
        policy.requested(1_000)
        policy.readReceived()
        policy.received(1_200)
        assertFalse(policy.shouldRefresh(6_199))
        assertTrue(policy.shouldRefresh(6_200))
        policy.requested(6_200)
        assertFalse(policy.shouldRefresh(6_201))
    }

    @Test
    fun continuousSensorTrafficIsCoalescedWithoutStarvation() {
        val policy = StatusRefreshPolicy()
        policy.autoRefreshEnabled = false
        policy.requested(0)
        policy.received(0)
        val attempts = mutableListOf<Long>()
        for (now in 500L..20_000L step 500L) {
            policy.readReceived()
            if (policy.shouldRefresh(now)) {
                attempts += now
                policy.requested(now)
                policy.received(now)
            }
        }
        assertEquals(listOf(5_000L, 10_000L, 15_000L, 20_000L), attempts)
    }

    @Test
    fun missingStatusRepliesDoNotCauseAQueryForEverySensorFrame() {
        val policy = StatusRefreshPolicy()
        policy.autoRefreshEnabled = false
        policy.requested(0)
        policy.readReceived()
        assertFalse(policy.shouldRefresh(4_999))
        assertTrue(policy.shouldRefresh(5_000))
        policy.requested(5_000)
        policy.readReceived()
        assertFalse(policy.shouldRefresh(9_999))
        assertTrue(policy.shouldRefresh(10_000))
    }

    @Test
    fun otaIgnoresNewSensorTriggersAndResetDiscardsOldOnes() {
        val policy = StatusRefreshPolicy()
        policy.autoRefreshEnabled = false
        policy.requested(0)
        policy.otaStarted()
        assertFalse(policy.readReceived())
        policy.otaMessage("OTA=STATE=0;PCT=0")
        assertFalse(policy.shouldRefresh(11_000))
        assertTrue(policy.readReceived())
        policy.reset()
        policy.requested(13_000)
        assertFalse(policy.shouldRefresh(30_000))
    }

    @Test
    fun incompleteInvalidAndDiagnosticMessagesDoNotTriggerAfterReads() {
        val policy = StatusRefreshPolicy()
        policy.autoRefreshEnabled = false
        policy.requested(0)
        val decoder = PlcStreamDecoder()
        var readings = PlcReadingsState()
        fun receive(bytes: ByteArray) {
            for (message in decoder.feed(bytes)) {
                if (message.isFrame) {
                    readings = readings.receive(message, 10_000)
                    if (!readings.invalidFrame) policy.readReceived()
                }
            }
        }
        receive("EVT=42\r\n\u0006STATUS=1;FW=TEST\r\n\u0006COUNTERS=1;BOOT=0\r\n".toByteArray())
        assertFalse(policy.shouldRefresh(10_000))
        receive("\u0006broken\u0003".toByteArray())
        assertFalse(policy.shouldRefresh(10_000))
        val frame =
            ("\u00062026-11-03T12:00:00\u0002" +
                    listOf("TRUCK-1", "4647", "01.25", "1", "PAL", "LOC", "")
                        .joinToString("\u001b") +
                    "\u0003")
                .toByteArray()
        receive(frame.copyOfRange(0, frame.lastIndex))
        assertFalse(policy.shouldRefresh(10_000))
        receive(frame.copyOfRange(frame.lastIndex, frame.size))
        assertTrue(policy.shouldRefresh(10_150))
        policy.requested(10_150)
        assertFalse(policy.shouldRefresh(10_151))
    }
}
