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
}
