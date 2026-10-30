package com.example.idautble

import org.junit.Assert.*
import org.junit.Test

class DeviceStatusTest {
    private val sample =
        "STATUS=1;FW=3.0.6-rc1;ID=TRUCK-1;LED=READY;WHY=LINK_READY;B1=PRESENT;B1V=18.00;B1P=66;B1AGE=0;B2=ABSENT;B2V=0.00;B2P=-;B2AGE=0;LOWEST=B1;WIFI=0;TCP=0;BLE=1;PHOTO=1;RTC=OK;OUT=OK;CUT=NO;REASON="

    @Test
    fun oneBatteryDoesNotLookLikeZeroPercent() {
        val s = parseDeviceStatus(sample)!!
        assertEquals("TRUCK-1", s.id)
        assertEquals(66, s.batteries[0].percent)
        assertEquals("ABSENT", s.batteries[1].state)
        assertNull(s.batteries[1].percent)
        assertEquals("B1", s.lowest)
        assertTrue(s.ble)
        assertFalse(s.wifi)
    }

    @Test
    fun invalidAndStaleMeasurementsHaveNoLiveValues() {
        val invalid =
            sample.replace("B1=PRESENT;B1V=18.00;B1P=66;B1AGE=0", "B1=INVALID;B1V=-;B1P=-;B1AGE=-")
        assertNull(parseDeviceStatus(invalid)!!.batteries[0].volts)
        val stale =
            invalid.replace("B1=INVALID;B1V=-;B1P=-;B1AGE=-", "B1=STALE;B1V=-;B1P=-;B1AGE=3001")
        assertEquals(3001L, parseDeviceStatus(stale)!!.batteries[0].ageMs)
        assertNull(parseDeviceStatus(stale.replace("B1V=-", "B1V=18.00")))
    }

    @Test
    fun rejectsPartialDuplicateAndUnsupportedSnapshots() {
        for (bad in
            listOf(
                sample.substringBefore(";OUT="),
                sample + ";ID=OTHER",
                sample.replace("STATUS=1", "STATUS=2"),
                sample.replace("B1P=66", "B1P=100"),
                sample.replace("B1V=18.00", "B1V=NaN"),
                sample.replace("B1AGE=0", "B1AGE=-1"),
                sample.replace("B1AGE=0", "B1AGE=3001"),
                sample.replace("BLE=1", "BLE=2"),
                sample.replace("ID=TRUCK-1", "ID=BAD=ID"),
                sample.replace("B2P=-", "B2P=0"),
                sample.replace("PHOTO=1", "PHOTO=2"),
            )) assertNull(bad, parseDeviceStatus(bad))
    }

    @Test
    fun acceptsFutureFieldsWithoutGuessingFutureSchema() {
        assertNotNull(parseDeviceStatus(sample + ";EXTRA=42"))
        assertNotNull(parseDeviceStatus(sample.replace("WHY=LINK_READY", "WHY=NEW_REASON")))
        assertEquals("NEW_REASON", ledReasonDescription("NEW_REASON"))
    }

    @Test
    fun batteryAt15PercentAndPowerCutRemainDistinct() {
        val low =
            parseDeviceStatus(
                sample
                    .replace("LED=READY;WHY=LINK_READY", "LED=BAT_LOW;WHY=BATTERY_LOW")
                    .replace("B1P=66", "B1P=15")
            )!!
        assertEquals("BAT_LOW", low.led)
        assertTrue(ledDescription(low.led).contains("2 flashes"))
        val cut =
            parseDeviceStatus(
                sample
                    .replace("LED=READY;WHY=LINK_READY", "LED=ERROR;WHY=POWER_CUT")
                    .replace("CUT=NO;REASON=", "CUT=PENDING;REASON=BAT_VOLTAGE_LOW")
            )!!
        assertEquals("BAT_VOLTAGE_LOW", cut.cutReason)
        assertEquals("PENDING", cut.cut)
    }

    @Test
    fun ageUsesMonotonicReceiptTime() {
        assertNull(deviceStatusAge(null, 100))
        assertEquals(0L, deviceStatusAge(100, 99))
        assertEquals(15L, deviceStatusAge(100, 15100))
        assertEquals(16L, deviceStatusAge(100, 16100))
    }

    @Test
    fun fragmentedStatusAndBusinessFrameStaySeparate() {
        val decoder = PlcStreamDecoder()
        val wire = ("\u0006" + sample + "\r\n").toByteArray()
        val messages = wire.flatMap { decoder.feed(byteArrayOf(it)) }
        assertEquals(1, messages.size)
        assertTrue(messages.single().acknowledged)
        assertNotNull(parseDeviceStatus(messages.single().text))
        val event =
            "\u0006" +
                "2026-10-30T12:00:00\u0002TRUCK-1\u001b6600\u001b-\u001b1\u001bPALLET\u001b-\u001b\u0003"
        val mixed = decoder.feed(event.toByteArray() + wire)
        assertEquals(2, mixed.size)
        assertTrue(mixed[0].isFrame)
        assertFalse(mixed[1].isFrame)
        assertNull(parseDeviceStatus(mixed[0].text))
        assertNotNull(parseDeviceStatus(mixed[1].text))
    }

    @Test
    fun statusIsReadOnlyAndDoesNotNeedMaintenancePin() {
        assertFalse(requiresMaintenanceAuthorization("STATUS?\r\n".toByteArray()))
    }
}
