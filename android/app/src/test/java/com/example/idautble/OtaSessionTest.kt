package com.example.idautble

import java.net.HttpURLConnection
import java.net.Proxy
import java.net.ServerSocket
import java.net.URL
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class OtaSessionTest {
    private val address = "AA:BB:CC:DD:EE:FF"
    private val file = OtaFirmware("content://test/A", "PLC_A.bin", 4096, "a".repeat(64))

    private class FakeServer : OtaServer {
        var starts = 0
        var stops = 0

        override fun startServing() {
            starts++
        }

        override fun stopServing() {
            stops++
        }
    }

    private fun prepared(server: OtaServer = FakeServer()): OtaSession =
        OtaSession { server }.also { it.finishSelection(it.beginSelection(file.name)!!, file) }

    @Test
    fun selectingAnotherFileCannotReplaceAnActiveServerSnapshot() {
        var served: OtaFirmware? = null
        val session = OtaSession { selected ->
            served = selected
            FakeServer()
        }
        session.finishSelection(session.beginSelection(file.name)!!, file)
        session.startServer()
        assertNull(session.beginSelection("B.bin"))
        session.startSent(address, "A")
        session.stopServer()
        assertTrue(session.serverRunning)
        assertEquals(file, served)
        assertNull(session.beginSelection("B.bin"))
    }

    @Test
    fun delayedHashCannotReplaceANewerSelection() {
        val session = OtaSession { FakeServer() }
        val old = session.beginSelection("A.bin")!!
        val current = session.beginSelection("B.bin")!!
        session.finishSelection(current, file.copy(name = "B.bin", uri = "content://test/B"))
        session.finishSelection(old, file)
        assertEquals("B.bin", session.fileName)
        assertEquals("content://test/B", session.firmware?.uri)
    }

    @Test
    fun unreadableOrEmptyFilesCannotStartServer() {
        val session = prepared()
        session.finishSelection(session.beginSelection("Empty.bin")!!, file.copy(size = 0))
        session.startServer()
        assertFalse(session.serverRunning)
        assertNotNull(session.localError)
        session.finishSelection(session.beginSelection("Gone.bin")!!, null)
        assertFalse(session.calculating)
        assertNull(session.firmware)
    }

    @Test
    fun serverFailureCleansUpAndCanBeRetried() {
        val server = FakeServer()
        var fail = true
        val session = OtaSession {
            if (fail) throw IllegalStateException("bind")
            server
        }
        session.finishSelection(session.beginSelection(file.name)!!, file)
        session.startServer()
        assertFalse(session.serverRunning)
        assertNotNull(session.localError)
        fail = false
        session.startServer()
        assertTrue(session.serverRunning)
        session.startServer()
        assertEquals(1, server.starts)
        assertNull(session.localError)
        session.close()
    }

    @Test
    fun startFailureStopsThePartiallyStartedServer() {
        var stopped = false
        val session =
            prepared(
                object : OtaServer {
                    override fun startServing() {
                        throw IllegalStateException("bind")
                    }

                    override fun stopServing() {
                        stopped = true
                    }
                }
            )
        session.startServer()
        assertTrue(stopped)
        assertFalse(session.serverRunning)
    }

    @Test
    fun oldStatusAndWrongDeviceCannotConfirmRestart() {
        val session = prepared()
        session.startSent(address, "A")
        assertFalse(session.reportedVersion(address, "B"))
        session.doneReceived()
        assertFalse(session.ready(address))
        session.disconnected("OTHER")
        assertFalse(session.ready(address))
        session.disconnected(address)
        assertFalse(session.ready("OTHER"))
        assertTrue(session.ready(address))
        assertFalse(session.reportedVersion("OTHER", "B"))
        assertFalse(session.reportedVersion(address, " "))
        assertTrue(session.reportedVersion(address, "B"))
        assertEquals("A", session.versionBefore)
        assertEquals("B", session.versionAfter)
    }

    @Test
    fun doneAloneDoesNotClaimRunningFirmwareWasVerified() {
        val session = prepared()
        session.startSent(address, "A")
        session.doneReceived()
        assertEquals(OtaConfirmation.WAITING_REBOOT, session.confirmation)
        assertNull(session.versionAfter)
        session.confirmationTimedOut()
        assertEquals(OtaConfirmation.UNCONFIRMED, session.confirmation)
        assertTrue(session.confirmationText!!.contains("not confirmed"))
    }

    @Test
    fun manualReconnectAfterTimeoutCanStillVerifyVersion() {
        val session = prepared()
        session.startSent(address, "A")
        session.doneReceived()
        session.disconnected(address)
        session.confirmationTimedOut()
        assertFalse(session.reportedVersion(address, "B"))
        assertTrue(session.ready(address))
        assertTrue(session.reportedVersion(address, "B"))
        assertEquals(OtaConfirmation.CONFIRMED, session.confirmation)
    }

    @Test
    fun repeatedUpdatesIgnoreTimeoutsFromAnEarlierAttempt() {
        val session = prepared()
        for ((before, after) in listOf("A" to "B", "B" to "A", "A" to "A")) {
            val old = session.attempt
            session.startSent(address, before)
            session.doneReceived()
            session.disconnected(address)
            session.ready(address)
            session.confirmationTimedOut(old)
            assertEquals(OtaConfirmation.WAITING_VERSION, session.confirmation)
            assertTrue(session.reportedVersion(address, after))
            assertEquals(after, session.versionAfter)
        }
    }

    @Test
    fun delayedRebootCanBeVerifiedAfterTheAutomaticWaitExpires() {
        val session = prepared()
        session.startSent(address, "A")
        session.doneReceived()
        session.confirmationTimedOut()
        session.disconnected(address)
        assertTrue(session.ready(address))
        assertTrue(session.reportedVersion(address, "B"))
    }

    @Test
    fun disconnectionWithoutDoneCannotConfirmAnUpdate() {
        val session = prepared()
        session.startSent(address, "A")
        session.disconnected(address)
        assertFalse(session.ready(address))
        assertFalse(session.reportedVersion(address, "B"))
    }

    @Test
    fun losingTheNewConnectionRequiresAnotherHandshakeBeforeVerification() {
        val session = prepared()
        session.startSent(address, "A")
        session.doneReceived()
        session.disconnected(address)
        session.ready(address)
        session.disconnected(address)
        session.confirmationTimedOut()
        assertFalse(session.reportedVersion(address, "B"))
        session.ready(address)
        assertTrue(session.reportedVersion(address, "B"))
    }

    @Test
    fun cleanupAfterRestartIsIndependentOfTheOtaTab() {
        val server = FakeServer()
        val session = prepared(server)
        session.startServer()
        session.startSent(address, "A")
        session.doneReceived()
        session.disconnected(address)
        assertFalse(session.serverRunning)
        assertEquals(1, server.stops)
        session.ready(address)
        session.reportedVersion(address, "B")
        session.close()
        assertEquals(1, server.stops)
        assertEquals(file, session.firmware)
    }

    @Test
    fun abortAndTerminalErrorReleaseServerButRetainFileForRetry() {
        val session = prepared()
        session.startServer()
        session.startSent(address, "A")
        session.aborted()
        assertFalse(session.busy)
        assertFalse(session.serverRunning)
        assertEquals(file, session.firmware)
        session.startServer()
        session.startSent(address, "A")
        session.failed("NO_CLIENT")
        assertFalse(session.serverRunning)
        assertFalse(session.busy)
        assertEquals(OtaConfirmation.FAILED, session.confirmation)
    }

    @Test
    fun nonTerminalProtocolErrorsMustNotCutOffAnExistingTransfer() {
        val session = prepared()
        session.startServer()
        session.startSent(address, "A")
        for (error in listOf("BUSY", "BAD_PARAMS")) {
            session.failed(error)
            assertTrue(session.serverRunning)
            assertTrue(session.busy)
        }
        session.close()
    }

    @Test
    fun destroyReleasesServerAndIgnoresLateFileRead() {
        val server = FakeServer()
        val session = prepared(server)
        session.startServer()
        session.close()
        session.close()
        assertEquals(1, server.stops)
        val other = OtaSession { FakeServer() }
        val ticket = other.beginSelection("A")!!
        other.close()
        other.finishSelection(ticket, file)
        assertNull(other.firmware)
    }

    @Test
    fun realHttpTransferContinuesWhenSessionIsReusedByAnotherView() {
        val bytes = ByteArray(256 * 1024) { (it % 251).toByte() }
        val port = ServerSocket(0).use { it.localPort }
        var created = 0
        val hash =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            }
        val selected = file.copy(size = bytes.size.toLong(), sha256 = hash)
        val session = OtaSession { snapshot ->
            created++
            HttpFileServer(port, snapshot.size) { bytes.inputStream() }
        }
        session.finishSelection(session.beginSelection(selected.name)!!, selected)
        session.startServer()
        try {
            session.startSent(address, "A")
            val connection =
                URL("http://127.0.0.1:$port/firmware.bin").openConnection(Proxy.NO_PROXY)
                    as HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            try {
                connection.inputStream.use { input ->
                    val first = input.readNBytes(8192)
                    // A new screen reads the same Activity-owned session; it has no server
                    // lifecycle.
                    val reattached = session
                    assertEquals(selected, reattached.firmware)
                    reattached.startServer() // Cannot create another listener, even if requested.
                    reattached.stopServer() // Cannot interrupt an active transfer.
                    assertTrue(reattached.serverRunning)
                    assertArrayEquals(bytes, first + input.readBytes())
                    assertEquals(1, created)
                }
            } finally {
                connection.disconnect()
            }
        } finally {
            session.close()
        }
    }
}
