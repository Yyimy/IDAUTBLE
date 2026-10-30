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
        assertTrue(policy.readReceived(32_000))
        assertTrue(policy.shouldRefresh(32_150))
        policy.requested(32_150)
        assertFalse(policy.shouldRefresh(33_000))
    }

    @Test
    fun recentSuccessfulStatusSuppressesExtraReadRefresh() {
        val policy = StatusRefreshPolicy()
        policy.requested(0)
        policy.received(1_000)
        policy.readSent(2_000)
        assertTrue(policy.readReceived(3_000))
        assertFalse(policy.shouldRefresh(3_150))
        assertFalse(policy.shouldRefresh(6_000)) // Skipped refresh does not linger.
        policy.readSent(6_000)
        assertTrue(policy.readReceived(6_100))
        assertTrue(policy.shouldRefresh(6_250))
    }

    @Test
    fun spontaneousFramesDoNotTriggerStatusRequests() {
        val policy = StatusRefreshPolicy()
        policy.requested(0)
        assertFalse(policy.readReceived(10_000))
        assertFalse(policy.shouldRefresh(10_000))
    }

    @Test
    fun missingReadResponseExpiresWithoutBlockingPeriodicRefresh() {
        val policy = StatusRefreshPolicy()
        policy.requested(0)
        policy.readSent(30_000)
        assertTrue(policy.readPending(37_999))
        assertFalse(policy.readPending(38_000))
        assertFalse(policy.readReceived(38_001))
        assertTrue(policy.shouldRefresh(38_001))
    }

    @Test
    fun transportBusyCanDeferRefreshAfterReadWithoutLosingIt() {
        val policy = StatusRefreshPolicy()
        policy.requested(0)
        policy.readSent(10_000)
        assertTrue(policy.readReceived(11_000))
        // Activity does not evaluate policy until transport and OTA/foreground gates allow it.
        assertTrue(policy.shouldRefresh(12_000))
    }

    @Test
    fun resetAndOtaCancellationDiscardPendingReading() {
        val policy = StatusRefreshPolicy()
        policy.requested(0)
        policy.readSent(10_000)
        policy.cancelReading()
        assertFalse(policy.readReceived(11_000))
        assertFalse(policy.shouldRefresh(11_000))
        policy.readSent(12_000)
        policy.reset()
        assertFalse(policy.readReceived(13_000))
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
}
