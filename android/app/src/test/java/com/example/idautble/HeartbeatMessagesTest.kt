package com.example.idautble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class HeartbeatMessagesTest {
    @Test
    fun customMessagesRoundTripFromConfigurationAndSaveReply() {
        val settings = TcpAdvancedConfig(true, false, 10, 20, HeartbeatMessages("1,;", "0"))
        assertEquals("TCPADV=1,0,10,20,312C3B,30", settings.command())
        assertEquals(settings, parseTcpAdvanced(settings.command()))
        assertEquals(settings, parseTcpAdvanced("NET=TCPADV=1,0,10,20;HBMSG=312C3B,30"))
    }

    @Test
    fun oldFirmwareAndProtocolDelimitersRemainDistinct() {
        assertEquals(TcpAdvancedConfig(true, false, 10, 20), parseTcpAdvanced("TCPADV=1,0,10,20"))
        assertNull(parseTcpAdvanced("TCPADV=1,0,10,20,31,0A"))
        assertFalse(HeartbeatMessages("1", "ACK=0").valid)
    }
}
