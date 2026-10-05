package dev.lodekeeper.nav;

/** Reusable, bounded, sorted set of absolute grounded feet heights in sixteenths. */
public final class GroundedStanceBuffer {
    public static final int CAPACITY = 64;

    private final int[] feetY16 = new int[CAPACITY];
    private int size;
    private boolean complete = true;

    public GroundedStanceBuffer clear() {
        size = 0;
        complete = true;
        return this;
    }

    /** Adds a unique height in sorted order; overflow marks the entire collection incomplete. */
    public void add(int value) {
        int index = 0;
        while (index < size && feetY16[index] < value) index++;
        if (index < size && feetY16[index] == value) return;
        if (size == feetY16.length) {
            complete = false;
            return;
        }
        System.arraycopy(feetY16, index, feetY16, index + 1, size - index);
        feetY16[index] = value;
        size++;
    }

    public int size() { return size; }

    public int get(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
        return feetY16[index];
    }

    public boolean isComplete() { return complete; }
}
