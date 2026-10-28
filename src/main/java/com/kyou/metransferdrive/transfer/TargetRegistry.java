package com.kyou.metransferdrive.transfer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
        private final Set<UUID> targetIds;

        private Route(final Mode mode, final Collection<UUID> targetIds) {
            this.mode = mode;
            if (targetIds.size() > MAX_TARGETS || targetIds.contains(null)) {
                throw new IllegalArgumentException("Invalid route target set");
            }
            this.targetIds = Collections.unmodifiableSet(new LinkedHashSet<>(targetIds));
        }

        public static Route unassigned() {
            return new Route(Mode.UNASSIGNED, Collections.emptySet());
        }

        public static Route automatic() {
            return new Route(Mode.AUTOMATIC, Collections.emptySet());
        }

        public static Route specific(final UUID id) {
            return specific(Collections.singleton(Objects.requireNonNull(id, "targetId")));
        }

        public static Route specific(final Collection<UUID> ids) {
            return new Route(Mode.SPECIFIC, Objects.requireNonNull(ids, "targetIds"));
        }

        public Set<UUID> getTargetIds() {
            return this.targetIds;
        }

        public Mode getMode() {
            return this.mode;
        }

        public UUID getTargetId() {
            return this.targetIds.size() == 1 ? this.targetIds.iterator()
                .next() : null;
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
            if (this.routes[group].targetIds.contains(id)) {
                final Set<UUID> remaining = new LinkedHashSet<>(this.routes[group].targetIds);
                remaining.remove(id);
                this.routes[group] = remaining.isEmpty() ? Route.unassigned() : Route.specific(remaining);
            }
        }
    }

    public void setRoute(final int group, final Route route) {
        DriveSlotLayout.slot(group, 0);
        Objects.requireNonNull(route, "route");
        if (!this.targets.keySet()
            .containsAll(route.targetIds)) {
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
                if (eligibleGroups[group] && (route.mode == Mode.AUTOMATIC
                    || route.mode == Mode.SPECIFIC && route.targetIds.contains(target.getId()))) {
                    result.add(target);
                    break;
                }
            }
        }
        return result;
    }
}
