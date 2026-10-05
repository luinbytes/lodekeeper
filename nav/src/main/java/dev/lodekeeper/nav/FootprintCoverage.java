package dev.lodekeeper.nav;

/**
 * Bounded, allocation-free union coverage for projected collision-shape rectangles.
 *
 * <p>Each rectangle is clipped to the queried footprint before it is stored. The
 * covered area is computed as a rectangle union, so overlapping block shapes do
 * not count twice. Overflow makes every coverage query fail closed.</p>
 */
public final class FootprintCoverage {
    public static final int MAX_RECTANGLES = 128;

    private final double[] minX = new double[MAX_RECTANGLES];
    private final double[] maxX = new double[MAX_RECTANGLES];
    private final double[] minZ = new double[MAX_RECTANGLES];
    private final double[] maxZ = new double[MAX_RECTANGLES];
    private final double[] xEvents = new double[MAX_RECTANGLES * 2];
    private final double[] intervalMin = new double[MAX_RECTANGLES];
    private final double[] intervalMax = new double[MAX_RECTANGLES];
    private double footprintMinX, footprintMaxX, footprintMinZ, footprintMaxZ;
    private int rectangleCount;
    private boolean complete;

    public FootprintCoverage() { clear(0, 0, 1, 1); }

    public void clear(double minX, double minZ, double maxX, double maxZ) {
        rectangleCount = 0;
        complete = Double.isFinite(minX) && Double.isFinite(minZ)
                && Double.isFinite(maxX) && Double.isFinite(maxZ)
                && minX < maxX && minZ < maxZ
                && Double.isFinite((maxX - minX) * (maxZ - minZ));
        footprintMinX = minX;
        footprintMaxX = maxX;
        footprintMinZ = minZ;
        footprintMaxZ = maxZ;
    }

    /** Add a projected rectangle. Returns false once the bounded proof overflows. */
    public boolean add(double minX, double minZ, double maxX, double maxZ) {
        if (!complete) return false;
        if (!Double.isFinite(minX) || !Double.isFinite(minZ)
                || !Double.isFinite(maxX) || !Double.isFinite(maxZ)) {
            complete = false;
            return false;
        }
        if (minX > maxX || minZ > maxZ) {
            complete = false;
            return false;
        }
        double clippedMinX = Math.max(footprintMinX, minX);
        double clippedMaxX = Math.min(footprintMaxX, maxX);
        double clippedMinZ = Math.max(footprintMinZ, minZ);
        double clippedMaxZ = Math.min(footprintMaxZ, maxZ);
        if (clippedMinX >= clippedMaxX || clippedMinZ >= clippedMaxZ) return true;
        if (rectangleCount == MAX_RECTANGLES) {
            complete = false;
            return false;
        }
        int index = rectangleCount++;
        this.minX[index] = clippedMinX;
        this.maxX[index] = clippedMaxX;
        this.minZ[index] = clippedMinZ;
        this.maxZ[index] = clippedMaxZ;
        return true;
    }

    public boolean isComplete() { return complete; }

    public int rectangleCount() { return rectangleCount; }

    /** Returns the union area, or NaN if the bounded proof is incomplete. */
    public double coveredArea() {
        if (!complete) return Double.NaN;
        if (rectangleCount == 0) return 0.0;
        int eventCount = 0;
        for (int i = 0; i < rectangleCount; i++) {
            xEvents[eventCount++] = minX[i];
            xEvents[eventCount++] = maxX[i];
        }
        sort(xEvents, eventCount);

        double area = 0.0;
        double previousX = xEvents[0];
        for (int eventIndex = 1; eventIndex < eventCount; eventIndex++) {
            double x = xEvents[eventIndex];
            if (x > previousX) {
                int intervalCount = 0;
                for (int i = 0; i < rectangleCount; i++) {
                    if (minX[i] < x && maxX[i] > previousX) {
                        intervalMin[intervalCount] = minZ[i];
                        intervalMax[intervalCount] = maxZ[i];
                        intervalCount++;
                    }
                }
                area += (x - previousX) * unionLength(intervalCount);
            }
            previousX = x;
        }
        return area;
    }

    /** Returns the uncovered footprint area, or NaN if the bounded proof is incomplete. */
    public double uncoveredArea() {
        double covered = coveredArea();
        return Double.isFinite(covered)
                ? Math.max(0.0, footprintArea() - covered)
                : Double.NaN;
    }

    /**
     * True when the union covers at least {@code fraction} of the full footprint;
     * a relative {@code 1.0e-10} area tolerance absorbs floating point edge noise.
     */
    public boolean coversFraction(double fraction) {
        if (!complete || !Double.isFinite(fraction) || fraction < 0.0 || fraction > 1.0) return false;
        double footprint = footprintArea();
        if (!(footprint > 0.0)) return false;
        double covered = coveredArea();
        return Double.isFinite(covered) && covered + footprint * 1.0e-10 >= footprint * fraction;
    }

    /** True only when the union covers the entire queried footprint. */
    public boolean coversAll() { return coversFraction(1.0); }

    private double footprintArea() {
        return (footprintMaxX - footprintMinX) * (footprintMaxZ - footprintMinZ);
    }

    private double unionLength(int count) {
        if (count == 0) return 0.0;
        for (int i = 1; i < count; i++) {
            double low = intervalMin[i], high = intervalMax[i];
            int j = i;
            while (j > 0 && intervalMin[j - 1] > low) {
                intervalMin[j] = intervalMin[j - 1];
                intervalMax[j] = intervalMax[j - 1];
                j--;
            }
            intervalMin[j] = low;
            intervalMax[j] = high;
        }
        double covered = 0.0;
        double start = intervalMin[0], end = intervalMax[0];
        for (int i = 1; i < count; i++) {
            if (intervalMin[i] <= end) {
                end = Math.max(end, intervalMax[i]);
            } else {
                covered += end - start;
                start = intervalMin[i];
                end = intervalMax[i];
            }
        }
        return covered + end - start;
    }

    private static void sort(double[] values, int count) {
        for (int i = 1; i < count; i++) {
            double value = values[i];
            int j = i;
            while (j > 0 && values[j - 1] > value) {
                values[j] = values[j - 1];
                j--;
            }
            values[j] = value;
        }
    }
}
