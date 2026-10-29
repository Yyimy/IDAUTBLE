package com.example.idautble

import org.junit.Assert.*
import org.junit.Test

class PlcProtocolTest {
    private val ready = "\u0006CONECTADO\r\n".toByteArray()
    private val auth = "\u0006AUTH=OK;TTL=300\r\n".toByteArray()
    private val busy = "\u0015BUSY\r\n".toByteArray()
    private val frame =
        "\u00062026-10-29T14:00:00\u0002AABBCCDDEEFF\u001B6598\u001B01.25\u001B1\u001BPALET\u001BUBIC\u001B\u0003"
            .toByteArray()

    @Test
    fun everyReadySplitProducesExactlyOneHandshake() {
        for (split in 0..ready.size) {
            val decoder = PlcStreamDecoder()
            val messages =
                decoder.feed(ready.copyOfRange(0, split)) +
                    decoder.feed(ready.copyOfRange(split, ready.size))
            assertEquals("split=$split", 1, messages.size)
            assertEquals("CONECTADO", messages.single().text)
            assertTrue(messages.single().acknowledged)
        }
    }

    @Test
    fun coalescedMessagesAndBytewiseChunksAreEquivalent() {
        val combined = ready + auth + frame + busy
        val expected = PlcStreamDecoder().feed(combined)
        val decoder = PlcStreamDecoder()
        val actual = combined.flatMap { decoder.feed(byteArrayOf(it)) }
        assertEquals(4, actual.size)
        actual.zip(expected).forEach { (a, b) -> assertArrayEquals(b.bytes, a.bytes) }
        assertTrue(actual[2].isFrame)
        assertTrue(actual[3].rejected)
    }

    @Test
    fun allFrameBoundariesAndMtuChunksPreserveBytes() {
        for (split in 0..frame.size) {
            val decoder = PlcStreamDecoder()
            val result =
                decoder.feed(frame.copyOfRange(0, split)) +
                    decoder.feed(frame.copyOfRange(split, frame.size))
            assertEquals(1, result.size)
            assertArrayEquals(frame, result.single().bytes)
        }
        for (chunkSize in listOf(1, 20, 182)) {
            val decoder = PlcStreamDecoder()
            val result =
                frame.asList().chunked(chunkSize).flatMap { decoder.feed(it.toByteArray()) }
            assertEquals(1, result.size)
            assertArrayEquals(frame, result.single().bytes)
        }
    }

    @Test
    fun incompleteFrameIsNotEmittedAndNewAckResynchronizes() {
        val decoder = PlcStreamDecoder()
        assertTrue(decoder.feed(frame.copyOfRange(0, 15)).isEmpty())
        assertEquals("CONECTADO", decoder.feed(ready).single().text)
        assertEquals(1, decoder.discardedBuffers)
    }

    @Test
    fun bufferIsBoundedAndDisconnectClearsPartialData() {
        val decoder = PlcStreamDecoder(32)
        assertTrue(decoder.feed(ByteArray(1000) { 'X'.code.toByte() }).isEmpty())
        assertTrue(decoder.discardedBuffers > 0)
        decoder.clear()
        assertEquals("AUTH=OK;TTL=300", decoder.feed(auth).single().text)
    }

    @Test
    fun unicodeIsDecodedOnlyWhenComplete() {
        val bytes = "\u0006DIAG=Ubicación\r\n".toByteArray(Charsets.UTF_8)
        val decoder = PlcStreamDecoder()
        val messages = bytes.flatMap { decoder.feed(byteArrayOf(it)) }
        assertEquals("DIAG=Ubicación", messages.single().text)
    }

    @Test
    fun protectionCoversTextAndBinaryOffButAllowsQueries() {
        listOf(
                "NET=MODE=DHCP",
                "RTC=2026-10-29T12:00:00",
                "CFG=PFXP=P",
                "WIFI=1",
                "ALT=0",
                "OTA=START",
                "OTA!",
                "ACKMODE=1",
                "HEARTBEAT=1",
            )
            .forEach { assertTrue(it, requiresMaintenanceAuthorization(it.toByteArray())) }
        listOf("NET?", "NETSTAT?", "CFG?", "ALT?", "WIFI?", "OTA?", "INFO?", "DIAG?").forEach {
            assertFalse(it, requiresMaintenanceAuthorization(it.toByteArray()))
        }
        assertTrue(requiresMaintenanceAuthorization(byteArrayOf(0x1B, 'O'.code.toByte(), 13, 10)))
        assertFalse(requiresMaintenanceAuthorization(byteArrayOf(0x1B, 'L'.code.toByte(), 13, 10)))
    }

    @Test
    fun leadingZeroPinsArePreservedAndOtherInputsRejected() {
        assertTrue(validMaintenancePin("001234"))
        assertTrue(validMaintenancePin("123456"))
        listOf("12345", "1234567", "12ab56", "１２３４５６", "123 45", "").forEach {
            assertFalse(validMaintenancePin(it))
        }
    }

    @Test
    fun authorizationExpiresAndDisconnectResetsIt() {
        var time = 1000L
        val session = MaintenanceSession { time }
        assertFalse(session.isAuthorized())
        session.authorize(300)
        assertTrue(session.isAuthorized())
        time += 294000
        assertTrue(session.isAuthorized())
        time += 2000
        assertFalse(session.isAuthorized())
        session.authorize(300)
        assertTrue(session.isAuthorized())
        session.reset()
        assertFalse(session.isAuthorized())
    }

    @Test
    fun logsDoNotRevealPinOrWifiPassword() {
        assertFalse(redactProtocolText("AUTH=001234\r\n").contains("001234"))
        assertFalse(redactProtocolText("NET=SSID=Test;PWD=secret;PORT=9000").contains("secret"))
        assertTrue(hasProtocolSecret("AUTH=001234".toByteArray()))
        assertFalse(hasProtocolSecret("NETSTAT?".toByteArray()))
    }

    @Test
    fun sessionAuthorizationHasNoTimeLimitAndCanBeRevoked() {
        var time = 1000L
        val session = MaintenanceSession { time }
        session.authorizeSession()
        time += 100L * 24 * 60 * 60 * 1000
        assertTrue(session.isAuthorized())
        session.reset()
        assertFalse(session.isAuthorized())
        session.authorizeSession()
        session.authorize(300)
        time += 301000
        assertFalse(session.isAuthorized())
    }

    @Test
    fun infoIsRequestedOncePerConnectionEvenWhenCIsRepeated() {
        val handshake = ConnectionHandshake()
        assertTrue(handshake.onConnected())
        repeat(10) { assertFalse(handshake.onConnected()) }
        handshake.reset()
        assertTrue(handshake.onConnected())
    }
}
