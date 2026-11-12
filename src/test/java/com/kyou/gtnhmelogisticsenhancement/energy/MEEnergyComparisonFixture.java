package com.kyou.gtnhmelogisticsenhancement.energy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.Arrays;

/** Same bytecode/inputs run in isolated old/new production class loaders. No replacement service or EU account. */
public class MEEnergyComparisonFixture extends MEEnergyAENetworkFixture {

    private static final int WARMUP_TICKS = 2000;
    private static final int TIMED_TICKS = 2000;
    private static final boolean DEMAND_WAKE = demandWakePresent();
    private static final Method LOCAL_PROBES = localProbeMethod();

    public static String implementationLocation() {
        return MEEnergyGridCache.class.getProtectionDomain()
            .getCodeSource()
            .getLocation()
            .toExternalForm();
    }

    public static boolean hasDemandWake() {
        return DEMAND_WAKE;
    }

    public static boolean defaultIsWildcard() {
        return MEEnergyGroupPlanner.matches(0, 16);
    }

    private static boolean demandWakePresent() {
        try {
            MEEnergyNodeSupport.class.getDeclaredMethod("pollDemandWake", long.class);
            return true;
        } catch (NoSuchMethodException absentInBaseline) {
            return false;
        }
    }

    private static Method localProbeMethod() {
        try {
            final Method method = MEEnergyGridCache.class.getDeclaredMethod("localDemandProbes");
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException absentInBaseline) {
            return null;
        }
    }

    private static long probes(final MEEnergyGridCache cache) throws Exception {
        return LOCAL_PROBES == null ? 0 : (Long) LOCAL_PROBES.invoke(cache);
    }

    public String runComparison(final String scenario) throws Exception {
        final Rig rig = new Rig(scenario);
        final java.lang.management.ThreadMXBean raw = ManagementFactory.getThreadMXBean();
        final com.sun.management.ThreadMXBean allocations = raw instanceof com.sun.management.ThreadMXBean
            ? (com.sun.management.ThreadMXBean) raw
            : null;
        if (allocations != null && allocations.isThreadAllocatedMemorySupported()
            && !allocations.isThreadAllocatedMemoryEnabled()) allocations.setThreadAllocatedMemoryEnabled(true);
        for (int tick = 0; tick < WARMUP_TICKS; tick++) rig.tick(null, -1);
        final long warmupMissed = rig.misses;
        final MEEnergyTransfer transfers = rig.frame.cache.transfers();
        final long reads = transfers.reads(), writes = transfers.writes(), attempts = transfers.attempts();
        final long commits = transfers.commits(), activeQueries = rig.queries(true), gridQueries = rig.queries(false);
        final long localProbes = probes(rig.frame.cache);
        rig.misses = 0;
        final long[] times = new long[TIMED_TICKS];
        long allocated = 0;
        for (int tick = 0; tick < TIMED_TICKS; tick++) allocated += rig.tick(times, tick, allocations);
        long total = 0;
        for (final long elapsed : times) total += elapsed;
        Arrays.sort(times);
        assertEquals("Steady-state sufficient-supply requests: " + scenario, 0, rig.misses);
        assertEquals("Warmup starts precharged: " + scenario, 0, warmupMissed);
        rig.assertConservation();
        final String row = WARMUP_TICKS + ","
            + TIMED_TICKS
            + ","
            + total / TIMED_TICKS
            + ","
            + times[(TIMED_TICKS * 95 + 99) / 100 - 1]
            + ","
            + total
            + ","
            + (allocated(allocations) < 0 ? -1 : allocated)
            + ","
            + rig.misses
            + ","
            + warmupMissed
            + ","
            + (transfers.reads() - reads)
            + ","
            + (transfers.writes() - writes)
            + ","
            + (transfers.attempts() - attempts)
            + ","
            + (transfers.commits() - commits)
            + ","
            + (rig.queries(true) - activeQueries)
            + ","
            + (rig.queries(false) - gridQueries)
            + ","
            + (probes(rig.frame.cache) - localProbes)
            + ","
            + rig.frame.cache.maxTickWork();
        rig.frame.cache.shutdown();
        return row;
    }

    private static long allocated(final com.sun.management.ThreadMXBean bean) {
        return bean == null || !bean.isThreadAllocatedMemorySupported() ? -1
            : bean.getThreadAllocatedBytes(
                Thread.currentThread()
                    .getId());
    }

    private static final class Rig {

        final Frame frame = new Frame();
        final IMEEnergyEndpoint[] sources = new IMEEnergyEndpoint[250];
        final IMEEnergyEndpoint[] targets = new IMEEnergyEndpoint[250];
        final FakeNode[] nodes = new FakeNode[500];
        final boolean idle;
        final boolean boosted;
        long initial, generated, consumed, gross, net, misses;

        Rig(final String scenario) throws Exception {
            this.idle = "full_idle".equals(scenario);
            this.boosted = "active125".equals(scenario);
            final boolean mixed = "tiers_colors".equals(scenario);
            final boolean colored = mixed || "colors".equals(scenario);
            assertTrue(this.idle || this.boosted || colored || "active100".equals(scenario));
            this.frame.cache.shutdown();
            this.frame.cache = new MEEnergyGridCache(this.frame.grid, 10);
            for (int pair = 0; pair < this.sources.length; pair++) {
                final int tier = mixed ? 5 + pair % 9 : 5;
                final int color = colored ? pair % 17 : 0;
                final IMEEnergyEndpoint source = hatch(MEEnergySpec.Role.DYNAMO, tier, 16);
                final IMEEnergyEndpoint target = hatch(MEEnergySpec.Role.ENERGY, tier, 4);
                this.sources[pair] = source;
                this.targets[pair] = target;
                this.nodes[pair * 2] = this.frame.add(source, color);
                this.nodes[pair * 2 + 1] = this.frame.add(target, color);
                source.machine()
                    .setEUVar(
                        source.spec()
                            .capacity());
                target.machine()
                    .setEUVar(
                        target.spec()
                            .capacity());
                this.initial += source.readEU() + target.readEU();
            }
            this.frame.cache.onUpdateTick();
        }

        long tick(final long[] times, final int index) {
            return tick(times, index, null);
        }

        long tick(final long[] times, final int index, final com.sun.management.ThreadMXBean allocations) {
            advance(1);
            for (int pair = 0; pair < this.sources.length; pair++) {
                final IMEEnergyEndpoint source = this.sources[pair], target = this.targets[pair];
                final long sourceStock = source.readEU();
                final long produced = Math.min(
                    source.spec()
                        .power(),
                    source.spec()
                        .capacity() - sourceStock);
                source.machine()
                    .setEUVar(sourceStock + produced);
                this.generated += produced;
                final long request = this.idle ? 0
                    : target.spec()
                        .power() * (this.boosted ? 5 : 4)
                        / 4;
                final long targetStock = target.readEU();
                if (targetStock >= request) {
                    target.machine()
                        .setEUVar(targetStock - request);
                    this.consumed += request;
                } else this.misses++;
            }
            final long beforeSource = stock(true), beforeTarget = stock(false);
            final long startAllocated = times == null ? -1 : allocated(allocations);
            final long start = times == null ? 0 : System.nanoTime();
            if (DEMAND_WAKE) {
                // The branch is absent on the preserved implementation; no reflective calls in timing.
                for (final IMEEnergyEndpoint target : this.targets) target.nodeSupport()
                    .pollDemandWake(MEEnergyTickClock.now());
            }
            this.frame.cache.onUpdateTick();
            final long elapsed = times == null ? 0 : System.nanoTime() - start;
            final long endAllocated = times == null ? -1 : allocated(allocations);
            if (times != null) times[index] = elapsed;
            this.gross += beforeSource - stock(true);
            this.net += stock(false) - beforeTarget;
            return startAllocated < 0 ? 0 : endAllocated - startAllocated;
        }

        long stock(final boolean sourcesOnly) {
            long total = 0;
            for (final IMEEnergyEndpoint host : sourcesOnly ? this.sources : this.targets) total += host.readEU();
            return total;
        }

        long queries(final boolean active) {
            long total = 0;
            for (final FakeNode node : this.nodes) total += active ? node.activeQueries : node.gridQueries;
            return total;
        }

        void assertConservation() {
            assertEquals(
                this.initial + this.generated,
                stock(true) + stock(false) + this.consumed + this.gross - this.net);
            long credit = 0;
            for (final IMEEnergyEndpoint source : this.sources) {
                assertTrue(
                    source.readEU() >= source.spec()
                        .nativeReserve());
                assertTrue(
                    source.readEU() <= source.spec()
                        .capacity());
                credit += source.energyState()
                    .lossAccount()
                    .credit();
            }
            for (final IMEEnergyEndpoint target : this.targets) {
                assertTrue(target.readEU() >= 0);
                assertTrue(
                    target.readEU() <= target.spec()
                        .capacity());
            }
            assertEquals(credit, 19 * this.gross - 20 * this.net);
            assertTrue(this.frame.cache.maxTickWork() <= MEEnergyGridCache.WORK_PER_TICK);
        }
    }
}
