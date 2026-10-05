package dev.lodekeeper.nav;

/** Immutable route snapshot. Steps include the start stance at index zero. */
public final class Path {
    public enum Movement { START, WALK, JUMP, DROP, PARKOUR, SWIM, CLIMB, BRIDGE }

    public static final class Step {
        public final int x;
        public final int y;
        public final int z;
        /** Movement from the preceding step; START for index zero. */
        public final Movement movement;
        /** Actions the executor must complete and verify before moving into this stance. */
        private final Action[] actions;

        Step(int x, int y, int z, Movement movement, Action[] actions) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.movement = movement;
            this.actions = actions.clone();
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
