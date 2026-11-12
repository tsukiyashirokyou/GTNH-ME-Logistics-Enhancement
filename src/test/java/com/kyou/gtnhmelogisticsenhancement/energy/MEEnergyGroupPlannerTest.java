package com.kyou.gtnhmelogisticsenhancement.energy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.math.BigInteger;
import java.util.Random;

import org.junit.Test;

public class MEEnergyGroupPlannerTest {

    @Test
    public void everyColorIncludingDefaultMatchesOnlyItself() {
        for (int source = 0; source < 17; source++) {
            for (int target = 0; target < 17; target++)
                assertEquals(source == target, MEEnergyGroupPlanner.matches(source, target));
        }
    }

    @Test
    public void defaultCannotSupplyOrDrainAnotherColorsPool() {
        final MEEnergyGroupPlanner graph = new MEEnergyGroupPlanner();
        graph.addSource(0, 10);
        graph.addSource(1, 7);
        graph.noteDemand(20);
        graph.sealSources();
        assertEquals(0, graph.request(2, 10));
        assertEquals(10, graph.request(0, 20));
        assertEquals(0, graph.request(0, 20)); // Red's seven EU cannot be drained by default.
        assertEquals(7, graph.request(1, 20));
        graph.freeze();
        assertEquals(10, graph.flowLimit(0, 0, 100));
        assertEquals(7, graph.flowLimit(1, 1, 100));
        assertEquals(0, graph.flowLimit(0, 2, 100));
        assertEquals(0, graph.flowLimit(1, 0, 100));
        assertEquals(BigInteger.valueOf(17), graph.totalGranted());
    }

    @Test
    public void partialRequestsCannotTakeUnrelatedSupplyOrAlterPriorReservations() {
        final MEEnergyGroupPlanner graph = new MEEnergyGroupPlanner();
        graph.addSource(0, 3);
        graph.addSource(1, 7);
        graph.sealSources();
        assertEquals(3, graph.request(0, 10));
        assertEquals(0, graph.request(2, 10));
        assertTrue(graph.hasRemainingSupply());
        assertEquals(7, graph.request(1, 10));
        assertFalse(graph.hasRemainingSupply());
        graph.freeze();
        graph.consumeFlow(0, 0, 1);
        assertEquals(2, graph.flowLimit(0, 0, 100));
        assertEquals(7, graph.flowLimit(1, 1, 100));
        assertEquals(BigInteger.TEN, graph.totalGranted());
    }

    @Test
    public void randomizedPoolsMatchIndependentBipartiteMinCutOracle() {
        final Random random = new Random(17036);
        final MEEnergyGroupPlanner graph = new MEEnergyGroupPlanner();
        for (int sample = 0; sample < 1000; sample++) {
            graph.reset();
            final long[] supply = new long[17];
            for (int source = 0; source < 30; source++) {
                final int color = random.nextInt(17);
                final int amount = random.nextInt(21);
                supply[color] += amount;
                graph.addSource(color, amount);
            }
            final int count = 1 + random.nextInt(6);
            final int[] colors = new int[count];
            final long[] demand = new long[count];
            for (int target = 0; target < count; target++) {
                colors[target] = random.nextInt(17);
                demand[target] = random.nextInt(31);
                graph.noteDemand(colors[target], demand[target]);
            }
            graph.sealSources();
            long actual = 0;
            for (int target = 0; target < count; target++) actual += graph.request(colors[target], demand[target]);
            assertEquals("sample " + sample, minCutOracle(supply, colors, demand), actual);
            assertEquals(0, graph.edgeVisits());
            graph.freeze();
            long finalFlow = 0;
            for (int source = 0; source < 17; source++) {
                long used = 0;
                for (int target = 0; target < 17; target++) {
                    final long flow = graph.flowLimit(source, target, Long.MAX_VALUE);
                    if (source != target) assertEquals(0, flow);
                    used += flow;
                }
                assertTrue(used <= supply[source]);
                finalFlow += used;
            }
            assertEquals(actual, finalFlow);
        }
    }

    /** Hall deficiency/min-cut over target subsets, independent of the production quota counters. */
    static long minCutOracle(final long[] supply, final int[] colors, final long[] demand) {
        long total = 0;
        for (final long amount : demand) total += amount;
        long largestDeficit = 0;
        for (int subset = 0; subset < 1 << demand.length; subset++) {
            long needed = 0;
            int neighbors = 0;
            for (int target = 0; target < demand.length; target++) {
                if ((subset & 1 << target) == 0) continue;
                needed += demand[target];
                neighbors |= 1 << colors[target];
            }
            long available = 0;
            for (int source = 0; source < 17; source++) {
                if ((neighbors & 1 << source) != 0) available += supply[source];
            }
            largestDeficit = Math.max(largestDeficit, needed - available);
        }
        return total - largestDeficit;
    }

    @Test
    public void highTierAggregatePromotesAndStreamsLongFragmentsWithoutCrossColorFlow() {
        final MEEnergyGroupPlanner graph = new MEEnergyGroupPlanner();
        final long capacity = 216172782113783808L;
        final BigInteger total = BigInteger.valueOf(capacity)
            .multiply(BigInteger.valueOf(43));
        for (int source = 0; source < 43; source++) graph.addSource(1, capacity);
        graph.noteDemand(Long.MAX_VALUE);
        graph.noteDemand(Long.MAX_VALUE);
        assertTrue(graph.usesWideArithmetic());
        graph.sealSources();
        assertEquals(0, graph.request(0, Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, graph.request(1, Long.MAX_VALUE));
        final long rest = total.subtract(BigInteger.valueOf(Long.MAX_VALUE))
            .longValueExact();
        assertEquals(rest, graph.request(1, Long.MAX_VALUE));
        graph.freeze();
        assertEquals(total, graph.totalGranted());
        assertEquals(0, graph.flowLimit(1, 0, Long.MAX_VALUE));
        graph.consumeFlow(1, 1, Long.MAX_VALUE);
        assertEquals(rest, graph.flowLimit(1, 1, Long.MAX_VALUE));
        graph.consumeFlow(1, 1, rest);
        assertEquals(0, graph.flowLimit(1, 1, 1));
        graph.reset();
        assertFalse(graph.usesWideArithmetic());
        graph.addSource(3, 2);
        graph.sealSources();
        assertEquals(2, graph.request(3, 10));
        graph.freeze();
        assertEquals(0, graph.flowLimit(1, 1, 100));
    }

    @Test
    public void demandOverflowPreservesColorIsolationAndExistingPromotionTrigger() {
        final MEEnergyGroupPlanner graph = new MEEnergyGroupPlanner();
        graph.addSource(0, 10);
        graph.noteDemand(Long.MAX_VALUE);
        graph.noteDemand(1);
        graph.addSource(1, 10);
        graph.sealSources();
        assertTrue(graph.usesWideArithmetic());
        assertEquals(10, graph.request(0, 20));
        assertEquals(0, graph.request(0, 10));
        assertEquals(0, graph.request(2, 10));
        assertEquals(10, graph.request(1, 10));
        assertTrue(graph.usesDirectQuotas());
        assertEquals(0, graph.edgeVisits());
    }

    @Test
    public void numericalMagnitudeDoesNotCreatePerEuLoops() {
        for (final long amount : new long[] { 1, Long.MAX_VALUE }) {
            final MEEnergyGroupPlanner graph = new MEEnergyGroupPlanner();
            graph.addSource(16, amount);
            graph.sealSources();
            assertEquals(amount, graph.request(16, amount));
            assertEquals(1, graph.augmentations());
            assertEquals(0, graph.edgeVisits());
            graph.freeze();
            assertEquals(amount, graph.flowLimit(16, 16, amount));
        }
    }

    @Test
    public void phasesAndInvalidColorsFailWithoutChangingFinalQuotas() {
        final MEEnergyGroupPlanner graph = new MEEnergyGroupPlanner();
        assertThrows(IllegalArgumentException.class, () -> graph.addSource(17, 1));
        assertThrows(IllegalArgumentException.class, () -> graph.addSource(0, -1));
        assertThrows(IllegalStateException.class, () -> graph.request(0, 1));
        graph.addSource(1, 5);
        graph.sealSources();
        assertThrows(IllegalStateException.class, () -> graph.addSource(1, 1));
        assertFalse(graph.canRouteTo(0));
        assertFalse(graph.canRouteTo(2));
        assertEquals(5, graph.request(1, 10));
        graph.freeze();
        assertThrows(IllegalStateException.class, () -> graph.request(1, 1));
        assertThrows(IllegalArgumentException.class, () -> graph.consumeFlow(1, 1, 6));
        assertThrows(IllegalArgumentException.class, () -> graph.consumeFlow(1, 0, 1));
        assertEquals(5, graph.flowLimit(1, 1, 100));
    }

    @Test
    public void undeclaredColorsNeverNeedFallbackOrAccessAnotherPool() {
        final MEEnergyGroupPlanner graph = new MEEnergyGroupPlanner();
        graph.addSource(1, 7);
        graph.addSource(2, 11);
        graph.noteDemand(1, 4);
        graph.noteDemand(2, 20);
        graph.sealSources();
        assertEquals(4, graph.request(1, 4));
        assertFalse(graph.canRouteTo(0));
        assertEquals(0, graph.request(0, 7));
        assertEquals(11, graph.request(2, 20));
        assertTrue(graph.usesDirectQuotas());
        graph.freeze();
        assertEquals(4, graph.flowLimit(1, 1, 100));
        assertEquals(11, graph.flowLimit(2, 2, 100));
    }

    @Test
    public void longAndWidePoolsAgreeOnEveryRequestAndFragmentAcrossAllColors() {
        final Random random = new Random(367104);
        for (int sample = 0; sample < 450; sample++) {
            final MEEnergyGroupPlanner direct = new MEEnergyGroupPlanner();
            final MEEnergyGroupPlanner wide = new MEEnergyGroupPlanner();
            for (int color = 0; color < 17; color++) {
                final long amount = random.nextInt(1000);
                direct.addSource(color, amount);
                wide.addSource(color, amount);
            }
            wide.noteDemand(Long.MAX_VALUE);
            wide.noteDemand(1);
            direct.sealSources();
            wide.sealSources();
            for (int target = 0; target < 30; target++) {
                final int color = random.nextInt(17);
                final long amount = random.nextInt(1000);
                assertEquals(wide.canRouteTo(color), direct.canRouteTo(color));
                assertEquals(wide.request(color, amount), direct.request(color, amount));
                assertEquals(wide.hasRemainingSupply(), direct.hasRemainingSupply());
            }
            assertEquals(wide.totalGranted(), direct.totalGranted());
            direct.freeze();
            wide.freeze();
            for (int source = 0; source < 17; source++) {
                for (int target = 0; target < 17; target++) {
                    final long flow = wide.flowLimit(source, target, Long.MAX_VALUE);
                    assertEquals(flow, direct.flowLimit(source, target, Long.MAX_VALUE));
                    direct.consumeFlow(source, target, flow / 2);
                    assertEquals(flow - flow / 2, direct.flowLimit(source, target, Long.MAX_VALUE));
                    direct.consumeFlow(source, target, flow - flow / 2);
                    assertEquals(0, direct.flowLimit(source, target, Long.MAX_VALUE));
                }
            }
        }
    }
}
