package nl.arnoldkolkman.akonotulist.core

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test fun durationShort() = assertEquals("1:03", Format.duration(63_000))
    @Test fun durationLong() = assertEquals("1:02:03", Format.duration(3_723_000))
    @Test fun durationNegativeClamps() = assertEquals("0:00", Format.duration(-5))
    @Test fun timestampPadsMinutes() = assertEquals("00:42", Format.timestamp(42_900))
    @Test fun timestampHours() = assertEquals("1:00:00", Format.timestamp(3_600_000))

    @Test fun costIsDurationTimesRate() {
        // 90 minutes at $0.006/min = $0.54
        assertEquals(0.54, Format.estimateCostUsd(90 * 60_000L, 0.006), 1e-9)
    }
}
