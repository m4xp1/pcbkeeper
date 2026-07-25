package com.max.privatecardbackup;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class StabilityTrackerTest {
    @Test
    public void requiresAtLeastTenSecondsWithoutMetadataChanges() {
        StabilityTracker tracker = new StabilityTracker();

        assertFalse(tracker.observe("id", 100L, 10L, 1_000L));
        assertFalse(tracker.observe("id", 100L, 10L, 5_000L));
        assertTrue(tracker.observe("id", 100L, 10L, 11_000L));
    }

    @Test
    public void rapidContentObserverEventsCannotBypassTheTimeWindow() {
        StabilityTracker tracker = new StabilityTracker();

        assertFalse(tracker.observe("id", 100L, 10L, 1_000L));
        assertFalse(tracker.observe("id", 100L, 10L, 1_100L));
        assertFalse(tracker.observe("id", 100L, 10L, 1_200L));
        assertFalse(tracker.observe("id", 100L, 10L, 10_999L));
        assertTrue(tracker.observe("id", 100L, 10L, 11_000L));
    }

    @Test
    public void resetsTheWindowWhenSizeTimestampOrIdentityChanges() {
        StabilityTracker tracker = new StabilityTracker();

        assertFalse(tracker.observe("id", 100L, 10L, 0L));
        assertFalse(tracker.observe("id", 101L, 10L, 9_000L));
        assertFalse(tracker.observe("id", 101L, 11L, 18_000L));
        assertFalse(tracker.observe("new-id", 101L, 11L, 27_000L));
        assertFalse(tracker.observe("new-id", 101L, 11L, 36_999L));
        assertTrue(tracker.observe("new-id", 101L, 11L, 37_000L));
    }

    @Test
    public void acceptsUnknownSafSizeAfterTheSameSafetyWindow() {
        StabilityTracker tracker = new StabilityTracker();

        assertFalse(tracker.observe("id", -1L, -1L, 0L));
        assertFalse(tracker.observe("id", -1L, -1L, 9_999L));
        assertTrue(tracker.observe("id", -1L, -1L, 10_000L));
    }

    @Test
    public void neverAcceptsAnEmptyFile() {
        StabilityTracker tracker = new StabilityTracker();

        assertFalse(tracker.observe("id", 0L, 10L, 0L));
        assertFalse(tracker.observe("id", 0L, 10L, 20_000L));
    }
}
