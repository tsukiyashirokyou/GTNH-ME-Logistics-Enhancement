package com.kyou.gtnhmelogisticsenhancement.transfer;

import java.util.UUID;

/** Stable local target reference; coordinates locate a node, never identify a grid. */
public final class TransferTarget {

    public interface Lookup<G> {

        boolean isLoaded(int x, int y, int z);

        G activeInterfaceGrid(int x, int y, int z);
    }

    private final UUID id;
    private final int dimension;
    private final int x;
    private final int y;
    private final int z;
    private final String displayName;
    private final int priority;

    public TransferTarget(final UUID id, final int dimension, final int x, final int y, final int z) {
        this(id, dimension, x, y, z, "ME Interface", 0);
    }

    public TransferTarget(final UUID id, final int dimension, final int x, final int y, final int z,
        final String displayName, final int priority) {
        if (id == null || y < 0 || y >= 256) {
            throw new IllegalArgumentException("Invalid target reference");
        }
        if (displayName == null || displayName.trim()
            .isEmpty()
            || displayName.length() > 64
            || displayName.chars()
                .anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Target name must contain 1 to 64 printable characters");
        }
        this.id = id;
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        this.displayName = displayName.trim();
        this.priority = priority;
    }

    public <G> G resolve(final int currentDimension, final G sourceGrid, final Lookup<G> lookup) {
        if (sourceGrid == null || currentDimension != this.dimension || !lookup.isLoaded(this.x, this.y, this.z)) {
            return null;
        }
        final G targetGrid = lookup.activeInterfaceGrid(this.x, this.y, this.z);
        return targetGrid == sourceGrid ? null : targetGrid;
    }

    public UUID getId() {
        return this.id;
    }

    public int getDimension() {
        return this.dimension;
    }

    public int getX() {
        return this.x;
    }

    public int getY() {
        return this.y;
    }

    public int getZ() {
        return this.z;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    public int getPriority() {
        return this.priority;
    }

    public TransferTarget withMetadata(final String name, final int newPriority) {
        return new TransferTarget(this.id, this.dimension, this.x, this.y, this.z, name, newPriority);
    }

    public boolean sameLocation(final TransferTarget other) {
        return this.dimension == other.dimension && this.x == other.x && this.y == other.y && this.z == other.z;
    }
}
