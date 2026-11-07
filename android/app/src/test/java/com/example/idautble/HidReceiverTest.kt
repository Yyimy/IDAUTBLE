package com.example.idautble

import org.junit.Assert.*
import org.junit.Test

class HidReceiverTest {
    private val saved = "HIDRECEIVER=1;SAVED=1;READY=1;WINDOW=0;STORE=OK;ID=C1:02:03:04:05:06"

    @Test fun parsesSavedAndReadyReceiver() {
        val info = requireNotNull(parseHidReceiver(saved))
        assertEquals("C1:02:03:04:05:06", info.identity)
        assertTrue(info.ready)
        assertFalse(info.window)
        assertTrue(info.storageOk)
    }

    @Test fun parsesAndroidPairingWindowAndPasskeyRequest() {
        val text = "HIDRECEIVER=1;SAVED=0;READY=0;WINDOW=1;MODE=ANDROID;PINREQ=1;STORE=OK;ID=-"
        val info = requireNotNull(parseHidReceiver(text))
        assertNull(info.identity)
        assertEquals("ANDROID", info.pairMode)
        assertTrue(info.pinRequired)
        assertTrue(info.window)
    }

    @Test fun keepsLegacyWindowsReceiverRepliesCompatible() {
        val info = requireNotNull(parseHidReceiver(saved))
        assertNull(info.pairMode)
        assertFalse(info.pinRequired)
    }

    @Test fun rejectsForgedOrInconsistentAndroidStatus() {
        val base = "HIDRECEIVER=1;SAVED=0;READY=0;WINDOW=1;MODE=ANDROID;PINREQ=1;STORE=OK;ID=-"
        assertNull(parseHidReceiver(base.replace("MODE=ANDROID", "MODE=UNKNOWN")))
        assertNull(parseHidReceiver(base.replace("PINREQ=1", "PINREQ=2")))
        assertNull(parseHidReceiver(base.replace("WINDOW=1", "WINDOW=0")))
        assertNull(parseHidReceiver(base + ";PINREQ=1"))
        assertNull(parseHidReceiver(base + ";PASSWORD=123456"))
    }

    @Test fun supportsSavedReceiverWaitingForConnection() {
        assertFalse(requireNotNull(parseHidReceiver(saved.replace("READY=1", "READY=0"))).ready)
    }

    @Test fun parsesEmptySelectionAndAssignmentWindow() {
        val info = requireNotNull(parseHidReceiver("HIDRECEIVER=1;SAVED=0;READY=0;WINDOW=1;STORE=OK;ID=-"))
        assertNull(info.identity)
        assertTrue(info.window)
    }

    @Test fun reportsStorageFailureWithoutInventingIdentity() {
        val info = requireNotNull(parseHidReceiver("HIDRECEIVER=1;SAVED=0;READY=0;WINDOW=0;STORE=ERROR;ID=-"))
        assertFalse(info.storageOk)
        assertNull(info.identity)
    }

    @Test fun rejectsIncompleteDuplicateAndInvalidResponses() {
        for (text in listOf(
            saved.substringBefore(";ID="), saved + ";READY=0", saved.replace("READY=1", "READY=2"),
            saved.replace("STORE=OK", "STORE=UNKNOWN"), saved.replace("C1:02:03:04:05:06", "-"),
            saved.replace("SAVED=1", "SAVED=0"), saved.replace("C1:02:03:04:05:06", "not-an-address"),
            saved.replace("HIDRECEIVER=1", "HIDRECEIVER=2"), saved + ";IRK=secret",
        )) assertNull(text, parseHidReceiver(text))
    }

    @Test fun androidEnrollmentRequiresNewFirmware() {
        assertFalse(supportsAndroidHidPairing("FW=3.0.25-rc1;PROTO=V2;HIDRECEIVER=1"))
        assertTrue(supportsAndroidHidPairing("FW=3.0.26-rc1;PROTO=V2;HIDRECEIVER=1"))
        assertTrue(supportsAndroidHidPairing("FW=3.1.0-rc1;PROTO=V2"))
        assertFalse(supportsAndroidHidPairing("FW=HID-PROBE-0.3.2;PROTO=V2"))
        assertFalse(supportsAndroidHidPairing("FW=3.0.26-rcX;PROTO=V2"))
    }

    @Test fun capabilityRequiresCompatibleProbeVersion() {
        assertTrue(supportsPersistentHidReceiver("FW=HID-PROBE-0.2.0;PROTO=V2;ACKMODE=0"))
        assertTrue(supportsPersistentHidReceiver("FW=HID-PROBE-1.0.0;PROTO=V2"))
        for (text in listOf("FW=HID-PROBE-0.1.2", "FW=3.0.12-rc1", "HID-PROBE-0.2.0", "FW=HID-PROBE-0.2.x"))
            assertFalse(text, supportsPersistentHidReceiver(text))
    }

    @Test fun mutationsAndTestTypingRequireMaintenanceAuthorization() {
        for (command in listOf("HIDPAIR=1", "HIDPAIR=0", "HIDFORGET=1", "HIDTYPE=TEST"))
            assertTrue(command, requiresMaintenanceAuthorization((command + "\r\n").toByteArray()))
        assertFalse(requiresMaintenanceAuthorization("HIDRECEIVER?\r\n".toByteArray()))
        assertFalse(requiresMaintenanceAuthorization("HIDTEST?\r\n".toByteArray()))
    }
}
