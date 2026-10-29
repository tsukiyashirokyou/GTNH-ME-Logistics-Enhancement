package com.kyou.gtnhmelogisticsenhancement.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.Test;

/** Audit witnesses of current boundaries, not Minecraft timing or assertions that the boundaries are ideal. */
public class ReleaseRoutingAuditTest {

    @Test
    public void closedRoutesStillResolveEveryRegisteredInterfaceWhenACycleIsStarted() {
        final TargetRegistry registry = new TargetRegistry();
        for (int n = 0; n < TargetRegistry.MAX_TARGETS; n++) {
            registry.register(new TransferTarget(UUID.randomUUID(), 0, n, 64, 0));
        }
        final Object source = new Object();
        final Object destination = new Object();
        final int[] resolutions = { 0 };
        final RoutingCycle<Object> cycle = new RoutingCycle<>(registry, () -> source, target -> {
            resolutions[0]++;
            return new RoutingCycle.Endpoint<>(destination, () -> destination, () -> true);
        });
        int slices = 0;
        while (!cycle.isPrepared()) {
            cycle.prepare(4);
            slices++;
        }
        assertEquals(16, slices);
        assertEquals(64, resolutions[0]);
        for (int group = 0; group < 8; group++) {
            assertTrue(
                cycle.targetsForGroup(group)
                    .isEmpty());
        }
    }

    @Test
    public void newDeviceMembershipIsNotAPositiveWitnessUntilTheGraphIsRebuilt() {
        final Object source = new Object();
        final Object target = new Object();
        final List<RouteGraph.Device<Object>> machines = new ArrayList<>();
        final RouteGraph.Topology<Object> topology = new RouteGraph.Topology<Object>() {

            @Override
            public long generation() {
                return 0;
            }

            @Override
            public java.util.Iterator<RouteGraph.Device<Object>> devices(final Object grid) {
                return grid == target ? machines.iterator() : Collections.emptyIterator();
            }
        };
        final List<RouteGraph.Link<Object>> roots = Arrays.asList(new RouteGraph.Link<>(target, () -> true));
        final RouteGraph<Object> first = new RouteGraph<>(source, roots, topology);
        first.step(4);
        assertTrue(first.isComplete());
        assertFalse(first.blocks(target));
        machines.add(new RouteGraph.Device<Object>() {

            @Override
            public Object sourceGrid() {
                return target;
            }

            @Override
            public long revision() {
                return 0;
            }

            @Override
            public List<TransferTarget> candidates() {
                return Collections.singletonList(new TransferTarget(UUID.randomUUID(), 0, 0, 64, 0));
            }

            @Override
            public RouteGraph.Link<Object> resolve(final TransferTarget binding) {
                return new RouteGraph.Link<>(source, () -> true);
            }
        });
        // The controller's bounded TTL/rebuild is essential: the old positive witnesses cannot see a new member.
        assertTrue(first.isFresh());
        assertFalse(first.blocks(target));
        final RouteGraph<Object> rebuilt = new RouteGraph<>(source, roots, topology);
        rebuilt.step(16);
        assertTrue(rebuilt.isComplete());
        assertTrue(rebuilt.blocks(target));
    }
}
