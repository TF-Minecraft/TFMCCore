package net.tfminecraft.tfmccore.resourcepack;

import java.util.*;

/** Terminal outcomes are emitted once, after all expected parts have succeeded. */
public final class PackLoadTracker {
    public enum Result { WAITING, COMPLETE, FAILED, IGNORED }
    private final Set<UUID> expected;
    private final Set<UUID> loaded = new HashSet<>();
    private boolean finished;
    public PackLoadTracker(Set<UUID> expected) {
        if (expected.isEmpty()) throw new IllegalArgumentException("No packs");
        this.expected = Set.copyOf(expected);
    }
    public Result accept(UUID id, boolean success, boolean failure) {
        if (finished || !expected.contains(id)) return Result.IGNORED;
        if (failure) { finished = true; return Result.FAILED; }
        if (success) loaded.add(id);
        if (loaded.containsAll(expected)) { finished = true; return Result.COMPLETE; }
        return Result.WAITING;
    }
    public Set<UUID> ids() { return expected; }
}
