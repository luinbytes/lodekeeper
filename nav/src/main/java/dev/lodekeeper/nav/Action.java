package dev.lodekeeper.nav;

/** An explicit, executor-verifiable world action attached to a path step. */
public final class Action {
    public enum Type { BREAK_BLOCK, PLACE_BLOCK }

    public final Type type;
    public final int x;
    public final int y;
    public final int z;
    /** Expected block-state token for BREAK_BLOCK, item token for PLACE_BLOCK. */
    public final int token;

    Action(Type type, int x, int y, int z, int token) {
        this.type = type;
        this.x = x;
        this.y = y;
        this.z = z;
        this.token = token;
    }
}
