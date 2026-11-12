package com.kyou.gtnhmelogisticsenhancement.energy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.kyou.gtnhmelogisticsenhancement.energy.MEEnergySpec.Role;

import appeng.api.networking.events.MENetworkBootingStatusChange;
import appeng.api.networking.events.MENetworkPowerStatusChange;

/** Native GT EU balances and production cache; Frame rejects all whole-Grid traversal. */
public class MEEnergyOptimizationFixture extends MEEnergyTransferFixture {

    /** Independent native-stock oracle; external producer refills are outside the measured service. */
    private static final class Ledger {

        private final List<IMEEnergyEndpoint> sources;
        private final List<IMEEnergyEndpoint> targets;
        private final long initialTargetStock;
        private final long initialCredit;
        private BigInteger gross = BigInteger.ZERO;
        private BigInteger net = BigInteger.ZERO;
        private long consumed;
        private long removed;

        private Ledger(final List<IMEEnergyEndpoint> sources, final List<IMEEnergyEndpoint> targets) {
            this.sources = sources;
            this.targets = targets;
            this.initialTargetStock = stock(targets);
            this.initialCredit = credits(sources);
        }

        private static long stock(final List<IMEEnergyEndpoint> hosts) {
            long result = 0;
            for (final IMEEnergyEndpoint host : hosts) result = Math.addExact(result, host.readEU());
            return result;
        }

        private static long credits(final List<IMEEnergyEndpoint> sources) {
            long result = 0;
            for (final IMEEnergyEndpoint source : sources) result += source.energyState()
                .lossAccount()
                .credit();
            return result;
        }

        private boolean consume(final IMEEnergyEndpoint target) {
            if (!MEEnergyOptimizationFixture.consume(target)) return false;
            this.consumed = Math.addExact(this.consumed, draw(target));
            return true;
        }

        private void removeAll(final IMEEnergyEndpoint target) {
            this.removed = Math.addExact(this.removed, target.readEU());
            target.machine()
                .setEUVar(0);
        }

        private void service(final Frame frame) {
            final long sourceBefore = stock(this.sources), targetBefore = stock(this.targets);
            frame.cache.onUpdateTick();
            final long debit = sourceBefore - stock(this.sources), admitted = stock(this.targets) - targetBefore;
            assertTrue(debit >= 0);
            assertTrue(admitted >= 0);
            this.gross = this.gross.add(BigInteger.valueOf(debit));
            this.net = this.net.add(BigInteger.valueOf(admitted));
        }

        private void assertConserved() {
            assertEquals(
                BigInteger.valueOf(credits(this.sources) - this.initialCredit),
                this.gross.multiply(BigInteger.valueOf(19))
                    .subtract(this.net.multiply(BigInteger.valueOf(20))));
            assertEquals(
                BigInteger.valueOf(stock(this.targets)),
                BigInteger.valueOf(this.initialTargetStock)
                    .add(this.net)
                    .subtract(BigInteger.valueOf(this.consumed))
                    .subtract(BigInteger.valueOf(this.removed)));
        }
    }

    private static Frame frame(final int phase) {
        final Frame result = new Frame();
        result.cache = new MEEnergyGridCache(result.grid, 10, phase);
        return result;
    }

    private static IMEEnergyEndpoint add(final Frame frame, final Role role, final int tier, final int amps,
        final boolean full) throws Exception {
        final IMEEnergyEndpoint host = hatch(role, tier, amps);
        frame.add(host, 0);
        if (full) host.machine()
            .setEUVar(
                host.spec()
                    .capacity());
        return host;
    }

    private static void poll(final List<IMEEnergyEndpoint> targets) {
        final long now = MEEnergyTickClock.now();
        for (final IMEEnergyEndpoint target : targets) target.nodeSupport()
            .pollDemandWake(now);
    }

    private static void refill(final List<IMEEnergyEndpoint> sources) {
        for (final IMEEnergyEndpoint source : sources) source.machine()
            .setEUVar(
                source.spec()
                    .capacity());
    }

    private static long draw(final IMEEnergyEndpoint target) {
        final long power = target.spec()
            .power();
        return power + power / 4;
    }

    private static boolean consume(final IMEEnergyEndpoint target) {
        final long required = draw(target);
        final long raw = target.readEU();
        if (raw < required) return false;
        target.machine()
            .setEUVar(raw - required);
        return true;
    }

    private static void bounds(final IMEEnergyEndpoint host) {
        final long raw = host.readEU();
        assertTrue(raw >= 0);
        assertTrue(
            raw <= host.spec()
                .capacity());
    }

    private static void warm(final Frame frame, final List<IMEEnergyEndpoint> targets, final int ticks) {
        for (int tick = 0; tick < ticks; tick++) {
            advance(1);
            poll(targets);
            frame.cache.onUpdateTick();
        }
    }

    public void dirtyRefreshAndRepeatedNetworkEventsRemainBounded() throws Exception {
        final Frame frame = frame(0);
        final List<FakeNode> nodes = new ArrayList<>();
        for (int index = 0; index < 900; index++) nodes.add(frame.add(hatch(Role.DYNAMO, 5, 4), 0));
        final long before = frame.cache.refreshes();
        frame.cache.onUpdateTick();
        assertTrue(frame.cache.refreshes() - before <= 128);
        assertTrue(frame.cache.refreshes() - before > 0);
        for (int tick = 0; tick < 12; tick++) {
            advance(1);
            frame.cache.onUpdateTick();
        }
        assertEquals(900, frame.cache.activeCount(Role.DYNAMO, 5, 0));
        final long refreshed = frame.cache.refreshes();
        int queries = 0;
        for (final FakeNode node : nodes) queries += node.activeQueries;
        for (int event = 0; event < 100; event++) frame.cache.powerChanged(new MENetworkPowerStatusChange());
        int afterQueries = 0;
        for (final FakeNode node : nodes) afterQueries += node.activeQueries;
        assertEquals("A cache-wide notification must not query each node", queries, afterQueries);
        advance(1);
        frame.cache.onUpdateTick();
        assertEquals(
            "Repeated unchanged network events need no membership refresh",
            refreshed,
            frame.cache.refreshes());
        assertTrue(frame.cache.maxTickWork() <= MEEnergyGridCache.WORK_PER_TICK);
    }

    public void coalescedOfflineEventsPreserveEarnedTokensWithoutCatchup() throws Exception {
        final Frame frame = frame(0);
        final IMEEnergyEndpoint source = add(frame, Role.DYNAMO, 5, 4, true);
        frame.cache.onUpdateTick();
        advance(3);
        final long earned = source.energyState()
            .rateBudget()
            .tokens(MEEnergyTickClock.now());
        assertEquals(
            3 * source.spec()
                .power(),
            earned);
        frame.powered = false;
        for (int event = 0; event < 20; event++) frame.cache.powerChanged(new MENetworkPowerStatusChange());
        advance(30);
        frame.booting = true;
        frame.cache.bootChanged(new MENetworkBootingStatusChange(true));
        frame.cache.onUpdateTick();
        advance(30);
        assertEquals(
            earned,
            source.energyState()
                .rateBudget()
                .tokens(MEEnergyTickClock.now()));
        frame.powered = true;
        frame.cache.powerChanged(new MENetworkPowerStatusChange());
        advance(30);
        frame.booting = false;
        for (int event = 0; event < 20; event++) frame.cache.bootChanged(new MENetworkBootingStatusChange(false));
        assertEquals(
            earned,
            source.energyState()
                .rateBudget()
                .tokens(MEEnergyTickClock.now()));
        advance(4);
        assertEquals(
            earned + 4 * source.spec()
                .power(),
            source.energyState()
                .rateBudget()
                .tokens(MEEnergyTickClock.now()));
    }

    public void hundredsOfFullTargetsSleepAndWakeWithoutAePollingOrPowerLoss() throws Exception {
        final Frame frame = frame(7);
        final List<IMEEnergyEndpoint> sources = new ArrayList<>(), targets = new ArrayList<>();
        final List<FakeNode> nodes = new ArrayList<>();
        sources.add(add(frame, Role.DYNAMO, 5, 16, true));
        for (int index = 0; index < 400; index++) {
            final IMEEnergyEndpoint target = hatch(Role.ENERGY, 5, 4);
            nodes.add(frame.add(target, 0));
            target.machine()
                .setEUVar(
                    target.spec()
                        .capacity());
            targets.add(target);
        }
        warm(frame, targets, 30);
        assertEquals(400, frame.cache.dormantCount());
        final int[] activeQueries = new int[nodes.size()];
        for (int index = 0; index < nodes.size(); index++) activeQueries[index] = nodes.get(index).activeQueries;
        final long reads = frame.cache.transfers()
            .reads();
        final long probes = frame.cache.localDemandProbes();
        warm(frame, targets, 40);
        for (int index = 0; index < nodes.size(); index++)
            assertEquals(activeQueries[index], nodes.get(index).activeQueries);
        assertEquals(
            "An all-dormant group must skip network inventory snapshots",
            reads,
            frame.cache.transfers()
                .reads());
        assertTrue(frame.cache.localDemandProbes() > probes);
        assertTrue(frame.cache.localDemandProbes() - probes <= 400L * 11);
        int misses = 0;
        for (int tick = 0; tick < 120; tick++) {
            advance(1);
            refill(sources);
            if (!consume(targets.get(0))) misses++;
            poll(targets);
            frame.cache.onUpdateTick();
            bounds(targets.get(0));
        }
        assertEquals("A full dormant target must wake before its 125% reserve is exhausted", 0, misses);
        for (int index = 1; index < nodes.size(); index++)
            assertEquals(activeQueries[index], nodes.get(index).activeQueries);
        assertTrue(frame.cache.maxTickWork() <= MEEnergyGridCache.WORK_PER_TICK);
        assertEquals(
            0,
            frame.cache.group(5)
                .expirations());
    }

    public void explicitGridPhasesDistributeServiceAndKeep125PercentLoadsPowered() throws Exception {
        final List<Frame> frames = new ArrayList<>();
        final List<IMEEnergyEndpoint> sources = new ArrayList<>(), targets = new ArrayList<>();
        for (int phase = 0; phase < 10; phase++) {
            final Frame frame = frame(phase);
            assertEquals(phase, frame.cache.phaseOffset());
            frames.add(frame);
            sources.add(add(frame, Role.DYNAMO, 5, 16, true));
            targets.add(add(frame, Role.ENERGY, 5, 4, true));
        }
        for (int tick = 0; tick < 30; tick++) {
            advance(1);
            poll(targets);
            for (final Frame frame : frames) frame.cache.onUpdateTick();
        }
        final Set<Long> serviceTicks = new HashSet<>();
        int misses = 0;
        for (int tick = 0; tick < 120; tick++) {
            advance(1);
            refill(sources);
            for (final IMEEnergyEndpoint target : targets) if (!consume(target)) misses++;
            poll(targets);
            for (final Frame frame : frames) frame.cache.onUpdateTick();
            if (tick >= 100) for (final Frame frame : frames) if (frame.cache.group(5)
                .lastServiceTick() == MEEnergyTickClock.now()) serviceTicks.add(MEEnergyTickClock.now() % 10);
        }
        assertEquals(0, misses);
        assertEquals(
            "Ten explicit grid phases must spread small-group service over all ten ticks",
            10,
            serviceTicks.size());
        for (final Frame frame : frames) assertEquals(
            0,
            frame.cache.group(5)
                .expirations());
    }

    public void nineTiersWithFragmentedSourcesSustain125PercentDemand() throws Exception {
        final Frame frame = frame(3);
        final List<IMEEnergyEndpoint> sources = new ArrayList<>(), targets = new ArrayList<>();
        for (int tier = 5; tier <= 13; tier++) {
            // 128A gross > (4+16+64)A * 125% / 95%; the 64A target needs several sources.
            for (int source = 0; source < 8; source++) sources.add(add(frame, Role.DYNAMO, tier, 16, true));
            for (final int amps : new int[] { 4, 16, 64 }) targets.add(add(frame, Role.ENERGY, tier, amps, true));
        }
        warm(frame, targets, 30);
        int misses = 0;
        for (int tick = 0; tick < 600; tick++) {
            advance(1);
            refill(sources);
            for (final IMEEnergyEndpoint target : targets) if (!consume(target)) misses++;
            poll(targets);
            frame.cache.onUpdateTick();
            for (final IMEEnergyEndpoint target : targets) bounds(target);
        }
        assertEquals(
            "Adequate per-tier gross supply must survive shared-budget scheduling and fragmentation",
            0,
            misses);
        for (int tier = 5; tier <= 13; tier++) assertEquals(
            0,
            frame.cache.group(tier)
                .expirations());
        assertTrue(frame.cache.maxTickWork() <= MEEnergyGridCache.WORK_PER_TICK);
    }

    /** Cold precharge is lower priority than an observed consumer's measured draw and bounded reserve. */
    public void coldIdleRefillCannotInterruptAnObservedRunningConsumer() throws Exception {
        final Frame frame = frame(0);
        final List<IMEEnergyEndpoint> sources = new ArrayList<>(), targets = new ArrayList<>();
        sources.add(add(frame, Role.DYNAMO, 5, 16, true));
        final IMEEnergyEndpoint running = add(frame, Role.ENERGY, 5, 4, true);
        targets.add(running);
        warm(frame, targets, 30);
        for (int index = 0; index < 20; index++) targets.add(add(frame, Role.ENERGY, 5, 4, false));
        final Ledger ledger = new Ledger(sources, targets);
        int misses = 0;
        for (int tick = 0; tick < 240; tick++) {
            advance(1);
            refill(sources);
            if (!ledger.consume(running)) misses++;
            poll(targets);
            ledger.service(frame);
            for (final IMEEnergyEndpoint target : targets) bounds(target);
        }
        assertEquals("Idle buffer charging must not interrupt a supplied 125% running consumer", 0, misses);
        ledger.assertConserved();
        assertTrue(
            frame.cache.transfers()
                .commits() > 0);
        assertTrue(frame.cache.maxTickWork() <= MEEnergyGridCache.WORK_PER_TICK);
    }

    public void starvedConsumerRetainsPriorityAndStoppedConsumerReleasesSupply() throws Exception {
        final Frame frame = frame(6);
        final List<IMEEnergyEndpoint> sources = new ArrayList<>(), targets = new ArrayList<>();
        sources.add(add(frame, Role.DYNAMO, 5, 16, true));
        final IMEEnergyEndpoint running = add(frame, Role.ENERGY, 5, 4, true);
        targets.add(running);
        warm(frame, targets, 30);
        final Ledger ledger = new Ledger(sources, targets);
        // Establish actual consumption before adding competing cold caches.
        for (int tick = 0; tick < 30; tick++) {
            advance(1);
            refill(sources);
            assertTrue(ledger.consume(running));
            poll(targets);
            ledger.service(frame);
        }
        for (int index = 0; index < 40; index++) targets.add(add(frame, Role.ENERGY, 5, 4, false));
        ledger.removeAll(running);
        assertTrue("This simulated failed native request must not itself change stock", !ledger.consume(running));
        assertEquals(0, running.readEU());
        int firstRecovery = -1, missesAfterRecovery = 0;
        for (int tick = 0; tick < 100; tick++) {
            advance(1);
            refill(sources);
            final boolean supplied = ledger.consume(running);
            if (supplied && firstRecovery < 0) firstRecovery = tick;
            if (!supplied && firstRecovery >= 0) missesAfterRecovery++;
            poll(targets);
            ledger.service(frame);
            for (final IMEEnergyEndpoint target : targets) bounds(target);
        }
        assertTrue("Failed native draws must not demote a previously observed consumer", firstRecovery >= 0);
        assertTrue("Refill must arrive within the wake/period/deadline window", firstRecovery <= 17);
        assertEquals(0, missesAfterRecovery);
        // Once consumption stops, its finite reserve must leave surplus available to precharge others.
        for (int tick = 0; tick < 400; tick++) {
            advance(1);
            refill(sources);
            poll(targets);
            ledger.service(frame);
        }
        for (final IMEEnergyEndpoint target : targets) assertEquals(
            target.spec()
                .capacity(),
            target.readEU());
        assertEquals(targets.size(), frame.cache.dormantCount());
        assertEquals(
            0,
            frame.cache.group(5)
                .expirations());
        assertTrue(frame.cache.maxTickWork() <= MEEnergyGridCache.WORK_PER_TICK);
        ledger.assertConserved();
    }

    public void wakingTailCannotReplaceAnUnvisitedRotatedDemandMember() throws Exception {
        final Frame frame = frame(0);
        final IMEEnergyEndpoint source = add(frame, Role.DYNAMO, 5, 16, true);
        final IMEEnergyEndpoint a = add(frame, Role.ENERGY, 5, 4, false);
        add(frame, Role.ENERGY, 5, 4, false);
        add(frame, Role.ENERGY, 5, 4, false);
        final IMEEnergyEndpoint waking = add(frame, Role.ENERGY, 5, 4, true);
        frame.cache.onUpdateTick(); // Zero initial source tokens; only the already-full target sleeps.
        assertEquals(1, frame.cache.dormantCount());
        assertEquals(3, frame.cache.demandCount(5, 0));
        final MEEnergyGridCache.Member first = frame.cache.firstDemand(5, 0);
        final MEEnergyGridCache.Member second = first.nextDemand();
        final MEEnergyGridCache.Member third = second.nextDemand();
        final MEEnergyGridCache.Member dormant = third.next();
        assertTrue(first.endpoint() == a);
        assertTrue(dormant.endpoint() == waking);
        final MEEnergyGroupState group = frame.cache.group(5);
        final MEEnergyGridCache.Member[] resume = (MEEnergyGridCache.Member[]) field(MEEnergyGroupState.class, "resume")
            .get(group);
        resume[MEEnergyGroupPlanner.COLOR_COUNT] = second; // Snapshot rotation B, C, A.
        advance(10);
        assertTrue(group.step(MEEnergyTickClock.now())); // Source snapshot.
        assertTrue(group.step(MEEnergyTickClock.now())); // B is visited; original tail is C.
        assertEquals(1, field(MEEnergyGroupState.class, "targetCount").getInt(group));
        waking.machine()
            .setEUVar(
                waking.spec()
                    .capacity() - 1);
        frame.cache.wakeTarget(dormant); // Appends D after C while the demand snapshot is in flight.
        assertEquals(4, frame.cache.demandCount(5, 0));
        assertTrue(group.step(MEEnergyTickClock.now())); // C must wrap to A instead of newly appended D.
        assertTrue(group.step(MEEnergyTickClock.now()));
        final MEEnergyGridCache.Member[] snapshot = (MEEnergyGridCache.Member[]) field(
            MEEnergyGroupState.class,
            "targets").get(group);
        assertEquals(3, field(MEEnergyGroupState.class, "targetCount").getInt(group));
        assertTrue(snapshot[0] == second);
        assertTrue(snapshot[1] == third);
        assertTrue(snapshot[2] == first);
        int steps = 0;
        while (group.step(MEEnergyTickClock.now())) assertTrue(++steps < 100);
        assertTrue("The previously unvisited A must be served in this round", a.readEU() > 0);
        assertEquals(
            waking.spec()
                .capacity() - 1,
            waking.readEU());
        source.machine()
            .setEUVar(
                source.spec()
                    .capacity());
        advance(10);
        frame.cache.onUpdateTick();
        assertEquals(
            "The newly awakened D must be available to the next service round",
            waking.spec()
                .capacity(),
            waking.readEU());
        assertEquals(0, group.expirations());
    }
}
