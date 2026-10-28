package com.kyou.metransferdrive.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

/** Topology/AE nodes are doubles. These tests prove graph bounds and identity rules, not real world connectivity. */
public class RouteGraphTest {

    private final Object a = new Object();
    private final Object b = new Object();
    private final Object c = new Object();
    private final Topology topology = new Topology();

    @Test
    public void twoDrivesOnDifferentPeriodsStillFormAConfiguredCycle() {
        this.topology.drive(this.b, this.a);
        final RouteGraph<Object> graph = this.graph(this.b);
        finish(graph);
        assertTrue(graph.blocks(this.b));
        assertTrue(graph.isFresh());
    }

    @Test
    public void threeGridCycleIsDetectedWithoutInventoryContents() {
        this.topology.drive(this.b, this.c);
        this.topology.drive(this.c, this.a);
        final RouteGraph<Object> graph = this.graph(this.b);
        finish(graph);
        assertTrue(graph.blocks(this.b));
    }

    @Test
    public void acyclicDestinationsAndOtherDrivesRemainAllowed() {
        this.topology.drive(this.b, this.c);
        this.topology.drive(this.c);
        final RouteGraph<Object> graph = this.graph(this.b);
        finish(graph);
        assertFalse(graph.blocks(this.b));
        assertFalse(graph.isLimited());
    }

    @Test
    public void onlyCandidatesThatCanReturnToTheSourceAreBlocked() {
        this.topology.drive(this.b, this.a);
        final RouteGraph<Object> graph = this.graph(this.b, this.c);
        finish(graph);
        assertTrue(graph.blocks(this.b));
        assertFalse(graph.blocks(this.c));
    }

    @Test
    public void unrelatedCycleDoesNotBlockAPathThatCannotReturnToSource() {
        this.topology.drive(this.b, this.c);
        this.topology.drive(this.c, this.b);
        final RouteGraph<Object> graph = this.graph(this.b);
        finish(graph);
        assertFalse(graph.blocks(this.b));
    }

    @Test
    public void externalStorageWithoutAnObservableModDeviceIsNotClassifiedOrBanned() {
        final RouteGraph<Object> graph = this.graph(this.b);
        finish(graph);
        assertFalse(graph.blocks(this.b));
        assertEquals(0, this.topology.resolutions);
    }

    @Test
    public void gridIdentityIsNeverValueEquality() {
        final Object first = new String("equal");
        final Object second = new String("equal");
        this.topology.drive(first, second);
        final RouteGraph<Object> graph = new RouteGraph<>(
            second,
            Collections.singletonList(new RouteGraph.Link<>(first, () -> true)),
            this.topology);
        finish(graph);
        assertTrue(graph.blocks(first));
        assertFalse(graph.blocks(new String("equal")));
    }

    @Test
    public void splitMergeConfigurationAndUnloadInvalidateOldProofs() {
        final Drive drive = this.topology.drive(this.b, this.a);
        final RouteGraph<Object> graph = this.graph(this.b);
        finish(graph);
        drive.grid = this.c;
        assertFalse(graph.isFresh());
        drive.grid = this.b;
        drive.version++;
        assertFalse(graph.isFresh());
        drive.version--;
        assertTrue(graph.isFresh());
        this.topology.generation++;
        assertFalse(graph.isFresh());
    }

    @Test
    public void stalePermissionOrOfflineEndpointCannotRemainCycleEvidence() {
        final Drive drive = this.topology.drive(this.b, this.a);
        final RouteGraph<Object> graph = this.graph(this.b);
        finish(graph);
        drive.online.set(false);
        assertFalse(graph.isFresh());
        final RouteGraph<Object> refreshed = this.graph(this.b);
        finish(refreshed);
        assertFalse(refreshed.blocks(this.b));
    }

    @Test
    public void aNewDeviceOrRouteUsesANewGraphRatherThanHistoricGridIds() {
        final RouteGraph<Object> first = this.graph(this.b);
        finish(first);
        assertFalse(first.blocks(this.b));
        this.topology.drive(this.b, this.a);
        this.topology.generation++;
        assertFalse(first.isFresh());
        final RouteGraph<Object> second = this.graph(this.b);
        finish(second);
        assertTrue(second.blocks(this.b));
    }

    @Test
    public void oneStepNeverResolvesMoreThanOneInterface() {
        this.topology.drive(this.b, this.c, this.a);
        final RouteGraph<Object> graph = this.graph(this.b);
        for (int step = 0; step < 20 && !graph.isComplete(); step++) {
            final int previous = this.topology.resolutions;
            graph.step(1);
            assertTrue(this.topology.resolutions - previous <= 1);
        }
        assertTrue(graph.isComplete());
    }

    @Test
    public void sixtyFourIndependentTargetsAreWithinTheNormalGraphBound() {
        final Object[] grids = new Object[64];
        for (int index = 0; index < grids.length; index++) {
            grids[index] = new Object();
            this.topology.drive(grids[index]);
        }
        final RouteGraph<Object> graph = this.graph(grids);
        finish(graph);
        assertTrue(graph.isComplete());
        assertFalse(graph.isLimited());
    }

    @Test
    public void excessiveDeviceSetsStopWithinAHardBound() {
        for (int count = 0; count < RouteGraph.MAX_DEVICES + 1; count++) {
            this.topology.drive(this.b);
        }
        final RouteGraph<Object> graph = this.graph(this.b);
        finish(graph);
        assertTrue(graph.isLimited());
        assertFalse(graph.isComplete());
    }

    @Test
    public void excessiveEdgesOrBindingsStopWithoutAnUnboundedTraversal() {
        for (int count = 0; count < 9; count++) {
            final Object[] destinations = new Object[64];
            Arrays.fill(destinations, this.c);
            this.topology.drive(this.b, destinations);
        }
        final RouteGraph<Object> graph = this.graph(this.b);
        finish(graph);
        assertTrue(graph.isLimited());
        assertTrue(this.topology.resolutions <= RouteGraph.MAX_BINDINGS);
    }

    @Test
    public void multiSelectedCycleCandidatesStaySeparatedForAllThreeStorageTypes() {
        final TargetRegistry registry = new TargetRegistry();
        final TransferTarget returnsToSource = new TransferTarget(UUID.randomUUID(), 0, 0, 64, 0);
        final TransferTarget safe = new TransferTarget(UUID.randomUUID(), 0, 1, 64, 0);
        registry.register(returnsToSource);
        registry.register(safe);
        registry.setRoute(0, TargetRegistry.Route.specific(safe.getId()));
        registry.setRoute(1, TargetRegistry.Route.specific(returnsToSource.getId()));
        registry.setRoute(2, TargetRegistry.Route.specific(Arrays.asList(safe.getId(), returnsToSource.getId())));
        for (int type = 0; type < 3; type++) {
            final boolean[] mask = new boolean[8];
            mask[type] = true;
            final RouteGraph.Device<Object> device = new RouteGraph.Device<Object>() {

                @Override
                public Object sourceGrid() {
                    return b;
                }

                @Override
                public long revision() {
                    return 0;
                }

                @Override
                public List<TransferTarget> candidates() {
                    return registry.routedBindings(mask);
                }

                @Override
                public RouteGraph.Link<Object> resolve(TransferTarget target) {
                    return new RouteGraph.Link<>(
                        target.getId()
                            .equals(returnsToSource.getId()) ? a : c,
                        () -> true);
                }
            };
            this.topology.devices.put(this.b, Collections.singletonList(device));
            final RouteGraph<Object> graph = this.graph(this.b);
            finish(graph);
            assertEquals(type != 0, graph.blocks(this.b));
        }
    }

    private RouteGraph<Object> graph(final Object... destinations) {
        final List<RouteGraph.Link<Object>> roots = new ArrayList<>();
        for (final Object destination : destinations) {
            roots.add(new RouteGraph.Link<>(destination, () -> true));
        }
        return new RouteGraph<>(this.a, roots, this.topology);
    }

    private static void finish(final RouteGraph<?> graph) {
        for (int tick = 0; tick < 1025 && !graph.isComplete() && !graph.isLimited(); tick++) {
            graph.step(4);
        }
        assertTrue(graph.isComplete() || graph.isLimited());
    }

    private static final class Topology implements RouteGraph.Topology<Object> {

        long generation;
        int resolutions;
        final Map<Object, List<RouteGraph.Device<Object>>> devices = new IdentityHashMap<>();

        Drive drive(final Object grid, final Object... destinations) {
            final Drive drive = new Drive(this, grid, destinations);
            this.devices.computeIfAbsent(grid, ignored -> new ArrayList<>())
                .add(drive);
            return drive;
        }

        @Override
        public long generation() {
            return this.generation;
        }

        @Override
        public Iterator<RouteGraph.Device<Object>> devices(Object grid) {
            return this.devices.getOrDefault(grid, Collections.emptyList())
                .iterator();
        }
    }

    private static final class Drive implements RouteGraph.Device<Object> {

        final Topology topology;
        final List<TransferTarget> bindings = new ArrayList<>();
        final Map<UUID, Object> destinations = new java.util.HashMap<>();
        final AtomicBoolean online = new AtomicBoolean(true);
        Object grid;
        long version;

        Drive(Topology topology, Object grid, Object[] destinations) {
            this.topology = topology;
            this.grid = grid;
            for (int index = 0; index < destinations.length; index++) {
                final TransferTarget target = new TransferTarget(UUID.randomUUID(), 0, index, 64, 0);
                this.bindings.add(target);
                this.destinations.put(target.getId(), destinations[index]);
            }
        }

        @Override
        public Object sourceGrid() {
            return this.grid;
        }

        @Override
        public long revision() {
            return this.version;
        }

        @Override
        public List<TransferTarget> candidates() {
            return this.bindings;
        }

        @Override
        public RouteGraph.Link<Object> resolve(TransferTarget target) {
            this.topology.resolutions++;
            return new RouteGraph.Link<>(this.destinations.get(target.getId()), this.online::get);
        }
    }
}
