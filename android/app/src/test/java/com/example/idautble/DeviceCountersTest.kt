package com.example.idautble

import org.junit.Assert.*
import org.junit.Test

class DeviceCountersTest {
    private val sample =
        "COUNTERS=1;BOOT=12;PON=1540;PAL_C=320;PAL_D=319;FAIL=8;EVT_NEXT=640;EVT_PENDING=2;EVT_OLDEST=638;ACKMODE=1"

    @Test
    fun separatesEventSequenceFromPendingCount() {
        val c = parseDeviceCounters(sample)!!
        assertEquals(12L, c.boots)
        assertEquals(1540L, c.readerTriggers)
        assertEquals(320L, c.palletsLoaded)
        assertEquals(319L, c.palletsUnloaded)
        assertEquals(8L, c.failedReads)
        assertEquals(640L, c.nextEventId)
        assertEquals(2, c.pendingEvents)
        assertEquals(638L, c.oldestEventId)
        assertTrue(c.ackMode)
    }

    @Test
    fun emptyQueueHasNoOldestId() {
        val text = sample.replace("EVT_PENDING=2;EVT_OLDEST=638", "EVT_PENDING=0;EVT_OLDEST=-")
        val c = parseDeviceCounters(text)!!
        assertNull(c.oldestEventId)
        assertEquals(640L, c.nextEventId)
    }

    @Test
    fun zeroDiagnosticCountersAreValid() {
        val text =
            "COUNTERS=1;BOOT=0;PON=0;PAL_C=0;PAL_D=0;FAIL=0;EVT_NEXT=1;EVT_PENDING=0;EVT_OLDEST=-;ACKMODE=0"
        val c = parseDeviceCounters(text)!!
        assertEquals(0L, c.boots)
        assertEquals(0L, c.failedReads)
        assertFalse(c.ackMode)
        assertEquals(1L, c.nextEventId)
    }

    @Test
    fun unsignedValuesDoNotOverflowInt() {
        val text =
            sample
                .replace("BOOT=12", "BOOT=4294967295")
                .replace("PON=1540", "PON=2147483648")
                .replace(
                    "EVT_NEXT=640;EVT_PENDING=2;EVT_OLDEST=638",
                    "EVT_NEXT=4294967295;EVT_PENDING=8;EVT_OLDEST=4294967287",
                )
        val c = parseDeviceCounters(text)!!
        assertEquals(4294967295L, c.boots)
        assertEquals(2147483648L, c.readerTriggers)
        assertEquals(4294967295L, c.nextEventId)
        assertEquals(8, c.pendingEvents)
    }

    @Test
    fun rejectsInvalidUnsignedCounters() {
        for (key in
            listOf("BOOT=12", "PON=1540", "PAL_C=320", "PAL_D=319", "FAIL=8", "EVT_NEXT=640")) {
            for (bad in
                listOf("-1", "+1", "4294967296", "999999999999999999999", "1.0", " 1", "", "١")) {
                assertNull(
                    "$key -> $bad",
                    parseDeviceCounters(sample.replace(key, key.substringBefore('=') + "=" + bad)),
                )
            }
        }
    }

    @Test
    fun rejectsIncompleteDuplicateOrFutureSchema() {
        for (bad in
            listOf(
                sample.substringBefore(";ACKMODE="),
                sample + ";BOOT=1",
                sample.replace("COUNTERS=1", "COUNTERS=2"),
                sample + ";BROKEN",
                sample + ";",
            )) {
            assertNull(bad, parseDeviceCounters(bad))
        }
        assertNotNull(parseDeviceCounters(sample + ";FUTURE=some-value"))
    }

    @Test
    fun rejectsInconsistentEventQueue() {
        for (bad in
            listOf(
                sample.replace("EVT_NEXT=640", "EVT_NEXT=0"),
                sample.replace("EVT_PENDING=2", "EVT_PENDING=9"),
                sample.replace("EVT_PENDING=2", "EVT_PENDING=-1"),
                sample.replace("EVT_OLDEST=638", "EVT_OLDEST=0"),
                sample.replace("EVT_OLDEST=638", "EVT_OLDEST=640"),
                sample.replace("EVT_OLDEST=638", "EVT_OLDEST=-"),
                sample.replace("EVT_PENDING=2", "EVT_PENDING=0"),
            )) assertNull(bad, parseDeviceCounters(bad))
    }

    @Test
    fun requiresExplicitAckMode() {
        assertFalse(parseDeviceCounters(sample.replace("ACKMODE=1", "ACKMODE=0"))!!.ackMode)
        assertNull(parseDeviceCounters(sample.replace("ACKMODE=1", "ACKMODE=2")))
        assertNull(parseDeviceCounters(sample.replace("ACKMODE=1", "ACKMODE=true")))
    }

    @Test
    fun counterLinesSurviveEveryBleSplitPoint() {
        val bytes = ("\u0006" + sample + "\r\n").toByteArray()
        for (split in 0..bytes.size) {
            val decoder = PlcStreamDecoder()
            val messages =
                decoder.feed(bytes.copyOfRange(0, split)) +
                    decoder.feed(bytes.copyOfRange(split, bytes.size))
            assertEquals(1, messages.size)
            assertTrue(messages.single().acknowledged)
            assertEquals(640L, parseDeviceCounters(messages.single().text)!!.nextEventId)
        }
    }

    @Test
    fun invalidResponseDoesNotMakePreviousCountersFresh() {
        val snapshot =
            CountersSnapshot().receive(sample, 1000).querying().receive("COUNTERS=1;BOOT=0", 8000)
        assertEquals(1000L, snapshot.receivedMs)
        assertEquals(12L, snapshot.counters!!.boots)
        assertFalse(snapshot.busy)
        assertTrue(snapshot.message.isNotBlank())
    }

    @Test
    fun timeoutRetainsSnapshotWithoutAdvancingItsAge() {
        val original = CountersSnapshot().receive(sample, 1000)
        val waiting = original.querying()
        assertTrue(waiting.busy)
        val timeout = waiting.timedOut()
        assertEquals(original.counters, timeout.counters)
        assertEquals(original.receivedMs, timeout.receivedMs)
        assertFalse(timeout.busy)
        assertTrue(timeout.message.isNotBlank())
    }

    @Test
    fun validRefreshReplacesAllValuesAndClearsError() {
        val old = CountersSnapshot().receive(sample, 1000).timedOut()
        val updated =
            old.receive(
                sample
                    .replace("BOOT=12", "BOOT=0")
                    .replace("EVT_PENDING=2;EVT_OLDEST=638", "EVT_PENDING=0;EVT_OLDEST=-"),
                9000,
            )
        assertEquals(0L, updated.counters!!.boots)
        assertNull(updated.counters.oldestEventId)
        assertEquals(9000L, updated.receivedMs)
        assertEquals("", updated.message)
        assertFalse(updated.busy)
    }

    @Test
    fun queryingCountersDoesNotRequireAuthorizationOrAcknowledgeAnEvent() {
        assertFalse(requiresMaintenanceAuthorization("COUNTERS?\r\n".toByteArray()))
        val message = PlcStreamDecoder().feed(("\u0006" + sample + "\r\n").toByteArray()).single()
        assertFalse(message.isFrame)
        assertEquals("COUNTERS", message.text.substringBefore('='))
        assertNull(parseDeviceCounters("EVT=42"))
    }
}
