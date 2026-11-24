package com.example.idautble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlcSoundConfigTest {
    @Test
    fun readsIndependentFlagsAndDurations() {
        val wire = "SOUNDCFG=13,50,150,275,500"
        val config = requireNotNull(parsePlcSoundConfig(wire))
        assertEquals(wire, config.wire())
        assertEquals(listOf(50, 150, 275, 500), config.durations)
        assertTrue(config.enabled(PlcSound.STARTUP))
        assertFalse(config.enabled(PlcSound.PCC_READ))
        assertTrue(config.enabled(PlcSound.PCC_ERROR))
        assertTrue(config.enabled(PlcSound.CC_READ))
        assertFalse(config.enabled(PlcSound.CC_ERROR))
    }

    @Test
    fun rejectsInvalidSettingsAndProtectsWrites() {
        assertNull(parsePlcSoundConfig("SOUNDCFG=32,150,150,150,150"))
        assertNull(parsePlcSoundConfig("SOUNDCFG=0,49,150,150,150"))
        assertNull(parsePlcSoundConfig("SOUNDCFG=0,150,150,150,501"))
        assertNull(parsePlcSoundConfig("SOUNDCFG=0,150,150,150,"))
        assertFalse(requiresMaintenanceAuthorization("SOUNDCFG?\r\n".toByteArray()))
        assertTrue(requiresMaintenanceAuthorization("SOUNDCFG=1,150,150,150,150\r\n".toByteArray()))
        assertTrue(requiresMaintenanceAuthorization("SOUNDTEST=0,150\r\n".toByteArray()))
    }
}
