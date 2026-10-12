package io.dataease.enhance;

/** Monotonic Snowflake-style IDs. nodeId must be unique across writers (0..1023). */
public final class EnhanceIds {
    private static final long EPOCH = 1760000000000L;
    private final int nodeId;
    private long previousMs = -1;
    private int sequence = 0;

    public EnhanceIds(int nodeId) {
        if (nodeId < 0 || nodeId > 1023) throw new IllegalArgumentException("nodeId must be 0..1023");
        this.nodeId = nodeId;
    }

    public synchronized long next() {
        long ms = System.currentTimeMillis();
        if (ms < previousMs) throw new IllegalStateException("Clock moved backwards");
        if (ms == previousMs) {
            sequence = (sequence + 1) & 4095;
            if (sequence == 0) {
                do { ms = System.currentTimeMillis(); } while (ms <= previousMs);
            }
        } else {
            sequence = 0;
        }
        previousMs = ms;
        return ((ms - EPOCH) << 22) | ((long) nodeId << 12) | sequence;
    }
}