package dev.lodekeeper.core;

import java.util.Objects;
import java.util.UUID;

public sealed interface TravelGoal permits TravelGoal.PointGoal, TravelGoal.ExploreGoal, TravelGoal.FollowGoal {
    record PointGoal(int x, int feetY, int z) implements TravelGoal {
        public PointGoal {
            if (x < -30_000_000 || x > 30_000_000 || z < -30_000_000 || z > 30_000_000
                    || feetY < -4096 || feetY > 4096)
                throw new IllegalArgumentException("Travel coordinates exceed local syntax bounds.");
        }
    }

    record ExploreGoal(int radius, int segments) implements TravelGoal {
        public ExploreGoal {
            if (radius < 16 || radius > 256 || segments < 1 || segments > 16)
                throw new IllegalArgumentException("Explore requires radius 16..256 and segments 1..16.");
        }
    }

    record FollowGoal(UUID target, int seconds) implements TravelGoal {
        public FollowGoal {
            Objects.requireNonNull(target, "target");
            if (seconds < 1 || seconds > 600)
                throw new IllegalArgumentException("Follow requires 1..600 seconds.");
        }
    }
}
