package dev.lodekeeper.nav;

/** Immutable route snapshot. Steps include the start stance at index zero. */
public final class Path {
    public enum Movement { START, WALK, JUMP, DROP, PARKOUR, SWIM, CLIMB, BRIDGE }

    public static final class Step {
        public final int x;
        /** Floor block containing the feet; retained for legacy consumers. */
        public final int y;
        public final int z;
        /** Exact absolute feet height in sixteenths of a block. */
        public final int feetY16;
        /** Movement from the preceding step; START for index zero. */
        public final Movement movement;
        /** Actions the executor must complete and verify before moving into this stance. */
        private final Action[] actions;

        Step(int x, int y, int z, Movement movement, Action[] actions) {
            this(x, integerFeetY16(x, y, z), z, movement, actions, true);
        }

        private Step(int x, int feetY16, int z, Movement movement, Action[] actions,
                     boolean exactFeetHeight) {
            Position.pack(x, Math.floorDiv(feetY16, 16), z);
            this.x = x;
            this.y = Math.floorDiv(feetY16, 16);
            this.z = z;
            this.feetY16 = feetY16;
            this.movement = movement;
            this.actions = actions.clone();
        }

        static Step atFeetY16(int x, int feetY16, int z, Movement movement, Action[] actions) {
            return new Step(x, feetY16, z, movement, actions, true);
        }

        public double feetY() { return feetY16 / 16.0; }

        private static int integerFeetY16(int x, int y, int z) {
            Position.pack(x, y, z);
            return y * 16;
        }

        public int actionCount() { return actions.length; }
        public Action action(int index) { return actions[index]; }
        public Action[] actions() { return actions.clone(); }
    }

    private final Step[] steps;
    public final long cost;
    public final int placementsReserved;
    public final long terrainRevision;

    Path(Step[] steps, long cost, int placementsReserved, long terrainRevision) {
        this.steps = steps.clone();
        this.cost = cost;
        this.placementsReserved = placementsReserved;
        this.terrainRevision = terrainRevision;
    }

    public int length() { return steps.length; }
    public Step step(int index) { return steps[index]; }
    public Step[] steps() { return steps.clone(); }
}
