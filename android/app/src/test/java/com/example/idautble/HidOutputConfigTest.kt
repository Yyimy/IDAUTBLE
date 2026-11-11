package com.example.idautble

import org.junit.Assert.*
import org.junit.Test

class HidOutputConfigTest {
    @Test fun orderAndTimingRoundTripAndPreviewMatchesOutput() {
        val config = HidOutputConfig(locationFirst = true, reportMs = 20, navigationPauseMs = 100)
        assertTrue(config.wire().endsWith(";ORDER=UP;TIMING=20,100"))
        assertEquals(config, parseHidOutputConfig(config.wire()))
        assertEquals("LOC01{TAB}PALLET123{ENTER}", config.preview())
        assertEquals("PALLET123{ENTER}", config.copy(fields = "P").preview())
        assertEquals(HidOutputConfig(), parseHidOutputConfig(config.wire(includeOutputOptions = false)))
        val longest = config.copy(prefix = "\t".repeat(32), separator = "\n".repeat(32),
            ending = "\t".repeat(32), hotkey = "CTRL+ALT+SHIFT+F12")
        assertEquals(longest, parseHidOutputConfig(longest.wire()))
        for (invalid in listOf(";ORDER=UP", ";TIMING=20,100", ";ORDER=U;TIMING=20,100",
            ";ORDER=UP;TIMING=0,100", ";ORDER=UP;TIMING=20,99", ";ORDER=UP;TIMING=20,100,0")) {
            assertNull(parseHidOutputConfig(HidOutputConfig().wire() + invalid))
        }
    }

    @Test fun finalSequenceRoundTripsAndFollowsLastField() {
        val config = HidOutputConfig(ending = "\t\n\n")
        assertEquals("PALLET123{TAB}LOC01{TAB}{ENTER}{ENTER}", config.preview())
        assertEquals("LOC01{TAB}{ENTER}{ENTER}", config.copy(fields = "U").preview())
        assertEquals(config, parseHidOutputConfig(config.wire()))
        assertTrue(config.wire().contains(";ENDHEX=090A0A;"))
        assertNull(parseHidOutputConfig(config.wire() + ";END=ENTER"))
        for (hex in listOf("00", "0D", "98", "0a", "1", "", "09".repeat(33))) {
            assertNull(parseHidOutputConfig(config.wire().replace("090A0A", hex)))
        }
        val longest = config.copy(prefix = "\t".repeat(32), separator = "\n".repeat(32),
            ending = "\t".repeat(32), hotkey = "CTRL+ALT+SHIFT+F12")
        assertEquals(longest, parseHidOutputConfig(longest.wire()))
        val clear = config.copy(ending = "")
        assertEquals(clear, parseHidOutputConfig(clear.wire(includeEndingSequence = true)))
        assertEquals(clear, parseHidOutputConfig(clear.wire(includeEndingSequence = false)))
    }

    @Test fun readShortcutRoundTripsWithoutChangingTheTypedSequence() {
        val config = HidOutputConfig(hotkey = "CTRL+ALT+L")
        assertEquals(config, parseHidOutputConfig(config.wire()))
        assertEquals(HidOutputConfig().preview(), config.preview())
        assertEquals(HidOutputConfig(), parseHidOutputConfig(config.wire(includeHotkey = false)))
        assertEquals(HidOutputConfig(), parseHidOutputConfig(HidOutputConfig().wire(includeHotkey = true)))
        listOf("L", "SHIFT+L", "CTRL+F13", "CTRL+CTRL+L", "ALT+", "ALT+CTRL+L").forEach {
            assertFalse(validHidHotkey(it))
            assertNull(parseHidOutputConfig(HidOutputConfig().wire() + ";HOTKEY=$it"))
        }
    }
    @Test fun extendedKeysCanSeparateOrTerminate() {
        val pattern = "{ESC}{BACKSPACE}{DELETE}{INSERT}{HOME}{END}{PAGEUP}{PAGEDOWN}{F1}{F12}"
        val separator = decodeHidSeparator(pattern)!!
        val config = HidOutputConfig(separator = separator, ending = "\u008b")
        assertEquals("HIDCFG=1;FIELDS=PU;SEPHEX=8485868788898A8B8C97;END=PAGEDOWN;LAYOUT=US", config.wire())
        assertEquals(config, parseHidOutputConfig(config.wire()))
        assertEquals("PALLET123${pattern}LOC01{PAGEDOWN}", config.preview())
        assertNull(decodeHidSeparator(pattern, extended = false))
        assertEquals(32, decodeHidSeparator("{BACKSPACE}".repeat(32))?.length)
        assertNull(decodeHidSeparator("{BACKSPACE}".repeat(33)))
        assertNull(legacyHidEnding(config.ending, extended = false))
    }

    @Test fun arrowsRoundTripAndRequireFirmwareSupport() {
        val pattern = "{RIGHT}{LEFT}{DOWN}{UP}{TAB}"
        val separator = decodeHidSeparator(pattern)!!
        val config = HidOutputConfig(separator = separator)
        assertEquals("HIDCFG=1;FIELDS=PU;SEPHEX=8081828309;END=ENTER;LAYOUT=US", config.wire())
        assertEquals(config, parseHidOutputConfig(config.wire()))
        assertEquals("PALLET123${pattern}LOC01{ENTER}", config.preview())
        assertNull(decodeHidSeparator(pattern, allowArrows = false))
        assertNull(decodeHidSeparator("\u0080"))
        assertEquals("\t", decodeHidSeparator("{TAB}", allowArrows = false))
    }

    @Test fun defaultRoundTrip() {
        val cfg = HidOutputConfig()
        assertEquals("HIDCFG=1;FIELDS=PU;SEPHEX=09;END=ENTER;LAYOUT=US", cfg.wire())
        assertEquals(cfg, parseHidOutputConfig(cfg.wire()))
        assertEquals("PALLET123{TAB}LOC01{ENTER}", cfg.preview())
    }
    @Test fun initialKeysAreSentBeforeTheFirstSelectedField() {
        val config = HidOutputConfig(prefix = decodeHidSeparator("{TAB}{ENTER}{RIGHT}")!!)
        assertEquals("HIDCFG=1;FIELDS=PU;SEPHEX=09;END=ENTER;LAYOUT=US;PREHEX=090A80", config.wire())
        assertEquals(config, parseHidOutputConfig(config.wire()))
        assertEquals("{TAB}{ENTER}{RIGHT}PALLET123{TAB}LOC01{ENTER}", config.preview())
        assertEquals("{TAB}{ENTER}{RIGHT}LOC01{ENTER}", config.copy(fields = "U").preview())
        assertEquals("{TAB}{ENTER}{RIGHT}PALLET123{ENTER}", config.copy(fields = "P").preview())
    }
    @Test fun initialKeysCanBeClearedAndAreStrictlyValidated() {
        val config = HidOutputConfig()
        assertTrue(config.wire(includePrefix = true).endsWith(";PREHEX=-"))
        assertEquals(config, parseHidOutputConfig(config.wire(includePrefix = true)))
        assertEquals(config, parseHidOutputConfig(config.wire()))
        val longest = HidOutputConfig(prefix = "\t".repeat(32), separator = "\n".repeat(32), ending = "\u0085")
        assertEquals(longest, parseHidOutputConfig(longest.wire()))
        listOf("", "00", "0a", "1", "98", "09;PREHEX=-", "09".repeat(33)).forEach { hex ->
            assertNull(parseHidOutputConfig(config.wire() + ";PREHEX=$hex"))
        }
    }
    @Test fun keyCombinationsAndLiteralDelimiters() {
        val bytes = "\n\n;=|\t {}"
        assertEquals(bytes, decodeHidSeparator(hidSeparatorPattern(bytes)))
        assertEquals("\n\n-\t ", decodeHidSeparator("{ENTER}{ENTER}-{TAB}{SPACE}"))
        assertEquals("{ENTER}", decodeHidSeparator("{{ENTER}"))
        val cfg = HidOutputConfig(separator = bytes, layout = "ES", ending = "")
        assertEquals(cfg, parseHidOutputConfig(cfg.wire()))
    }
    @Test fun modesAndIndependentEnding() {
        assertEquals("PALLET123{TAB}", HidOutputConfig(fields = "P", ending = "\t").preview())
        assertEquals("LOC01", HidOutputConfig(fields = "U", ending = "").preview())
        assertEquals("PALLET123LOC01", HidOutputConfig(separator = "", ending = "").preview())
        assertEquals(HidOutputConfig(separator = ""), parseHidOutputConfig(HidOutputConfig(separator = "").wire()))
    }
    @Test fun invalidPatternsAreNotSilentlyChanged() {
        listOf("{F13}", "{enter}", "{", "é", "\r", "\u0000", "x".repeat(33), "{ENTER}".repeat(33)).forEach { assertNull(it, decodeHidSeparator(it)) }
        assertEquals("\n".repeat(32), decodeHidSeparator("{ENTER}".repeat(32)))
    }
    @Test fun strictWireValidation() {
        val valid = HidOutputConfig().wire()
        listOf(valid + ";", valid + ";END=NONE", valid.replace("SEPHEX=09", "SEPHEX=00"),
            valid.replace("SEPHEX=09", "SEPHEX=0A0"), valid.replace("SEPHEX=09", "SEPHEX=0a"),
            valid.replace("FIELDS=PU", "FIELDS=UP"), valid.replace("LAYOUT=US", "LAYOUT=UK"),
            valid.replace("END=ENTER", "END=F13"), valid.replace(";END=ENTER", ""),
            valid.replace("SEPHEX=09", "SEPHEX=" + "41".repeat(33))).forEach { assertNull(it, parseHidOutputConfig(it)) }
    }
    @Test fun formatRequiresMaintenanceAuthorisation() {
        assertTrue(requiresMaintenanceAuthorization(HidOutputConfig().wire().toByteArray()))
        assertFalse(requiresMaintenanceAuthorization("HIDCFG?\r\n".toByteArray()))
    }
}
