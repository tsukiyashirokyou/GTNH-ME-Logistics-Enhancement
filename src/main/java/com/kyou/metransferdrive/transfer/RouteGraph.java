package com.kyou.metransferdrive.transfer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Disposable proof of configured candidate paths, not a third-party inventory graph or a persistent grid ID. */
public final class RouteGraph<G> {

    public static final int MAX_GRIDS = 128;
    public static final int MAX_DEVICES = 128;
    public static final int MAX_BINDINGS = 512;
    public static final int MAX_EDGES = 256;
    private static final int MAX_STEPS = 1024;

    public interface Topology<G> {

        long generation();

        Iterator<Device<G>> devices(G grid);
    }

    public interface Device<G> {

        G sourceGrid();

        long revision();

        List<TransferTarget> candidates();

        Link<G> resolve(TransferTarget target);
    }

    public static final class Link<G> {

        private final G grid;
        private final BooleanSupplier valid;

        public Link(final G grid, final BooleanSupplier valid) {
            this.grid = grid;
            this.valid = valid;
        }
    }

    private final G source;
    private final Topology<G> topology;
    private final long generation;
    private final Map<G, List<G>> reverse = new IdentityHashMap<>();
    private final Map<G, Boolean> visited = new IdentityHashMap<>();
    private final Map<G, Boolean> returnsToSource = new IdentityHashMap<>();
    private final List<BooleanSupplier> witnesses = new ArrayList<>();
    private final Deque<G> pending = new ArrayDeque<>();
    private Iterator<Device<G>> machines = Collections.emptyIterator();
    private Iterator<TransferTarget> bindings = Collections.emptyIterator();
    private Device<G> device;
    private G grid;
    private int deviceCount;
    private int bindingCount;
    private int edgeCount;
    private int steps;
    private boolean complete;
    private boolean limited;

    public RouteGraph(final G source, final List<Link<G>> roots, final Topology<G> topology) {
        if (source == null || roots.size() > TargetRegistry.MAX_TARGETS) {
            throw new IllegalArgumentException("Invalid graph roots");
        }
        this.source = source;
        this.topology = topology;
        this.generation = topology.generation();
        this.visited.put(source, Boolean.TRUE);
        for (final Link<G> link : roots) {
            this.add(source, link);
        }
    }

    /** Each unit consumes at most one machine or one interface binding; never scan a storage inventory. */
    public void step(final int budget) {
        if (budget <= 0) {
            throw new IllegalArgumentException("Positive graph budget required");
        }
        for (int work = 0; work < budget && !this.complete && !this.limited; work++) {
            if (++this.steps > MAX_STEPS) {
                this.limited = true;
            } else if (this.bindings.hasNext()) {
                if (++this.bindingCount > MAX_BINDINGS) {
                    this.limited = true;
                } else {
                    final Link<G> link = this.device.resolve(this.bindings.next());
                    if (link != null && link.grid != this.grid && link.valid.getAsBoolean()) {
                        this.add(this.grid, link);
                    }
                }
            } else if (this.machines.hasNext()) {
                if (++this.deviceCount > MAX_DEVICES) {
                    this.limited = true;
                    continue;
                }
                final Device<G> next = this.machines.next();
                if (next == null || next.sourceGrid() != this.grid) {
                    continue;
                }
                this.device = next;
                final long version = next.revision();
                final G original = this.grid;
                this.witnesses.add(() -> next.sourceGrid() == original && next.revision() == version);
                this.bindings = next.candidates()
                    .iterator();
            } else if (!this.pending.isEmpty()) {
                this.grid = this.pending.removeFirst();
                this.machines = this.topology.devices(this.grid);
            } else {
                this.finish();
            }
        }
    }

    private void add(final G origin, final Link<G> link) {
        if (link.grid == null) {
            this.limited = true;
            return;
        }
        this.witnesses.add(link.valid);
        final List<G> predecessors = this.reverse.computeIfAbsent(link.grid, ignored -> new ArrayList<>());
        for (final G predecessor : predecessors) {
            if (predecessor == origin) {
                return;
            }
        }
        if (++this.edgeCount > MAX_EDGES) {
            this.limited = true;
            return;
        }
        predecessors.add(origin);
        if (!this.visited.containsKey(link.grid)) {
            if (this.visited.size() >= MAX_GRIDS) {
                this.limited = true;
                return;
            }
            this.visited.put(link.grid, Boolean.TRUE);
            this.pending.addLast(link.grid);
        }
    }

    private void finish() {
        // At most MAX_GRIDS + MAX_EDGES operations on metadata, no AE/world calls.
        final Deque<G> reachable = new ArrayDeque<>();
        reachable.add(this.source);
        this.returnsToSource.put(this.source, Boolean.TRUE);
        while (!reachable.isEmpty()) {
            for (final G predecessor : this.reverse.getOrDefault(reachable.removeFirst(), Collections.emptyList())) {
                if (this.returnsToSource.put(predecessor, Boolean.TRUE) == null) {
                    reachable.addLast(predecessor);
                }
            }
        }
        this.complete = true;
    }

    public boolean isComplete() {
        return this.complete;
    }

    public boolean isLimited() {
        return this.limited;
    }

    public boolean isFresh() {
        if (!this.sameGeneration()) {
            return false;
        }
        for (final BooleanSupplier witness : this.witnesses) {
            if (!witness.getAsBoolean()) {
                return false;
            }
        }
        return true;
    }

    public boolean sameGeneration() {
        return this.generation == this.topology.generation();
    }

    public boolean blocks(final G target) {
        return this.complete && this.returnsToSource.containsKey(target);
    }
}
