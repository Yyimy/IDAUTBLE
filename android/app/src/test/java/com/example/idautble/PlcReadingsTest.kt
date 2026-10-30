package com.example.idautble

import org.junit.Assert.*
import org.junit.Test

class PlcReadingsTest {
    private fun frame(
        id: String = "TRUCK-1",
        battery: String = "6598",
        height: String = "01.25",
        photo: String = "1",
        pallet: String = "RC12345",
        location: String = "RACK-A2",
        time: String = "2026-10-30T12:34:56",
    ): PlcMessage =
        PlcMessage(
            ("\u0006" +
                    time +
                    "\u0002" +
                    listOf(id, battery, height, photo, pallet, location, "")
                        .joinToString("\u001b") +
                    "\u0003")
                .toByteArray(),
            true,
        )

    @Test
    fun fullReadingSupportsCustomIdsAndExactCodes() {
        val r =
            parsePlcReading(
                frame(id = "E 1234 BCD", pallet = "RC Mixed-case 123", location = "A-01_A2")
            )!!
        assertEquals("E 1234 BCD", r.deviceId)
        assertEquals("RC Mixed-case 123", r.pallet)
        assertEquals("A-01_A2", r.location)
        assertEquals("01.25", r.heightMetres)
        assertEquals(true, r.photocell)
        assertEquals(65, r.battery1)
        assertEquals(98, r.battery2)
        assertTrue(r.clockValid)
    }

    @Test
    fun partialCommandClearsEarlierCodesAndMeasurements() {
        val state = PlcReadingsState().receive(frame(), 1000)
        val batteryOnly =
            state.receive(
                frame(battery = "1500", height = "-", photo = "-", pallet = "-", location = "-"),
                2000,
            )
        val r = batteryOnly.reading!!
        assertNull(r.pallet)
        assertNull(r.location)
        assertNull(r.heightMetres)
        assertNull(r.photocell)
        assertEquals(15, r.battery1)
        assertEquals(0, r.battery2)
        assertEquals(2000L, batteryOnly.receivedMs)
        val missing =
            batteryOnly
                .receive(
                    frame(
                        id = "A",
                        battery = "-",
                        height = "-",
                        photo = "0",
                        pallet = "-",
                        location = "-",
                    ),
                    3000,
                )
                .reading!!
        assertNull(missing.battery1)
        assertNull(missing.battery2)
        assertEquals(false, missing.photocell)
    }

    @Test
    fun allUnprovidedFieldsAndShortIdsAreValid() {
        val r =
            parsePlcReading(
                frame(
                    id = "A",
                    battery = "-",
                    height = "-",
                    photo = "-",
                    pallet = "-",
                    location = "-",
                )
            )!!
        assertNull(r.pallet)
        assertNull(r.location)
        assertNull(r.battery1)
        assertNull(r.heightMetres)
        assertNotNull(parsePlcReading(frame(id = "12345678901234567890")))
        assertNotNull(parsePlcReading(frame(pallet = "", location = "")))
    }

    @Test
    fun rejectsMalformedFramesWithoutReplacingGoodData() {
        val good = PlcReadingsState().receive(frame(), 1000)
        val bad =
            listOf(
                frame(battery = "65XX"),
                frame(battery = "10000"),
                frame(height = "NaN"),
                frame(height = "1.25"),
                frame(photo = "2"),
                frame(id = "bad;id"),
                frame(pallet = "CODE\rX"),
                frame(location = "A\u001bB"),
                frame(time = "not-a-timestamp-xxxx"),
                PlcMessage(byteArrayOf(6, 2, 3), true),
                PlcMessage(frame().bytes.dropLast(1).toByteArray(), true),
                PlcMessage(frame().bytes.dropLast(2).toByteArray() + byteArrayOf(3), true),
                PlcMessage(frame().bytes + byteArrayOf(3), true),
                frame(pallet = "A".repeat(600)),
            )
        for (message in bad) {
            assertNull(parsePlcReading(message))
            val retained = good.receive(message, 2000)
            assertEquals(good.reading, retained.reading)
            assertEquals(1000L, retained.receivedMs)
            assertTrue(retained.invalidFrame)
        }
        assertFalse(good.copy(invalidFrame = true).receive(frame(), 3000).invalidFrame)
    }

    @Test
    fun invalidRtcDoesNotLoseOtherwiseValidReadings() {
        val r = parsePlcReading(frame(time = "0000-00-00T00:00:00"))!!
        assertFalse(r.clockValid)
        assertEquals("RC12345", r.pallet)
        assertEquals("0000-00-00T00:00:00", r.plcTime)
    }

    @Test
    fun linesAndControlAcksDoNotOverwriteReadings() {
        val state = PlcReadingsState().receive(frame(), 1000)
        for (text in
            listOf("\u0006CONECTADO\r\n", "\u0006STATUS=1;ID=TRUCK-1\r\n", "\u0015BUSY\r\n")) {
            val message = PlcMessage(text.toByteArray(), false)
            assertNull(parsePlcReading(message))
            assertEquals(state, state.receive(message, 2000))
        }
        assertNull(PlcReadingsState().reading)
        assertNull(PlcReadingsState().receivedMs)
    }

    @Test
    fun fragmentedAndCoalescedFramesAreHandledIndependently() {
        val wire = frame(pallet = "RC-ÉTIQUETTE").bytes
        for (size in listOf(1, 2, 7, 20, 182)) {
            val decoder = PlcStreamDecoder()
            val messages = wire.toList().chunked(size).flatMap { decoder.feed(it.toByteArray()) }
            assertEquals(1, messages.size)
            assertEquals("RC-ÉTIQUETTE", parsePlcReading(messages.single())!!.pallet)
        }
        val decoder = PlcStreamDecoder()
        val messages =
            decoder.feed(
                frame().bytes +
                    "\u0006STATUS=1\r\n".toByteArray() +
                    frame(pallet = "-", location = "-").bytes
            )
        val state = messages.fold(PlcReadingsState()) { s, m -> s.receive(m, 2000) }
        assertEquals(3, messages.size)
        assertNull(state.reading!!.pallet)
        assertNull(state.reading!!.location)
    }

    @Test
    fun invalidUtf8AndNegativeRepliesCannotBecomeReadings() {
        val bytes = frame(pallet = "X").bytes
        val index = bytes.indexOf('X'.code.toByte())
        bytes[index] = 0xff.toByte()
        assertNull(parsePlcReading(PlcMessage(bytes, true)))
        val rejected = frame().bytes
        rejected[0] = 0x15
        assertNull(parsePlcReading(PlcMessage(rejected, true)))
    }
}
