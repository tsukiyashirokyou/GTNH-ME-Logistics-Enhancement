package com.kyou.metransferdrive.binding;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.kyou.metransferdrive.transfer.TargetRegistry;
import com.kyou.metransferdrive.transfer.TransferTarget;

/** Two-step binding policy. World access and the existing device registry are supplied by the server adapter. */
public final class BindingToolLogic {

    public enum Outcome {
        PASS,
        SOURCE_SELECTED,
        NO_SOURCE,
        SOURCE_UNLOADED,
        SOURCE_INVALID,
        SOURCE_ID_MISMATCH,
        DIFFERENT_DIMENSION,
        INVALID_TARGET,
        LOCKED,
        PERMISSION_DENIED,
        SELF_GRID,
        LIMIT_REACHED,
        BOUND,
        BOUND_SAME_GRID,
        BOUND_PENDING,
        DUPLICATE_INTERFACE
    }

    public static final class Position {

        public final int dimension;
        public final int x;
        public final int y;
        public final int z;

        public Position(final int dimension, final int x, final int y, final int z) {
            if (y < 0 || y >= 256 || x < -30000000 || x >= 30000000 || z < -30000000 || z >= 30000000) {
                throw new IllegalArgumentException("Invalid binding coordinates");
            }
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    public static final class SourceReference {

        public final Position position;
        public final UUID deviceId;

        public SourceReference(final Position position, final UUID deviceId) {
            this.position = Objects.requireNonNull(position, "position");
            this.deviceId = Objects.requireNonNull(deviceId, "deviceId");
        }
    }

    public interface Source {

        UUID identity();

        boolean isLocked();

        boolean canConfigure();

        Object grid();

        List<TransferTarget> targets();

        UUID register(TransferTarget target);
    }

    public interface Context {

        boolean isServer();

        int dimension();

        boolean isLoaded(Position position);

        Source sourceAt(Position position);

        boolean isInterface(Position position);

        boolean canUseInterface(Position position);

        Object interfaceGrid(Position position);
    }

    public static final class Result {

        public final Outcome outcome;
        public final SourceReference selection;

        Result(final Outcome outcome, final SourceReference selection) {
            this.outcome = outcome;
            this.selection = selection;
        }
    }

    private BindingToolLogic() {}

    /** Authority gate for onItemUse; inherit Item's false first-hook so Forge sends and authorizes the use. */
    public static boolean handleOnServer(final boolean client, final boolean sneaking) {
        return !client && sneaking;
    }

    public static boolean consumeClientUse(final boolean client, final boolean sneaking) {
        return client && sneaking;
    }

    public static Result use(final boolean sneaking, final SourceReference selected, final Position clicked,
        final Context context) {
        if (!context.isServer() || !sneaking) {
            return result(Outcome.PASS);
        }
        if (clicked.dimension != context.dimension()) {
            return result(Outcome.DIFFERENT_DIMENSION);
        }
        if (!context.isLoaded(clicked)) {
            return result(Outcome.INVALID_TARGET);
        }
        final Source candidate = context.sourceAt(clicked);
        if (candidate != null) {
            if (candidate.isLocked()) {
                return result(Outcome.LOCKED);
            }
            if (!candidate.canConfigure()) {
                return result(Outcome.PERMISSION_DENIED);
            }
            return new Result(Outcome.SOURCE_SELECTED, new SourceReference(clicked, candidate.identity()));
        }
        if (selected == null) {
            return result(Outcome.NO_SOURCE);
        }
        if (selected.position.dimension != context.dimension()) {
            return result(Outcome.DIFFERENT_DIMENSION);
        }
        if (!context.isLoaded(selected.position)) {
            return result(Outcome.SOURCE_UNLOADED);
        }
        final Source source = context.sourceAt(selected.position);
        if (source == null) {
            return result(Outcome.SOURCE_INVALID);
        }
        if (!selected.deviceId.equals(source.identity())) {
            return result(Outcome.SOURCE_ID_MISMATCH);
        }
        if (source.isLocked()) {
            return result(Outcome.LOCKED);
        }
        if (!source.canConfigure()) {
            return result(Outcome.PERMISSION_DENIED);
        }
        if (!context.isInterface(clicked)) {
            return result(Outcome.INVALID_TARGET);
        }
        if (!context.canUseInterface(clicked)) {
            return result(Outcome.PERMISSION_DENIED);
        }
        final Object targetGrid = context.interfaceGrid(clicked);
        if (targetGrid != null && targetGrid == source.grid()) {
            return result(Outcome.SELF_GRID);
        }
        final TransferTarget target = new TransferTarget(
            UUID.randomUUID(),
            clicked.dimension,
            clicked.x,
            clicked.y,
            clicked.z);
        final List<TransferTarget> previous = source.targets();
        boolean duplicate = false;
        boolean sameGrid = false;
        for (final TransferTarget existing : previous) {
            duplicate |= existing.sameLocation(target);
            final Position position = new Position(
                existing.getDimension(),
                existing.getX(),
                existing.getY(),
                existing.getZ());
            if (targetGrid != null && position.dimension == context.dimension()
                && context.isLoaded(position)
                && context.isInterface(position)
                && context.interfaceGrid(position) == targetGrid) {
                sameGrid = true;
            }
        }
        if (!duplicate && previous.size() >= TargetRegistry.MAX_TARGETS) {
            return result(Outcome.LIMIT_REACHED);
        }
        // All mutation goes through the device's existing validated registration entry point.
        source.register(target);
        return result(
            duplicate ? Outcome.DUPLICATE_INTERFACE
                : targetGrid == null ? Outcome.BOUND_PENDING : sameGrid ? Outcome.BOUND_SAME_GRID : Outcome.BOUND);
    }

    private static Result result(final Outcome outcome) {
        return new Result(outcome, null);
    }
}
