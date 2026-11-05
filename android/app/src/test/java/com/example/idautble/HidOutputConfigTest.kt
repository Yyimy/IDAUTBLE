package com.example.idautble

import org.junit.Assert.*
import org.junit.Test

class HidOutputConfigTest {
    @Test fun defaultRoundTrip() {
        val cfg = HidOutputConfig()
        assertEquals("HIDCFG=1;FIELDS=PU;SEPHEX=09;END=ENTER;LAYOUT=US", cfg.wire())
        assertEquals(cfg, parseHidOutputConfig(cfg.wire()))
        assertEquals("PALLET123{TAB}LOC01{ENTER}", cfg.preview())
    }
    @Test fun keyCombinationsAndLiteralDelimiters() {
        val bytes = "\n\n;=|\t {}"
        assertEquals(bytes, decodeHidSeparator(hidSeparatorPattern(bytes)))
        assertEquals("\n\n-\t ", decodeHidSeparator("{ENTER}{ENTER}-{TAB}{SPACE}"))
        assertEquals("{ENTER}", decodeHidSeparator("{{ENTER}"))
        val cfg = HidOutputConfig(separator = bytes, layout = "ES", ending = "NONE")
        assertEquals(cfg, parseHidOutputConfig(cfg.wire()))
    }
    @Test fun modesAndIndependentEnding() {
        assertEquals("PALLET123{TAB}", HidOutputConfig(fields = "P", ending = "TAB").preview())
        assertEquals("LOC01", HidOutputConfig(fields = "U", ending = "NONE").preview())
        assertEquals("PALLET123LOC01", HidOutputConfig(separator = "", ending = "NONE").preview())
        assertEquals(HidOutputConfig(separator = ""), parseHidOutputConfig(HidOutputConfig(separator = "").wire()))
    }
    @Test fun invalidPatternsAreNotSilentlyChanged() {
        listOf("{ESC}", "{enter}", "{", "é", "\r", "\u0000", "x".repeat(33), "{ENTER}".repeat(33)).forEach { assertNull(it, decodeHidSeparator(it)) }
        assertEquals("\n".repeat(32), decodeHidSeparator("{ENTER}".repeat(32)))
    }
    @Test fun strictWireValidation() {
        val valid = HidOutputConfig().wire()
        listOf(valid + ";", valid + ";END=NONE", valid.replace("SEPHEX=09", "SEPHEX=00"),
            valid.replace("SEPHEX=09", "SEPHEX=0A0"), valid.replace("SEPHEX=09", "SEPHEX=0a"),
            valid.replace("FIELDS=PU", "FIELDS=UP"), valid.replace("LAYOUT=US", "LAYOUT=UK"),
            valid.replace("END=ENTER", "END=ESC"), valid.replace(";END=ENTER", ""),
            valid.replace("SEPHEX=09", "SEPHEX=" + "41".repeat(33))).forEach { assertNull(it, parseHidOutputConfig(it)) }
    }
    @Test fun formatRequiresMaintenanceAuthorisation() {
        assertTrue(requiresMaintenanceAuthorization(HidOutputConfig().wire().toByteArray()))
        assertFalse(requiresMaintenanceAuthorization("HIDCFG?\r\n".toByteArray()))
    }
}
