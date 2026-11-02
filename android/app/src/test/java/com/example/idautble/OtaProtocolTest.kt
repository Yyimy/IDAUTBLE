package com.example.idautble

import org.junit.Assert.*
import org.junit.Test

class OtaProtocolTest {
    @Test
    fun factorySsid() {
        assertEquals(
            "PLC_OTA_AABBCCDDEEFF",
            parseOtaApSsid("AP_ON SSID=PLC_OTA_AABBCCDDEEFF IP=192.168.4.1"),
        )
    }

    @Test
    fun customSsid() {
        assertEquals("PLC_OTA_777", parseOtaApSsid("AP_ON SSID=PLC_OTA_777 IP=192.168.4.1"))
    }

    @Test
    fun equipmentIdWithSpacesIsNotTruncated() {
        assertEquals("PLC_OTA_FORK 7", parseOtaApSsid("AP_ON SSID=PLC_OTA_FORK 7 IP=192.168.4.1"))
    }

    @Test
    fun maximumEquipmentId() {
        val ssid = "PLC_OTA_" + "A".repeat(20)
        assertEquals(ssid, parseOtaApSsid("AP_ON SSID=$ssid IP=192.168.4.1"))
    }

    @Test
    fun unrelatedOrIncompleteMessagesAreIgnored() {
        for (line in
            listOf(
                "WAIT_CLIENT",
                "AP_ON SSID=PLC_OTA_777",
                "SSID=PLC_OTA_777 IP=192.168.4.1",
                "AP_ON SSID= IP=192.168.4.1",
            )) {
            assertNull(parseOtaApSsid(line))
        }
    }

    @Test
    fun invalidSsidIsNotDisplayed() {
        assertNull(parseOtaApSsid("AP_ON SSID=" + "A".repeat(33) + " IP=192.168.4.1"))
        assertNull(parseOtaApSsid("AP_ON SSID=PLC_OTA_A\u0000B IP=192.168.4.1"))
    }
}
