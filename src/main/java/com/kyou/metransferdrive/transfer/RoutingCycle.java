package com.kyou.metransferdrive.transfer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

import com.kyou.metransferdrive.tile.DriveSlotLayout;

/** A bounded, disposable view of the current topology, separate from persistent interface bindings. */
public final class RoutingCycle<G> {

    public enum Status {
        UNVALIDATED,
        OFFLINE_OR_FORBIDDEN,
        READY,
        DUPLICATE_GRID,
        CYCLE_BLOCKED
    }

    public static final class Endpoint<G> {

        private final G grid;
        private final Supplier<G> currentGrid;
        private final BooleanSupplier available;

        public Endpoint(final G grid, final Supplier<G> currentGrid, final BooleanSupplier available) {
            this.grid = Objects.requireNonNull(grid, "grid");
            this.currentGrid = currentGrid;
            this.available = available;
        }

        public G getGrid() {
            return this.grid;
        }

        public boolean isAvailable() {
            return this.currentGrid.get() == this.grid && this.available.getAsBoolean();
        }
    }

    public static final class Target<G> {

        private final TransferTarget binding;
        private final Endpoint<G> endpoint;

        private Target(final TransferTarget binding, final Endpoint<G> endpoint) {
            this.binding = binding;
            this.endpoint = endpoint;
        }

        public TransferTarget getBinding() {
            return this.binding;
        }

        public G getGrid() {
            return this.endpoint.getGrid();
        }

        public boolean isAvailable() {
            return this.endpoint.isAvailable();
        }
    }

    private final List<TransferTarget> bindings;
    private final TargetRegistry.Route[] routes = new TargetRegistry.Route[DriveSlotLayout.GROUP_COUNT];
    private final Map<UUID, Target<G>> resolved = new HashMap<>();
    private final Map<UUID, Status> statuses = new HashMap<>();
    private final Map<Integer, List<Target<G>>> groupTargets = new HashMap<>();
    private final Map<G, Boolean> seen = new IdentityHashMap<>();
    private final Supplier<G> sourceGrid;
    private final G originalSourceGrid;
    private final Function<TransferTarget, Endpoint<G>> lookup;
    private int next;

    public RoutingCycle(final TargetRegistry registry, final Supplier<G> sourceGrid,
        final Function<TransferTarget, Endpoint<G>> lookup) {
        this.bindings = registry.byPriority();
        for (int group = 0; group < this.routes.length; group++) {
            this.routes[group] = registry.getRoute(group);
        }
        this.sourceGrid = sourceGrid;
        this.originalSourceGrid = sourceGrid.get();
        this.lookup = lookup;
    }

    /** Performs at most budget world/interface lookups; idle devices never call this. */
    public boolean prepare(final int budget) {
        if (budget <= 0) {
            throw new IllegalArgumentException("Resolution budget must be positive");
        }
        for (int checked = 0; checked < budget && this.next < this.bindings.size(); checked++) {
            final TransferTarget binding = this.bindings.get(this.next++);
            final Endpoint<G> endpoint = this.lookup.apply(binding);
            if (endpoint == null || endpoint.getGrid() == this.originalSourceGrid || !endpoint.isAvailable()) {
                this.statuses.put(binding.getId(), Status.OFFLINE_OR_FORBIDDEN);
            } else {
                this.resolved.put(binding.getId(), new Target<>(binding, endpoint));
                this.statuses.put(
                    binding.getId(),
                    this.seen.put(endpoint.getGrid(), Boolean.TRUE) == null ? Status.READY : Status.DUPLICATE_GRID);
            }
        }
        return this.isPrepared();
    }

    public boolean isPrepared() {
        return this.next == this.bindings.size();
    }

    /** Cached node identities only; never enumerate target inventory or persist a grid/hash code. */
    public boolean isTopologyValid() {
        if (this.originalSourceGrid == null || this.sourceGrid.get() != this.originalSourceGrid) {
            return false;
        }
        for (final Target<G> target : this.resolved.values()) {
            if (target.endpoint.currentGrid.get() != target.getGrid()) {
                return false;
            }
        }
        return true;
    }

    public List<Target<G>> targetsForGroup(final int group) {
        DriveSlotLayout.slot(group, 0);
        if (!this.isPrepared()) {
            throw new IllegalStateException("Resolve the cycle before routing");
        }
        if (this.groupTargets.containsKey(group)) {
            return this.groupTargets.get(group);
        }
        final TargetRegistry.Route route = this.routes[group];
        final List<Target<G>> result = new ArrayList<>();
        final Map<G, Boolean> grids = new IdentityHashMap<>();
        for (final TransferTarget binding : this.bindings) {
            if (route.getMode() == TargetRegistry.Mode.UNASSIGNED
                || route.getMode() == TargetRegistry.Mode.SPECIFIC && !route.getTargetIds()
                    .contains(binding.getId())) {
                continue;
            }
            final Target<G> target = this.resolved.get(binding.getId());
            if (target != null && grids.put(target.getGrid(), Boolean.TRUE) == null) {
                result.add(target);
            }
        }
        final List<Target<G>> snapshot = Collections.unmodifiableList(result);
        this.groupTargets.put(group, snapshot);
        return snapshot;
    }

    public Status getStatus(final UUID id) {
        return this.statuses.getOrDefault(id, Status.UNVALIDATED);
    }

    public G getResolvedGrid(final UUID id) {
        final Target<G> target = this.resolved.get(id);
        return target == null ? null : target.getGrid();
    }
}
