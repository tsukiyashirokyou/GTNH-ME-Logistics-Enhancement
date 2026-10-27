package com.kyou.metransferdrive.transfer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.kyou.metransferdrive.tile.DriveSlotLayout;

/** Device-owned bindings. No live grid identity is ever stored here. */
public final class TargetRegistry {

    /** Fixed metadata/work bound, not a user-adjustable scheduling parameter. */
    public static final int MAX_TARGETS = 64;

    public enum Mode {
        UNASSIGNED,
        AUTOMATIC,
        SPECIFIC
    }

    public static final class Route {

        private final Mode mode;
        private final UUID targetId;

        private Route(final Mode mode, final UUID targetId) {
            this.mode = mode;
            this.targetId = targetId;
        }

        public static Route unassigned() {
            return new Route(Mode.UNASSIGNED, null);
        }

        public static Route automatic() {
            return new Route(Mode.AUTOMATIC, null);
        }

        public static Route specific(final UUID id) {
            return new Route(Mode.SPECIFIC, Objects.requireNonNull(id, "targetId"));
        }

        public Mode getMode() {
            return this.mode;
        }

        public UUID getTargetId() {
            return this.targetId;
        }
    }

    private final Map<UUID, TransferTarget> targets = new LinkedHashMap<>();
    private final Route[] routes = new Route[DriveSlotLayout.GROUP_COUNT];

    public TargetRegistry() {
        Arrays.fill(this.routes, Route.unassigned());
    }

    /** Same coordinates reuse a binding; same grids at different coordinates remain separate bindings. */
    public UUID register(final TransferTarget target) {
        Objects.requireNonNull(target, "target");
        final TransferTarget existingId = this.targets.get(target.getId());
        if (existingId != null && !existingId.sameLocation(target)) {
            throw new IllegalArgumentException("Target ID is already assigned to another interface");
        }
        for (final TransferTarget existing : this.targets.values()) {
            if (existing.sameLocation(target)) {
                return existing.getId();
            }
        }
        if (this.targets.size() >= MAX_TARGETS) {
            throw new IllegalStateException("At most 64 interface bindings are supported");
        }
        this.targets.put(target.getId(), target);
        return target.getId();
    }

    public void updateMetadata(final UUID id, final String name, final int priority) {
        final TransferTarget previous = this.targets.get(id);
        if (previous == null) {
            throw new IllegalArgumentException("Unknown target ID");
        }
        this.targets.put(id, previous.withMetadata(name, priority));
    }

    public void remove(final UUID id) {
        this.targets.remove(id);
        for (int group = 0; group < this.routes.length; group++) {
            if (id.equals(this.routes[group].getTargetId())) {
                this.routes[group] = Route.unassigned();
            }
        }
    }

    public void setRoute(final int group, final Route route) {
        DriveSlotLayout.slot(group, 0);
        Objects.requireNonNull(route, "route");
        if (route.getMode() == Mode.SPECIFIC && !this.targets.containsKey(route.getTargetId())) {
            throw new IllegalArgumentException("Route references an unknown target");
        }
        this.routes[group] = route;
    }

    public Route getRoute(final int group) {
        DriveSlotLayout.slot(group, 0);
        return this.routes[group];
    }

    public List<TransferTarget> getTargets() {
        return Collections.unmodifiableList(new ArrayList<>(this.targets.values()));
    }

    public boolean isEmpty() {
        return this.targets.isEmpty();
    }

    public List<TransferTarget> byPriority() {
        final List<TransferTarget> ordered = new ArrayList<>(this.targets.values());
        // List.sort is stable: equal priorities retain registration order; avoid subtraction overflow.
        ordered.sort((first, second) -> Integer.compare(second.getPriority(), first.getPriority()));
        return Collections.unmodifiableList(ordered);
    }

    /** Candidate edges only; never inspect cells or destination inventory to infer graph connectivity. */
    public List<TransferTarget> routedBindings() {
        final boolean[] groups = new boolean[DriveSlotLayout.GROUP_COUNT];
        Arrays.fill(groups, true);
        return this.routedBindings(groups);
    }

    public List<TransferTarget> routedBindings(final boolean[] eligibleGroups) {
        if (eligibleGroups.length != this.routes.length) {
            throw new IllegalArgumentException("Expected eight eligible groups");
        }
        final List<TransferTarget> result = new ArrayList<>();
        for (final TransferTarget target : this.targets.values()) {
            for (int group = 0; group < this.routes.length; group++) {
                final Route route = this.routes[group];
                if (eligibleGroups[group]
                    && (route.mode == Mode.AUTOMATIC || route.mode == Mode.SPECIFIC && target.getId()
                        .equals(route.targetId))) {
                    result.add(target);
                    break;
                }
            }
        }
        return result;
    }
}
