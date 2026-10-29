package com.example.idautble

import org.junit.Assert.*
import org.junit.Test

class NetParsingTest {
    @Test
    fun maskedPasswordIsOmittedWhenSavingUnchangedConfiguration() {
        val config =
            parseNetConfigLine(
                "<ACK>NET=MODE=DHCP;SSID=Test;PWD=***;IP=192.168.1.10;GW=192.168.1.1;MASK=255.255.255.0;HOST=192.168.1.2;PORT=9000"
            )!!
        assertEquals("***", config.pwd)
        assertFalse(buildNetSetCommand(config).contains("PWD="))
    }

    @Test
    fun changedAndEmptyPasswordsAreSentExplicitly() {
        val config =
            NetConfig(
                "DHCP",
                "Test",
                "new=secret",
                "192.168.1.10",
                "192.168.1.1",
                "255.255.255.0",
                "192.168.1.2",
                9000,
            )
        assertTrue(buildNetSetCommand(config).contains("PWD=new=secret;"))
        assertTrue(buildNetSetCommand(config.copy(pwd = "")).contains("PWD=;"))
    }

    @Test
    fun passwordWithEqualsSignsIsParsedWithoutTruncation() {
        assertEquals("x=y", parseNetConfigLine("NET=MODE=DHCP;PWD=x=y;PORT=9000")!!.pwd)
    }

    @Test
    fun independentLinkAndTcpStatesAreParsed() {
        val status =
            parseNetStatusLine("<ACK>NETSTAT=WIFI=1;LINK=UP;TCP=DOWN;IP=192.168.1.10;RSSI=-62")!!
        assertTrue(status.wifiEnabled)
        assertTrue(status.linkUp)
        assertFalse(status.tcpUp)
    }

    @Test
    fun configSurvivesStatusInSameBurst() {
        val state =
            NetworkSnapshot()
                .receive("NET=MODE=DHCP;SSID=Test;PWD=***;PORT=9000")
                .receive("NETSTAT=WIFI=1;LINK=UP;TCP=DOWN")
        assertEquals("Test", state.config!!.ssid)
        assertTrue(state.status!!.linkUp)
        assertEquals(1L, state.configRevision)
        assertEquals(1L, state.statusRevision)
    }

    @Test
    fun identicalReadStillHasNewRevisionToRefreshEditedFields() {
        val line = "NET=MODE=DHCP;SSID=Test;PWD=***;PORT=9000"
        val first = NetworkSnapshot().receive(line)
        val second = first.receive(line)
        assertEquals(first.config, second.config)
        assertEquals(first.configRevision + 1, second.configRevision)
    }
}
