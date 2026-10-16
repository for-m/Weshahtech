package com.weshah.core.models

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The same table is executed by weshah-agent's own test-suite on the router side
 * (test/target/run.sh, "schedule semantics"). Client and router must never disagree.
 */
class TimeRangeTest {

    private fun range(sh: Int, sm: Int, eh: Int, em: Int, vararg days: Int) =
        TimeRange(sh, sm, eh, em, days.toSet())

    private fun check(r: TimeRange, day: Int, minute: Int, expected: Boolean) =
        assertEquals("day=$day minute=$minute", expected, r.isAllowedAt(day, minute))

    @Test fun `daytime window has an inclusive start and an exclusive end`() {
        val r = range(8, 0, 22, 0)
        check(r, 1, 479, false); check(r, 1, 480, true); check(r, 1, 1319, true); check(r, 1, 1320, false)
    }

    @Test fun `23-59 end means end of day`() {
        val r = range(0, 0, 23, 59)
        check(r, 3, 0, true); check(r, 3, 1439, true)
    }

    @Test fun `weekday filter uses ISO numbering`() {
        val r = range(8, 0, 17, 0, 1, 2, 3, 4, 5)
        check(r, 6, 600, false); check(r, 7, 600, false); check(r, 5, 600, true); check(r, 1, 600, true)
    }

    @Test fun `overnight window every day`() {
        val r = range(22, 0, 6, 0)
        check(r, 2, 1400, true); check(r, 2, 100, true); check(r, 2, 700, false)
    }

    @Test fun `overnight window belongs to the day it started on`() {
        val friday = range(22, 0, 6, 0, 5)
        check(friday, 5, 1400, true)   // Friday 23:20
        check(friday, 6, 100, true)    // Saturday 01:40 - still Friday's window
        check(friday, 6, 1400, false)  // Saturday night is not covered
        check(friday, 5, 100, false)   // Friday 01:40 belongs to Thursday's window
        val monday = range(22, 0, 6, 0, 1)
        check(monday, 1, 100, false)
        check(monday, 2, 100, true)
        val sunday = range(22, 0, 6, 0, 7)
        check(sunday, 1, 100, true)    // Monday 01:40 is Sunday's wrap (7 -> 1)
    }

    @Test fun `start equals end is the whole day`() {
        val r = range(1, 40, 1, 40)
        check(r, 4, 1000, true)
    }

    @Test fun `end before start on the same minute wraps almost a full day`() {
        val r = range(10, 0, 0, 1, 1)
        check(r, 1, 0, false)
    }
}
