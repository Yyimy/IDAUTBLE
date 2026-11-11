package com.example.idautble

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalSectionOrderTest {
    @Test
    fun splitSectionsKeepTheOldPosition() {
        val previous = "DEVICE_STATUS,BARCODE_RTC,BLUETOOTH"
        val expected = listOf(
            TerminalSection.DEVICE_STATUS,
            TerminalSection.BARCODE_ID,
            TerminalSection.RTC,
            TerminalSection.BLUETOOTH,
        )

        assertEquals(expected, TerminalSection.readOrder(previous).take(expected.size))
    }

    @Test
    fun splitSectionsCanBeReorderedIndependently() {
        val order = listOf(TerminalSection.RTC, TerminalSection.BLUETOOTH, TerminalSection.BARCODE_ID)
        val restored = TerminalSection.readOrder(order.joinToString(",") { it.name })

        assertEquals(order, restored.take(order.size))
        assertEquals(TerminalSection.entries.toSet(), restored.toSet())
    }

    @Test
    fun firstLaunchAndExplicitCollapseAllAreDifferent() {
        assertEquals(
            setOf(TerminalSection.BLUETOOTH, TerminalSection.BINARY_COMMANDS, TerminalSection.READINGS),
            TerminalSection.readExpanded(null),
        )
        assertEquals(emptySet<TerminalSection>(), TerminalSection.readExpanded(""))
    }

    @Test
    fun savedExpansionUsesKnownSectionsOnly() {
        assertEquals(
            setOf(TerminalSection.RTC, TerminalSection.HID_OUTPUT),
            TerminalSection.readExpanded("RTC,HID_OUTPUT,RTC,UNKNOWN"),
        )
    }
}
