package com.kyou.metransferdrive.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public class RoutingCycleTest {

    private final TargetRegistry registry = new TargetRegistry();
    private final AtomicReference<Object> source = new AtomicReference<>(new Object());
    private final Map<UUID, AtomicReference<Object>> grids = new HashMap<>();
    private final AtomicInteger lookups = new AtomicInteger();

    @Test
    public void duplicateInterfacesCreateOneRuntimeDestinationWithoutErasingBindings() {
        final Object grid = new Object();
        final TransferTarget high = this.add(grid, 20);
        final TransferTarget low = this.add(grid, 0);
        this.registry.setRoute(0, TargetRegistry.Route.automatic());
        final RoutingCycle<Object> cycle = this.prepared();
        assertEquals(
            1,
            cycle.targetsForGroup(0)
                .size());
        assertEquals(
            high.getId(),
            cycle.targetsForGroup(0)
                .get(0)
                .getBinding()
                .getId());
        assertEquals(RoutingCycle.Status.DUPLICATE_GRID, cycle.getStatus(low.getId()));
        assertEquals(
            2,
            this.registry.getTargets()
                .size());
    }

    @Test
    public void splitRebuildsTwoDestinationsFromTheOriginalBindingsNextCycle() {
        final Object joined = new Object();
        this.add(joined, 10);
        final TransferTarget other = this.add(joined, 0);
        this.registry.setRoute(0, TargetRegistry.Route.automatic());
        final RoutingCycle<Object> previous = this.prepared();
        this.grids.get(other.getId())
            .set(new Object());
        assertFalse(previous.isTopologyValid());
        assertEquals(
            2,
            this.prepared()
                .targetsForGroup(0)
                .size());
        assertEquals(
            2,
            this.registry.getTargets()
                .size());
    }

    @Test
    public void mergeInvalidatesTheOldCycleEvenWhenTheNextDestinationRetainsItsGridObject() {
        final Object survivor = new Object();
        final TransferTarget first = this.add(new Object(), 10);
        this.add(survivor, 0);
        this.registry.setRoute(0, TargetRegistry.Route.automatic());
        final RoutingCycle<Object> previous = this.prepared();
        this.grids.get(first.getId())
            .set(survivor);
        assertFalse(previous.isTopologyValid());
        assertEquals(
            1,
            this.prepared()
                .targetsForGroup(0)
                .size());
        assertEquals(
            2,
            this.registry.getTargets()
                .size());
    }

    @Test
    public void equalityAndHashCodesCannotCollapseDifferentGridObjects() {
        this.add(new EqualGrid(), 0);
        this.add(new EqualGrid(), 0);
        this.registry.setRoute(0, TargetRegistry.Route.automatic());
        assertEquals(
            2,
            this.prepared()
                .targetsForGroup(0)
                .size());
    }

    @Test
    public void sourceGridIsRejectedByIdentity() {
        final TransferTarget self = this.add(this.source.get(), 0);
        this.registry.setRoute(0, TargetRegistry.Route.automatic());
        final RoutingCycle<Object> cycle = this.prepared();
        assertTrue(
            cycle.targetsForGroup(0)
                .isEmpty());
        assertEquals(RoutingCycle.Status.OFFLINE_OR_FORBIDDEN, cycle.getStatus(self.getId()));
    }

    @Test
    public void unassignedAutomaticAndSpecifiedGroupsAreIndependent() {
        final TransferTarget first = this.add(new Object(), 10);
        final TransferTarget second = this.add(new Object(), 0);
        this.registry.setRoute(0, TargetRegistry.Route.automatic());
        this.registry.setRoute(1, TargetRegistry.Route.specific(second.getId()));
        final RoutingCycle<Object> cycle = this.prepared();
        assertEquals(
            2,
            cycle.targetsForGroup(0)
                .size());
        assertEquals(
            second.getId(),
            cycle.targetsForGroup(1)
                .get(0)
                .getBinding()
                .getId());
        assertTrue(
            cycle.targetsForGroup(2)
                .isEmpty());
        assertEquals(
            first.getId(),
            cycle.targetsForGroup(0)
                .get(0)
                .getBinding()
                .getId());
    }

    @Test
    public void specifiedDuplicateStillResolvesItsOwnBindingWithoutFallback() {
        final Object same = new Object();
        this.add(same, 10);
        final TransferTarget selected = this.add(same, 0);
        this.registry.setRoute(0, TargetRegistry.Route.specific(selected.getId()));
        assertEquals(
            selected.getId(),
            this.prepared()
                .targetsForGroup(0)
                .get(0)
                .getBinding()
                .getId());
    }

    @Test
    public void offlineBindingIsSkippedWithoutDestroyingItsConfiguration() {
        final TransferTarget offline = this.add(null, 10);
        final TransferTarget online = this.add(new Object(), 0);
        this.registry.setRoute(0, TargetRegistry.Route.automatic());
        this.registry.setRoute(1, TargetRegistry.Route.specific(offline.getId()));
        final RoutingCycle<Object> cycle = this.prepared();
        assertEquals(
            1,
            cycle.targetsForGroup(0)
                .size());
        assertEquals(
            online.getId(),
            cycle.targetsForGroup(0)
                .get(0)
                .getBinding()
                .getId());
        assertTrue(
            cycle.targetsForGroup(1)
                .isEmpty());
        assertEquals(
            2,
            this.registry.getTargets()
                .size());
    }

    @Test
    public void resolutionHonorsThePerTickBudgetAndDoesNotResolveAgainPerType() {
        for (int index = 0; index < 9; index++) {
            this.add(new Object(), 0);
        }
        final RoutingCycle<Object> cycle = this.cycle();
        assertFalse(cycle.prepare(4));
        assertEquals(4, this.lookups.get());
        assertFalse(cycle.prepare(4));
        assertTrue(cycle.prepare(4));
        assertEquals(9, this.lookups.get());
        cycle.prepare(4);
        cycle.targetsForGroup(0);
        cycle.targetsForGroup(1);
        assertEquals(9, this.lookups.get());
    }

    @Test
    public void prioritiesAndGroupSelectionsAreSnapshotsForTheCycle() {
        final TransferTarget first = this.add(new Object(), 10);
        final TransferTarget second = this.add(new Object(), 0);
        this.registry.setRoute(0, TargetRegistry.Route.automatic());
        final RoutingCycle<Object> previous = this.prepared();
        this.registry.updateMetadata(second.getId(), "Now preferred", 20);
        assertSame(
            first,
            previous.targetsForGroup(0)
                .get(0)
                .getBinding());
        assertEquals(
            second.getId(),
            this.prepared()
                .targetsForGroup(0)
                .get(0)
                .getBinding()
                .getId());
        this.registry.setRoute(0, TargetRegistry.Route.unassigned());
        assertEquals(
            2,
            previous.targetsForGroup(0)
                .size());
    }

    @Test
    public void changingTheSourceGridInvalidatesTheCycle() {
        this.add(new Object(), 0);
        final RoutingCycle<Object> cycle = this.prepared();
        this.source.set(new Object());
        assertFalse(cycle.isTopologyValid());
    }

    private TransferTarget add(final Object grid, final int priority) {
        final TransferTarget binding = new TransferTarget(
            UUID.randomUUID(),
            0,
            this.grids.size(),
            64,
            0,
            "Interface",
            priority);
        this.registry.register(binding);
        this.grids.put(binding.getId(), new AtomicReference<>(grid));
        return binding;
    }

    private RoutingCycle<Object> cycle() {
        return new RoutingCycle<>(this.registry, this.source::get, binding -> {
            this.lookups.incrementAndGet();
            final AtomicReference<Object> reference = this.grids.get(binding.getId());
            return reference.get() == null ? null
                : new RoutingCycle.Endpoint<>(reference.get(), reference::get, () -> true);
        });
    }

    private RoutingCycle<Object> prepared() {
        final RoutingCycle<Object> cycle = this.cycle();
        while (!cycle.prepare(4)) {
            // Ordinary JVM model only; production calls prepare once per tick.
        }
        return cycle;
    }

    private static final class EqualGrid {

        @Override
        public boolean equals(final Object other) {
            return other instanceof EqualGrid;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }
}
