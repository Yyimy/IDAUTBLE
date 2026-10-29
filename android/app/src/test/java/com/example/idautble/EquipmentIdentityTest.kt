package com.example.idautble

import org.junit.Assert.*
import org.junit.Test

class EquipmentIdentityTest {
    @Test
    fun acceptsPlatesAndIndustrialIdentifiers() {
        for (id in
            listOf("A", "CAR-023", "E 1234 BCD", "TRUCK_01", "12345678901234567890")) assertTrue(
            id,
            validEquipmentId(id),
        )
        assertEquals("E 1234 BCD", normalizedEquipmentId(" e 1234 bcd "))
    }

    @Test
    fun rejectsFrameDelimitersUnicodeAndInvalidLengths() {
        for (id in
            listOf(
                "",
                "123456789012345678901",
                " A",
                "A ",
                "car-1",
                "CAR;01",
                "A=B",
                "A\u001bB",
                "A\rB",
                "A\nB",
                "CAMIÓN",
                "A/B",
            )) assertFalse(id, validEquipmentId(id))
    }

    @Test
    fun onlyWritesRequireMaintenanceAuthorization() {
        assertTrue(requiresMaintenanceAuthorization("ID=CAR-023\r\n".toByteArray()))
        assertFalse(requiresMaintenanceAuthorization("ID?\r\n".toByteArray()))
    }

    @Test
    fun parsesConfirmedIdentityAndLegacyDefaultName() {
        assertEquals(
            EquipmentIdentity("E 1234 BCD", "AABBCCDDEEFF", "E 1234 BCD"),
            parseEquipmentIdentity("ID=E 1234 BCD;HWID=AABBCCDDEEFF;NAME=E 1234 BCD"),
        )
        assertEquals(
            "ISP-AABBCCDDEEFF",
            parseEquipmentIdentity("ID=AABBCCDDEEFF;HWID=AABBCCDDEEFF;NAME=ISP-AABBCCDDEEFF")
                ?.bluetoothName,
        )
    }

    @Test
    fun doesNotTreatIncompleteOrMismatchedResponseAsSaved() {
        for (line in
            listOf(
                "ID=CAR-023",
                "ID=CAR-023;HWID=BAD;NAME=CAR-023",
                "ID=CAR-023;HWID=AABBCCDDEEFF;NAME=OTHER",
                "ID=CAR-023;ID=OTHER;HWID=AABBCCDDEEFF;NAME=OTHER",
            )) assertNull(parseEquipmentIdentity(line))
    }

    @Test
    fun customIdFrameSurvivesEveryNotificationSplit() {
        val frame =
            "\u00062026-10-29T14:00:00\u0002E 1234 BCD\u001B6598\u001B01.25\u001B1\u001BPALET\u001BUBIC\u001B\u0003"
                .toByteArray()
        for (split in 0..frame.size) {
            val decoder = PlcStreamDecoder()
            val messages =
                decoder.feed(frame.copyOfRange(0, split)) +
                    decoder.feed(frame.copyOfRange(split, frame.size))
            assertEquals(1, messages.size)
            assertTrue(messages.single().isFrame)
            assertArrayEquals(frame, messages.single().bytes)
        }
    }

    @Test
    fun identityConfirmationSurvivesFragmentation() {
        val line = "\u0006ID=CAR-023;HWID=AABBCCDDEEFF;NAME=CAR-023\r\n".toByteArray()
        for (chunkSize in listOf(1, 20, 182)) {
            val decoder = PlcStreamDecoder()
            val messages =
                line.asList().chunked(chunkSize).flatMap { decoder.feed(it.toByteArray()) }
            assertEquals(1, messages.size)
            assertEquals("CAR-023", parseEquipmentIdentity(messages.single().text)?.id)
        }
    }
}
