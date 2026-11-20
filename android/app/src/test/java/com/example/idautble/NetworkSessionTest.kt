package com.example.idautble

import org.junit.Assert.*
import org.junit.Test

class NetworkSessionTest {
    private val config =
        NetConfig(
            "DHCP",
            "Test network",
            "***",
            "192.168.1.10",
            "192.168.1.1",
            "255.255.255.0",
            "192.168.1.2",
            9000,
        )

    private fun configLine(c: NetConfig = config) =
        "NET=MODE=${c.mode};SSID=${c.ssid};PWD=${c.pwd};IP=${c.ip};GW=${c.gw};MASK=${c.mask};HOST=${c.host};PORT=${c.port}"

    private val off = "NETSTAT=WIFI=0;LINK=DOWN;TCP=DOWN"
    private val linking = "NETSTAT=WIFI=1;LINK=DOWN;TCP=DOWN"
    private val connected = "NETSTAT=WIFI=1;LINK=UP;TCP=UP;IP=192.168.1.176;RSSI=-35"

    private class Rig {
        var time = 1000L
        val commands = mutableListOf<Pair<String, Long>>()
        val session = NetworkSession({ time }) { command, id -> commands.add(command to id) }

        fun ack(text: String) =
            session.receive(PlcMessage(("\u0006" + text + "\r\n").toByteArray(), false))

        fun sent() {
            session.commandSent(commands.last().second)
        }

        fun open() {
            session.setForeground(true)
            session.setReady(true)
            session.setVisible(true)
        }

        fun advance(ms: Long) {
            time += ms
            session.tick()
        }
    }

    private fun loaded(status: String = off): Rig =
        Rig().apply {
            open()
            sent()
            ack(configLine())
            sent()
            ack(status)
        }

    @Test
    fun tcpAdvancedRequiresExactReadbackAndReportsLiveDiagnostics() {
        val r = loaded()
        r.ack(configLine() + ";TCPADV=0,0,10,20")
        val changed = TcpAdvancedConfig(true, true, 15, 45)
        r.session.saveTcpAdvanced(changed)
        assertEquals("TCPADV=1,1,15,45", r.commands.last().first)
        assertTrue(requiresMaintenanceAuthorization("TCPADV=1,1,15,45\r\n".toByteArray()))
        r.sent()
        r.ack("OK")
        assertFalse(r.session.state.tcpAdvanced!!.heartbeat)
        r.ack("TCPADV=1,1,15,45")
        assertEquals(changed, r.session.state.tcpAdvanced)
        r.sent()
        r.ack(connected + ";PONGAGE=2;EVT_PENDING=4")
        assertEquals(2L, r.session.state.status!!.pongAgeSeconds)
        assertEquals(4, r.session.state.status!!.pendingEvents)
    }

    @Test
    fun tcpAdvancedRejectsInvalidTimingAndDoesNotOfferSettingsForOlderFirmware() {
        val r = loaded()
        val oldCount = r.commands.size
        assertNull(r.session.state.tcpAdvanced)
        r.session.saveTcpAdvanced(TcpAdvancedConfig(true, false, 10, 20))
        assertEquals(oldCount, r.commands.size)
        r.ack(configLine() + ";TCPADV=0,0,10,20")
        r.session.saveTcpAdvanced(TcpAdvancedConfig(true, false, 10, 10))
        assertEquals(oldCount, r.commands.size)
        assertNull(parseTcpAdvanced("TCPADV=1,0,4,20"))
        assertNull(parseTcpAdvanced("TCPADV=1,0,10,301"))
    }

    @Test
    fun openingReadsConfigurationOverBleWithWifiOffAndDoesNotEnableWifi() {
        val r = loaded()
        assertEquals(listOf("NET?", "NETSTAT?"), r.commands.map { it.first })
        assertEquals(config, r.session.state.config)
        assertFalse(r.session.state.status!!.wifiEnabled)
        assertFalse(r.session.state.saved)
    }

    @Test
    fun openingBeforeBluetoothReadyWaitsForHandshake() {
        val r = Rig()
        r.session.setForeground(true)
        r.session.setVisible(true)
        assertTrue(r.commands.isEmpty())
        r.session.setReady(true)
        assertEquals("NET?", r.commands.single().first)
    }

    @Test
    fun tabChangesAndReloadKeepUnsavedDraft() {
        val r = loaded()
        r.session.edit { it.copy(host = "192.168.1.9") }
        r.session.setVisible(false)
        r.session.setVisible(true)
        r.sent()
        r.ack(configLine())
        assertEquals("192.168.1.9", r.session.state.draft.host)
        assertEquals(config.host, r.session.state.config!!.host)
        assertTrue(r.session.state.dirty)
    }

    @Test
    fun disconnectClearsConfigPasswordDraftAndInvalidatesQueuedWrites() {
        val r = loaded()
        r.session.edit { it.copy(pwd = "example-secret") }
        r.session.save()
        val id = r.commands.last().second
        r.session.reset()
        assertFalse(r.session.isCurrent(id))
        assertNull(r.session.state.config)
        assertEquals("", r.session.state.draft.pwd)
        assertNull(r.session.state.status)
    }

    @Test
    fun saveNeedsOkAndMatchingConfigurationNotJustGenericOk() {
        val r = loaded()
        r.session.edit { it.copy(port = "9001") }
        r.session.save()
        r.sent()
        r.ack("OK")
        assertFalse(r.session.state.saved)
        assertTrue(r.session.state.busy)
        r.ack("STATUS=1")
        assertFalse(r.session.state.saved)
        r.ack(configLine(config.copy(port = 9001)))
        assertTrue(r.session.state.saved)
        assertFalse(r.session.state.dirty)
        assertEquals("NETSTAT?", r.commands.last().first)
    }

    @Test
    fun readbackBeforeOkCanAlsoConfirmSave() {
        val r = loaded()
        r.session.edit { it.copy(port = "9001") }
        r.session.save()
        r.sent()
        r.ack(configLine(config.copy(port = 9001)))
        assertFalse(r.session.state.saved)
        r.ack("OK")
        assertTrue(r.session.state.saved)
    }

    @Test
    fun mismatchingReadbackNeverReportsSaved() {
        val r = loaded()
        r.session.edit { it.copy(port = "9001") }
        r.session.save()
        r.sent()
        r.ack("OK")
        r.ack(configLine())
        assertFalse(r.session.state.saved)
        assertTrue(r.session.state.dirty)
        assertTrue(r.session.state.error!!.contains("different configuration"))
    }

    @Test
    fun maskedPasswordIsPreservedWhileNewPasswordIsSentLiterally() {
        val r = loaded()
        r.session.edit { it.copy(port = "9001") }
        r.session.save()
        assertFalse(r.commands.last().first.contains("PWD="))
        r.session.authorisationCancelled("Cancelled")
        r.session.edit { it.copy(pwd = "sample<LF>=value") }
        r.session.save()
        assertTrue(r.commands.last().first.contains("PWD=sample<LF>=value;"))
    }

    @Test
    fun newPasswordIsMaskedAfterConfirmedSave() {
        val r = loaded()
        r.session.edit { it.copy(pwd = "example-secret") }
        r.session.save()
        r.sent()
        r.ack("OK")
        r.ack(configLine())
        assertTrue(r.session.state.saved)
        assertEquals("***", r.session.state.draft.pwd)
    }

    @Test
    fun cancelledPinInvalidatesProtectedWrite() {
        val r = loaded()
        r.session.edit { it.copy(port = "9001") }
        r.session.save()
        val id = r.commands.last().second
        r.session.authorisationCancelled("PIN entry cancelled")
        assertFalse(r.session.isCurrent(id))
        assertFalse(r.session.state.busy)
        r.ack("OK")
        r.ack(configLine())
        assertFalse(r.session.state.saved)
    }

    @Test
    fun unsentAuthorisationTimesOutWithoutRetry() {
        val r = loaded()
        r.session.edit { it.copy(port = "9001") }
        r.session.save()
        val id = r.commands.last().second
        r.session.setVisible(false)
        r.advance(60000)
        assertFalse(r.session.isCurrent(id))
        assertFalse(r.session.state.busy)
        assertEquals(3, r.commands.size)
        assertTrue(r.session.state.error!!.contains("timed out"))
    }

    @Test
    fun submittedOperationTimesOutInEightSecondsEvenOffTab() {
        val r = loaded()
        r.session.edit { it.copy(port = "9001") }
        r.session.save()
        r.sent()
        r.session.setVisible(false)
        r.advance(8000)
        assertFalse(r.session.state.busy)
        assertFalse(r.session.state.saved)
        assertTrue(r.session.state.error!!.contains("may have been applied"))
    }

    @Test
    fun lateReplyCannotConfirmExpiredSaveBeforeTimerTick() {
        val r = loaded()
        r.session.edit { it.copy(port = "9001") }
        r.session.save()
        r.sent()
        r.time += 8_000
        r.ack("OK")
        r.ack(configLine(config.copy(port = 9001)))
        assertFalse(r.session.state.saved)
        assertTrue(r.session.state.error!!.contains("may have been applied"))
    }

    @Test
    fun cancellingAnotherPinDialogDoesNotClaimAlreadySubmittedWriteWasCancelled() {
        val r = loaded()
        r.session.edit { it.copy(port = "9001") }
        r.session.save()
        r.sent()
        r.session.authorisationCancelled("PIN entry cancelled")
        assertTrue(r.session.state.busy)
        r.ack("OK")
        r.ack(configLine(config.copy(port = 9001)))
        assertTrue(r.session.state.saved)
    }

    @Test
    fun configurationQueriesNeedNoMaintenanceAuthorisation() {
        assertFalse(requiresMaintenanceAuthorization("NET?\r\n".toByteArray()))
        assertFalse(requiresMaintenanceAuthorization("NETSTAT?\r\n".toByteArray()))
        assertTrue(requiresMaintenanceAuthorization("WIFI=1\r\n".toByteArray()))
        assertTrue(
            requiresMaintenanceAuthorization((buildNetSetCommand(config) + "\r\n").toByteArray())
        )
    }

    @Test
    fun wifiEnableIsConfirmedSeparatelyFromAssociationAndTcp() {
        val r = loaded()
        r.session.toggleWifi()
        r.sent()
        assertEquals("WIFI=1", r.commands.last().first)
        assertFalse(r.session.state.status!!.wifiEnabled)
        r.ack("OK")
        assertTrue(r.session.state.busy)
        r.ack(linking)
        assertFalse(r.session.state.busy)
        assertTrue(r.session.state.status!!.wifiEnabled)
        assertFalse(r.session.state.status!!.linkUp)
        assertFalse(r.session.state.status!!.tcpUp)
    }

    @Test
    fun enablingWithNoSsidDoesNotSendWifiCommand() {
        val r = loaded()
        r.ack(configLine(config.copy(ssid = "")))
        r.session.toggleWifi()
        assertEquals(2, r.commands.size)
        assertNotNull(r.session.state.error)
    }

    @Test
    fun liveDhcpAddressIsIndependentOfStoredStaticAddress() {
        val r = loaded(connected)
        assertEquals("192.168.1.176", r.session.state.status!!.ip)
        assertEquals("192.168.1.10", r.session.state.config!!.ip)
    }

    @Test
    fun pollingIsReadOnlyAndLimitedToVisibleForegroundNetwork() {
        val r = loaded()
        r.advance(5000)
        assertEquals("NETSTAT?", r.commands.last().first)
        r.sent()
        r.ack(off)
        val count = r.commands.size
        r.session.setVisible(false)
        r.advance(30000)
        assertEquals(count, r.commands.size)
        r.session.setVisible(true)
        r.sent()
        r.ack(configLine())
        r.sent()
        r.ack(off)
        r.session.setForeground(false)
        val count2 = r.commands.size
        r.advance(30000)
        assertEquals(count2, r.commands.size)
    }

    @Test
    fun otaCancelsPendingWriteAndPausesPolling() {
        val r = loaded()
        r.session.edit { it.copy(port = "9001") }
        r.session.save()
        val id = r.commands.last().second
        r.session.setPaused(true)
        val count = r.commands.size
        r.advance(30000)
        assertFalse(r.session.isCurrent(id))
        assertEquals(count, r.commands.size)
        r.session.setPaused(false)
        assertEquals("NET?", r.commands.last().first)
    }

    @Test
    fun connectionTimeoutExplainsWifiFailureAndRecoveryClearsIt() {
        val r = loaded()
        r.session.toggleWifi()
        r.sent()
        r.ack("OK")
        r.ack(linking)
        r.advance(30000)
        assertTrue(r.session.state.error!!.contains("SSID"))
        r.ack(connected)
        assertNull(r.session.state.error)
    }

    @Test
    fun connectionTimeoutDistinguishesDhcpAndTcpFailure() {
        for ((status, expected) in
            listOf(
                "NETSTAT=WIFI=1;LINK=UP;TCP=DOWN;IP=255.255.255.255" to "DHCP",
                "NETSTAT=WIFI=1;LINK=UP;TCP=DOWN;IP=192.168.1.176" to "server",
            )) {
            val r = loaded()
            r.session.toggleWifi()
            r.sent()
            r.ack("OK")
            r.ack(status)
            r.advance(30000)
            assertTrue(r.session.state.error!!.contains(expected))
        }
    }

    @Test
    fun invalidAndUnacknowledgedResponsesDoNotWipeValidValues() {
        val r = loaded(connected)
        r.session.receive(
            PlcMessage((configLine(config.copy(ssid = "wrong")) + "\r\n").toByteArray(), false)
        )
        r.ack("NETSTAT=WIFI=not-a-flag")
        assertEquals(config, r.session.state.config)
        assertTrue(r.session.state.status!!.tcpUp)
        r.session.readConfiguration()
        r.sent()
        r.ack("NET=MODE=DHCP")
        assertEquals(config, r.session.state.config)
        assertNotNull(r.session.state.error)
    }

    @Test
    fun rejectedChangeRetainsDraftAndExplainsAuth() {
        val r = loaded()
        r.session.edit { it.copy(port = "9001") }
        r.session.save()
        r.sent()
        r.session.receive(PlcMessage("\u0015ERR=AUTH_REQUIRED\r\n".toByteArray(), false))
        assertTrue(r.session.state.dirty)
        assertFalse(r.session.state.busy)
        assertTrue(r.session.state.error!!.contains("PIN"))
    }

    @Test
    fun editingIsAllowedDuringStatusPollButNotDuringSave() {
        val r = loaded()
        r.session.refreshStatus()
        r.session.edit { it.copy(port = "9001") }
        assertEquals("9001", r.session.state.draft.port)
        r.sent()
        r.ack(off)
        r.session.save()
        r.session.edit { it.copy(port = "9002") }
        assertEquals("9001", r.session.state.draft.port)
    }

    @Test
    fun discardOnlyChangesDraftAndSendsNothing() {
        val r = loaded()
        r.session.edit { it.copy(port = "9001") }
        r.session.discardEdits()
        assertEquals(NetworkDraft.from(config), r.session.state.draft)
        assertEquals(2, r.commands.size)
    }

    @Test
    fun staticSubnetAndCommandInjectionValidationBlockSave() {
        val d = NetworkDraft.from(config).copy(mode = "STATIC")
        assertTrue(networkDraftErrors(d).isEmpty())
        for (bad in
            listOf(
                d.copy(mask = "255.0.255.0"),
                d.copy(gw = "192.168.2.1"),
                d.copy(ip = "192.168.1.0"),
                d.copy(ip = "192.168.1.255"),
                d.copy(port = "65536"),
                d.copy(host = "http://example.com"),
                d.copy(ssid = "test;WIFI=1"),
                d.copy(pwd = "a\r\nWIFI=1"),
            )) {
            assertTrue("Expected validation failure: $bad", networkDraftErrors(bad).isNotEmpty())
        }
    }

    @Test
    fun wifiNamesAndPasswordsUseUtf8ByteLimits() {
        val d = NetworkDraft.from(config)
        assertTrue(networkDraftErrors(d.copy(ssid = "é".repeat(16))).isEmpty())
        assertTrue(networkDraftErrors(d.copy(ssid = "é".repeat(17))).isNotEmpty())
        assertTrue(networkDraftErrors(d.copy(pwd = "é".repeat(33))).isNotEmpty())
    }

    @Test
    fun backgroundPollKeepsControlsStableBeforeAndAfterReply() {
        val r = loaded()
        assertFalse(r.session.state.controlsBusy)
        r.advance(5_000)
        assertTrue(r.session.state.busy)
        assertTrue(r.session.state.pending!!.automatic)
        assertFalse(r.session.state.controlsBusy)
        r.sent()
        r.ack(off)
        assertFalse(r.session.state.controlsBusy)
    }

    @Test
    fun wifiActionSupersedesBackgroundPollWithoutLosingClick() {
        val r = loaded()
        r.session.refreshStatus(automatic = true)
        val pollId = r.commands.last().second
        r.session.toggleWifi()
        assertEquals("WIFI=1", r.commands.last().first)
        assertFalse(r.session.isCurrent(pollId))
        assertTrue(r.session.state.controlsBusy)
        // A snapshot from the superseded query must not confirm the unsent change.
        r.ack(off)
        assertEquals(NetworkOperation.WIFI_ON, r.session.state.pending!!.operation)
        r.sent()
        r.ack("OK")
        r.ack(linking)
        assertFalse(r.session.state.controlsBusy)
    }

    @Test
    fun manualRefreshShowsUserOperationEvenWhenBackgroundReadWasPending() {
        val r = loaded()
        r.session.refreshStatus(automatic = true)
        val pollId = r.commands.last().second
        r.session.refreshStatus()
        assertFalse(r.session.isCurrent(pollId))
        assertFalse(r.session.state.pending!!.automatic)
        assertTrue(r.session.state.controlsBusy)
        r.sent()
        r.ack(off)
        assertFalse(r.session.state.controlsBusy)
    }

    @Test
    fun saveAndReloadCanSupersedePollingWithoutReplayingItsQueueEntry() {
        for (save in listOf(true, false)) {
            val r = loaded()
            r.session.edit { it.copy(port = "9001") }
            r.session.refreshStatus(automatic = true)
            val oldId = r.commands.last().second
            if (save) r.session.save() else r.session.readConfiguration()
            assertFalse(r.session.isCurrent(oldId))
            assertEquals(
                if (save) NetworkOperation.SAVE else NetworkOperation.READ_CONFIG,
                r.session.state.pending!!.operation,
            )
            r.session.commandSent(oldId)
            assertNull(r.session.state.pending!!.sentAt)
            r.ack(off)
            assertTrue(r.session.state.controlsBusy)
        }
    }

    @Test
    fun discardEditsRemainsAvailableWhilePolling() {
        val r = loaded()
        r.session.edit { it.copy(port = "9001") }
        r.session.refreshStatus(automatic = true)
        val pending = r.session.state.pending
        r.session.discardEdits()
        assertFalse(r.session.state.dirty)
        assertEquals(pending, r.session.state.pending)
        assertFalse(r.session.state.controlsBusy)
    }

    @Test
    fun parserRejectsMissingDuplicateInvalidAndPrefixedRecords() {
        assertNull(parseNetConfigLine("NET=MODE=DHCP"))
        assertNull(parseNetConfigLine(configLine() + ";SSID=duplicate"))
        assertNull(parseNetConfigLine(configLine().replace("PORT=9000", "PORT=0")))
        assertNull(parseNetConfigLine("other=" + configLine()))
        for (bad in
            listOf(
                "NETSTAT=WIFI=1;LINK=UP",
                "NETSTAT=WIFI=maybe;LINK=UP;TCP=DOWN",
                off + ";WIFI=1",
                connected.replace("192.168.1.176", "999.1.2.3"),
            )) assertNull(parseNetStatusLine(bad))
        assertEquals(config, parseNetConfigLine("\u0006" + configLine() + "\r\n"))
    }
}
