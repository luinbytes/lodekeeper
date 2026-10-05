package dev.lodekeeper.nav;

/** Reusable, bounded collection of normalized motion-event times. */
public final class MotionEventBuffer {
    public static final int MAX_CAPACITY = 256;

    private final double[] times;
    private final boolean[] critical;
    private int size;
    private double timeTolerance;
    private boolean complete = true;

    public MotionEventBuffer(int capacity) {
        if (capacity < 1 || capacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("capacity must be between 1 and " + MAX_CAPACITY);
        }
        times = new double[capacity];
        critical = new boolean[capacity];
    }

    /** Resets this buffer and sets the inclusive normalized-time merge tolerance. */
    public MotionEventBuffer clear(double timeTolerance) {
        if (!Double.isFinite(timeTolerance) || timeTolerance < 0.0 || timeTolerance > 1.0) {
            throw new IllegalArgumentException("timeTolerance must be finite and in [0, 1]");
        }
        size = 0;
        this.timeTolerance = timeTolerance;
        complete = true;
        return this;
    }

    /** Adds or merges an event; invalid input and capacity overflow make this collection incomplete. */
    public boolean add(double time, boolean isCritical) {
        if (!complete) return false;
        if (!Double.isFinite(time) || time < 0.0 || time > 1.0) {
            complete = false;
            return false;
        }

        int firstMatch = -1;
        int firstCriticalMatch = -1;
        for (int index = 0; index < size; index++) {
            if (matches(times[index], time)) {
                if (firstMatch < 0) firstMatch = index;
                if (critical[index] && firstCriticalMatch < 0) firstCriticalMatch = index;
            }
        }

        if (firstMatch >= 0) {
            if (!isCritical && firstCriticalMatch >= 0) return true;

            int keepIndex = firstCriticalMatch >= 0 ? firstCriticalMatch : firstMatch;
            double keepTime = firstCriticalMatch >= 0 ? times[firstCriticalMatch]
                    : isCritical ? time : times[firstMatch];
            boolean keepCritical = firstCriticalMatch >= 0 || isCritical;
            int writeIndex = 0;
            for (int readIndex = 0; readIndex < size; readIndex++) {
                if (readIndex == keepIndex) {
                    times[writeIndex] = keepTime;
                    critical[writeIndex] = keepCritical;
                    writeIndex++;
                } else if (firstCriticalMatch >= 0) {
                    if (critical[readIndex] || !matches(times[readIndex], keepTime)) {
                        times[writeIndex] = times[readIndex];
                        critical[writeIndex] = critical[readIndex];
                        writeIndex++;
                    }
                } else if (!matches(times[readIndex], time)) {
                    times[writeIndex] = times[readIndex];
                    critical[writeIndex] = critical[readIndex];
                    writeIndex++;
                } else {
                    // A new critical event replaces all matching regular events. A regular event
                    // keeps the first existing regular timestamp and absorbs its close duplicates.
                }
            }
            size = writeIndex;
            return true;
        }

        if (size == times.length) {
            complete = false;
            return false;
        }
        times[size] = time;
        critical[size] = isCritical;
        size++;
        return true;
    }

    /** Sorts the collected event times in ascending order without allocating. */
    public void sort() {
        for (int index = 1; index < size; index++) {
            double time = times[index];
            boolean isCritical = critical[index];
            int insertAt = index;
            while (insertAt > 0 && times[insertAt - 1] > time) {
                times[insertAt] = times[insertAt - 1];
                critical[insertAt] = critical[insertAt - 1];
                insertAt--;
            }
            times[insertAt] = time;
            critical[insertAt] = isCritical;
        }
    }

    public int size() { return size; }

    public double get(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
        return times[index];
    }

    public boolean isComplete() { return complete; }

    private boolean matches(double first, double second) {
        return Math.abs(first - second) <= timeTolerance;
    }
}
