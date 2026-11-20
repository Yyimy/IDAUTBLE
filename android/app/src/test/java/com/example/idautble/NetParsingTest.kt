package com.example.idautble

import org.junit.Assert.*
import org.junit.Test

class NetParsingTest {
    @Test
    fun monitorDecodesServerRepliesAndConnectionHistory() {
        val status = parseNetStatusLine(
            "NETSTAT=WIFI=1;LINK=UP;TCP=UP;TCPMON=1;TCPUP=12;" +
                "TCPDOWN=PONG_TIMEOUT,15;TCPRX=1,504F4E47|2,41434B3D34323B2B+",
        )!!
        val monitor = status.tcpMonitor!!
        assertEquals(12L, monitor.connectionAgeSeconds)
        assertEquals(TcpDisconnection("PONG_TIMEOUT", 15), monitor.disconnection)
        assertEquals(
            listOf(TcpServerReply(1, "PONG", false), TcpServerReply(2, "ACK=42;+", true)),
            monitor.replies,
        )
    }

    @Test
    fun legacyStatusAndMalformedMonitorKeepLiveStatusUsable() {
        val line = "NETSTAT=WIFI=1;LINK=UP;TCP=DOWN"
        assertNull(parseNetStatusLine(line)!!.tcpMonitor)
        val status = parseNetStatusLine("$line;TCPMON=1;TCPUP=-1;TCPRX=0,1B|1,XX")!!
        assertFalse(status.tcpUp)
        assertNull(status.tcpMonitor!!.connectionAgeSeconds)
        assertTrue(status.tcpMonitor.replies.isEmpty())
    }

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
        assertEquals(
            "x=y",
            parseNetConfigLine(
                    "NET=MODE=DHCP;SSID=Test;PWD=x=y;IP=192.168.1.10;GW=192.168.1.1;MASK=255.255.255.0;HOST=192.168.1.2;PORT=9000"
                )!!
                .pwd,
        )
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
                .receive(
                    "NET=MODE=DHCP;SSID=Test;PWD=***;IP=192.168.1.10;GW=192.168.1.1;MASK=255.255.255.0;HOST=192.168.1.2;PORT=9000"
                )
                .receive("NETSTAT=WIFI=1;LINK=UP;TCP=DOWN")
        assertEquals("Test", state.config!!.ssid)
        assertTrue(state.status!!.linkUp)
        assertEquals(1L, state.configRevision)
        assertEquals(1L, state.statusRevision)
    }

    @Test
    fun identicalReadStillHasNewRevisionToRefreshEditedFields() {
        val line =
            "NET=MODE=DHCP;SSID=Test;PWD=***;IP=192.168.1.10;GW=192.168.1.1;MASK=255.255.255.0;HOST=192.168.1.2;PORT=9000"
        val first = NetworkSnapshot().receive(line)
        val second = first.receive(line)
        assertEquals(first.config, second.config)
        assertEquals(first.configRevision + 1, second.configRevision)
    }
}
