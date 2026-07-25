package com.max.privatecardbackup;

final class StabilityTracker {
    static final long MIN_STABLE_MS = 10_000L;

    private String documentId;
    private long size = Long.MIN_VALUE;
    private long modified = Long.MIN_VALUE;
    private long unchangedSinceMs = Long.MIN_VALUE;
    private int equalObservations;

    boolean observe(String newDocumentId, long newSize, long newModified, long nowMs) {
        boolean same = newDocumentId != null
                && newDocumentId.equals(documentId)
                && newSize == size
                && newModified == modified
                && newSize != 0L;

        if (same) {
            equalObservations++;
        } else {
            documentId = newDocumentId;
            size = newSize;
            modified = newModified;
            unchangedSinceMs = nowMs;
            equalObservations = newSize != 0L ? 1 : 0;
        }

        return equalObservations >= 2
                && nowMs >= unchangedSinceMs
                && nowMs - unchangedSinceMs >= MIN_STABLE_MS;
    }

    void reset() {
        documentId = null;
        size = Long.MIN_VALUE;
        modified = Long.MIN_VALUE;
        unchangedSinceMs = Long.MIN_VALUE;
        equalObservations = 0;
    }
}
