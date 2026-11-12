package com.kyou.gtnhmelogisticsenhancement.energy;

import static org.junit.Assert.*;

import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import org.junit.Assume;

/** Controlled availability/consumer timing, actual native stocks and the production cache/planner/settlement. */
public class MEEnergyPerformanceFixture extends MEEnergyAENetworkFixture {

    private static final class Rig {

        final Frame frame = new Frame();
        final IMEEnergyEndpoint[] sources, targets;
        final FakeNode[] nodes;
        final java.util.Map<IMEEnergyEndpoint, MEEnergyGridCache.Member> members;
        long initial, generated, consumed, net, gross, misses, minimum = Long.MAX_VALUE;
        final int mode;

        @SuppressWarnings("unchecked")
        Rig(final int devices, final int period, final int mode) throws Exception {
            this.mode = mode;
            this.frame.cache.shutdown();
            this.frame.cache = new MEEnergyGridCache(this.frame.grid, period == 0 ? 20 : period);
            this.members = (java.util.Map<IMEEnergyEndpoint, MEEnergyGridCache.Member>) field(
                MEEnergyGridCache.class,
                "byHost").get(this.frame.cache);
            final int pairs = devices / 2;
            this.sources = new IMEEnergyEndpoint[pairs];
            this.targets = new IMEEnergyEndpoint[pairs];
            this.nodes = new FakeNode[pairs];
            for (int i = 0; i < pairs; i++) {
                final int tier = mode == 1 ? 5 + i % 9 : 5;
                final int targetA = mode == 2 && i % 2 == 1 ? 16 : 4;
                final int sourceA = mode == 3 ? 4 : targetA == 16 ? 64 : 16;
                this.sources[i] = hatch(MEEnergySpec.Role.DYNAMO, tier, sourceA);
                this.targets[i] = hatch(MEEnergySpec.Role.ENERGY, tier, targetA);
                this.nodes[i] = this.frame.add(this.sources[i], mode == 1 ? i % 17 : 0);
                this.frame.add(this.targets[i], mode == 1 ? i % 17 : 0);
                this.sources[i].machine()
                    .setEUVar(
                        this.sources[i].spec()
                            .capacity());
                this.targets[i].machine()
                    .setEUVar(
                        this.targets[i].spec()
                            .capacity());
                this.initial += this.sources[i].readEU() + this.targets[i].readEU();
            }
            this.frame.cache.onUpdateTick();
        }

        void before(final int tick, final boolean boosted) throws Exception {
            advance(1);
            if (this.mode == 4 && tick % 17 == 0) {
                final int index = tick / 17 % this.sources.length;
                this.frame.cache.removeNode(this.nodes[index].node, this.sources[index]);
                this.nodes[index] = this.frame.add(this.sources[index], 0);
            }
            for (int i = 0; i < this.sources.length; i++) {
                final IMEEnergyEndpoint source = this.sources[i], target = this.targets[i];
                final long produced = Math.min(
                    source.spec()
                        .power(),
                    source.spec()
                        .capacity() - source.readEU());
                source.machine()
                    .setEUVar(source.readEU() + produced);
                this.generated += produced;
                final long request = this.mode == 5 ? 0
                    : target.spec()
                        .power() * (boosted ? 5 : 4)
                        / 4;
                final long have = target.readEU();
                if (have >= request) {
                    target.machine()
                        .setEUVar(have - request);
                    this.consumed += request;
                } else this.misses++;
                this.minimum = Math.min(this.minimum, target.readEU());
            }
        }

        void service(final int period) {
            for (final IMEEnergyEndpoint target : this.targets) target.nodeSupport()
                .pollDemandWake(MEEnergyTickClock.now());
            if (period != 0) this.frame.cache.onUpdateTick();
            else {
                // Favorable direct baseline: fixed indexed pairs, no matching search, same native gross checks.
                for (int i = 0; i < this.sources.length; i++) {
                    final IMEEnergyEndpoint source = this.sources[i], target = this.targets[i];
                    final MEEnergyGridCache.Member from = this.members.get(source), to = this.members.get(target);
                    if (from != null && to != null) this.frame.cache.transfers()
                        .commit(
                            this.frame.cache,
                            this.frame.cache.epoch(
                                source.spec()
                                    .tier()),
                            from,
                            to,
                            target.spec()
                                .capacity(),
                            MEEnergyTickClock.now());
                }
            }
        }

        void account(final long beforeSource, final long beforeTarget) {
            long afterSource = 0, afterTarget = 0;
            for (int i = 0; i < this.sources.length; i++) {
                afterSource += this.sources[i].readEU();
                afterTarget += this.targets[i].readEU();
                assertTrue(this.sources[i].readEU() >= 0);
                assertTrue(
                    this.targets[i].readEU() >= 0 && this.targets[i].readEU() <= this.targets[i].spec()
                        .capacity());
            }
            this.gross += beforeSource - afterSource;
            this.net += afterTarget - beforeTarget;
            assertEquals(
                this.initial + this.generated,
                afterSource + afterTarget + this.consumed + this.gross - this.net);
            assertTrue(this.frame.cache.maxTickWork() <= MEEnergyGridCache.WORK_PER_TICK);
        }

        long total(final boolean source) {
            long result = 0;
            for (final IMEEnergyEndpoint endpoint : source ? this.sources : this.targets) result += endpoint.readEU();
            return result;
        }
    }

    private static long run(final int period, final boolean boosted, final int devices) throws Exception {
        final Rig rig = new Rig(devices, period, 0);
        for (int tick = 0; tick < 240; tick++) {
            rig.before(tick, boosted);
            final long source = rig.total(true), target = rig.total(false);
            rig.service(period);
            rig.account(source, target);
        }
        rig.frame.cache.shutdown();
        System.out.println(
            "ME service comparison: P=" + period
                + " rate="
                + (boosted ? "125%" : "100%")
                + " devices="
                + devices
                + " missed="
                + rig.misses);
        return rig.misses;
    }

    public void periodComparisonExposes125PercentP20GapsAndValidatesP10() throws Exception {
        assertEquals(0, run(20, false, 10));
        assertTrue(run(20, true, 10) > 0);
        for (final int period : new int[] { 1, 5, 10 }) {
            assertEquals("P" + period, 0, run(period, true, 10));
        }
        assertEquals(0, run(10, true, 500));
    }

    public void internalPolicyRejectsInvalidPeriodsAndPublicDefaultIsFixed() {
        final Frame frame = new Frame();
        assertThrows(IllegalArgumentException.class, () -> new MEEnergyGridCache(frame.grid, 0));
        assertThrows(IllegalArgumentException.class, () -> new MEEnergyGridCache(frame.grid, 21));
        assertEquals(MEEnergyGridCache.SERVICE_PERIOD, new MEEnergyGridCache(frame.grid).servicePeriod());
    }

    public void optionalBenchmarkRecordsRealCacheCostsAndConservation() throws Exception {
        Assume.assumeTrue(
            "Set ME_ENERGY_BENCHMARK=1 for the controlled native-class benchmark",
            "1".equals(System.getenv("ME_ENERGY_BENCHMARK")));
        final String configuredOutput = System.getenv("ME_ENERGY_BENCHMARK_OUTPUT");
        final Path folder = Paths.get(configuredOutput == null ? "build/energy-optimization" : configuredOutput);
        Files.createDirectories(folder);
        final java.lang.management.ThreadMXBean raw = ManagementFactory.getThreadMXBean();
        final com.sun.management.ThreadMXBean allocations = raw instanceof com.sun.management.ThreadMXBean
            ? (com.sun.management.ThreadMXBean) raw
            : null;
        if (allocations != null && allocations.isThreadAllocatedMemorySupported()
            && !allocations.isThreadAllocatedMemoryEnabled()) allocations.setThreadAllocatedMemoryEnabled(true);
        try (PrintWriter output = new PrintWriter(
            Files.newBufferedWriter(folder.resolve("cache-benchmark.csv"), StandardCharsets.UTF_8))) {
            output.println(
                "devices,period,mode,p50_ns,p95_ns,p99_ns,max_ns,allocated_bytes,missed_requests,reads,writes,attempts,commits,refreshes,max_work,max_gap,expirations,gc_count,gc_ms,source_slots,target_slots,pending_slots,local_probes");
            for (final int devices : new int[] { 4, 10, 50, 200, 500 })
                for (final int period : new int[] { 0, 1, 5, 10, 20 }) for (int mode = 0; mode < 6; mode++) {
                    if (period == 0 && mode == 4) continue; // Direct baseline has no topology scheduler.
                    final Rig rig = new Rig(devices, period, mode);
                    final long[] times = new long[160];
                    long allocated = 0, beforeGc = gcCount(false), beforeGcMs = gcCount(true);
                    for (int tick = -80; tick < times.length; tick++) {
                        rig.before(tick + 80, false);
                        final long source = rig.total(true), target = rig.total(false);
                        final long startAllocated = allocated(allocations);
                        final long start = System.nanoTime();
                        rig.service(period);
                        final long elapsed = System.nanoTime() - start;
                        final long endAllocated = allocated(allocations);
                        if (tick >= 0) {
                            times[tick] = elapsed;
                            if (startAllocated >= 0) allocated += endAllocated - startAllocated;
                        }
                        rig.account(source, target);
                    }
                    Arrays.sort(times);
                    long maxGap = 0, expirations = 0;
                    int sourceSlots = 0, targetSlots = 0, pendingSlots = 0;
                    for (int tier = 5; tier <= 13; tier++) {
                        final MEEnergyGroupState group = rig.frame.cache.group(tier);
                        if (group != null) {
                            maxGap = Math.max(maxGap, group.maxServiceGap());
                            expirations += group.expirations();
                            sourceSlots += ((Object[]) field(MEEnergyGroupState.class, "sources").get(group)).length;
                            targetSlots += ((Object[]) field(MEEnergyGroupState.class, "targets").get(group)).length;
                            final Object plan = field(MEEnergyGroupState.class, "plan").get(group);
                            pendingSlots += ((int[]) field(MEEnergyEpochPlan.class, "pendingNext").get(plan)).length;
                        }
                    }
                    final MEEnergyTransfer transfers = rig.frame.cache.transfers();
                    output.println(
                        devices + ","
                            + period
                            + ","
                            + mode
                            + ","
                            + times[80]
                            + ","
                            + times[152]
                            + ","
                            + times[158]
                            + ","
                            + times[159]
                            + ","
                            + (allocated(allocations) < 0 ? -1 : allocated)
                            + ","
                            + rig.misses
                            + ","
                            + transfers.reads()
                            + ","
                            + transfers.writes()
                            + ","
                            + transfers.attempts()
                            + ","
                            + transfers.commits()
                            + ","
                            + rig.frame.cache.refreshes()
                            + ","
                            + rig.frame.cache.maxTickWork()
                            + ","
                            + maxGap
                            + ","
                            + expirations
                            + ","
                            + (gcCount(false) - beforeGc)
                            + ","
                            + (gcCount(true) - beforeGcMs)
                            + ","
                            + sourceSlots
                            + ","
                            + targetSlots
                            + ","
                            + pendingSlots
                            + ","
                            + rig.frame.cache.localDemandProbes());
                    output.flush();
                    rig.frame.cache.shutdown();
                }
        }
        final String metadata = "java=" + System.getProperty("java.runtime.version")
            + "\nvm="
            + System.getProperty("java.vm.name")
            + "\nos="
            + System.getProperty("os.name")
            + " "
            + System.getProperty("os.arch")
            + "\nprocessors="
            + Runtime.getRuntime()
                .availableProcessors()
            + "\nallocation_measurement="
            + (allocated(allocations) >= 0)
            + "\nME class fixtures; timed cache + local dormant probes, controlled AE availability, simulated consumers; NOT server MSPT/TPS.\n";
        Files.write(folder.resolve("cache-benchmark-environment.txt"), metadata.getBytes(StandardCharsets.UTF_8));
    }

    private static long allocated(final com.sun.management.ThreadMXBean bean) {
        return bean == null || !bean.isThreadAllocatedMemorySupported() ? -1
            : bean.getThreadAllocatedBytes(
                Thread.currentThread()
                    .getId());
    }

    private static long gcCount(final boolean time) {
        long total = 0;
        for (final java.lang.management.GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans())
            total += Math.max(0, time ? bean.getCollectionTime() : bean.getCollectionCount());
        return total;
    }
}
