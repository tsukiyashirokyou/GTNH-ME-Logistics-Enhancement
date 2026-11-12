package com.kyou.gtnhmelogisticsenhancement.energy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.minecraft.nbt.NBTTagCompound;

import com.kyou.gtnhmelogisticsenhancement.energy.MEEnergyGridCache.Member;
import com.kyou.gtnhmelogisticsenhancement.energy.MEEnergySpec.Role;

import appeng.api.AEApi;
import appeng.api.networking.events.MENetworkChannelsChanged;
import appeng.api.networking.events.MENetworkPowerStatusChange;
import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;

/** Real GT stock, loss, permissions and scheduler; controlled AE availability or explicit native AE engine. */
public class MEEnergyTransferFixture extends MEEnergyAENetworkFixture {

    private static final BigInteger NINETEEN = BigInteger.valueOf(19), TWENTY = BigInteger.valueOf(20);

    private static final class Pair {

        final Frame frame = new Frame();
        final IMEEnergyEndpoint source, target;
        final FakeNode sourceNode, targetNode;
        final Member from, to;

        Pair(final int tier, final int sourceA, final int targetA) throws Exception {
            this.source = hatch(Role.DYNAMO, tier, sourceA);
            this.target = hatch(Role.ENERGY, tier, targetA);
            this.sourceNode = this.frame.add(this.source, 0);
            this.targetNode = this.frame.add(this.target, 0);
            this.frame.cache.onUpdateTick();
            this.from = this.frame.cache.first(Role.DYNAMO, tier, 0);
            this.to = this.frame.cache.first(Role.ENERGY, tier, 0);
        }

        long commit(final long limit) {
            return this.frame.cache.transfers()
                .commit(
                    this.frame.cache,
                    this.frame.cache.epoch(
                        this.source.spec()
                            .tier()),
                    this.from,
                    this.to,
                    limit,
                    MEEnergyTickClock.now());
        }

        void charge() {
            this.source.machine()
                .setEUVar(
                    this.source.spec()
                        .capacity());
            advance(24);
        }
    }

    private static void ticks(final Frame frame, final int count) {
        for (int tick = 0; tick < count; tick++) {
            advance(1);
            frame.cache.onUpdateTick();
        }
    }

    private static BigInteger big(final long value) {
        return BigInteger.valueOf(value);
    }

    public void sourceLossIsChargedAtAdmissionAndPartialSpaceIsExact() throws Exception {
        final Pair p = new Pair(5, 4, 4);
        p.charge();
        final long initial = p.source.readEU();
        assertEquals(19, p.commit(19));
        assertEquals(initial - 20, p.source.readEU());
        assertEquals(19, p.target.readEU());
        assertEquals(
            0,
            p.source.energyState()
                .lossAccount()
                .credit());
        p.target.machine()
            .setEUVar(
                p.target.spec()
                    .capacity() - 1);
        assertEquals(1, p.commit(Long.MAX_VALUE));
        assertEquals(initial - 22, p.source.readEU());
        assertEquals(
            p.target.spec()
                .capacity(),
            p.target.readEU());
        assertEquals(
            18,
            p.source.energyState()
                .lossAccount()
                .credit());
        final long tokens = p.source.energyState()
            .rateBudget()
            .tokens(MEEnergyTickClock.now());
        assertEquals(0, p.commit(1));
        assertEquals(
            tokens,
            p.source.energyState()
                .rateBudget()
                .tokens(MEEnergyTickClock.now()));
    }

    public void everyRoundingCreditMatchesIndependentBigIntegerOracle() throws Exception {
        final Pair p = new Pair(13, 16777216, 16777216);
        final Random random = new Random(951920);
        for (int initialCredit = 0; initialCredit < 19; initialCredit++) {
            credit(p.source, initialCredit);
            BigInteger gross = BigInteger.ZERO, net = BigInteger.ZERO;
            for (int sample = 0; sample < 100; sample++) {
                final long now = MEEnergyTickClock.now();
                p.source.energyState()
                    .rateBudget()
                    .reset(now);
                p.source.energyState()
                    .rateBudget()
                    .setActive(true, now);
                p.source.machine()
                    .setEUVar(
                        p.source.spec()
                            .capacity());
                p.target.machine()
                    .setEUVar(0);
                advance(24);
                final long requested = sample == 0 ? p.target.spec()
                    .capacity()
                    : 1 + (random.nextLong() & Long.MAX_VALUE) % p.target.spec()
                        .capacity();
                final long before = p.source.readEU();
                final long received = p.commit(requested);
                assertTrue(received > 0 && received <= requested);
                gross = gross.add(big(before - p.source.readEU()));
                net = net.add(big(received));
                assertEquals(
                    big(
                        p.source.energyState()
                            .lossAccount()
                            .credit() - initialCredit),
                    gross.multiply(NINETEEN)
                        .subtract(net.multiply(TWENTY)));
                assertEquals(received, p.target.readEU());
            }
        }
    }

    public void grossBudgetReserveNegativeAndOverCapacityStocksAreSafe() throws Exception {
        final Pair p = new Pair(5, 4, 4);
        p.source.machine()
            .setEUVar(
                p.source.spec()
                    .capacity());
        assertEquals(0, p.commit(100)); // No initial free tokens.
        advance(1);
        assertEquals(
            p.source.spec()
                .power() * 19 / 20,
            p.commit(Long.MAX_VALUE));
        assertEquals(0, p.commit(100));
        p.source.machine()
            .setEUVar(-1);
        advance(24);
        assertEquals(0, p.commit(1));
        assertEquals(-1, p.source.readEU());
        p.source.machine()
            .setEUVar(
                p.source.spec()
                    .nativeReserve());
        assertEquals(0, p.commit(1));
        p.source.machine()
            .setEUVar(
                p.source.spec()
                    .capacity() + 500);
        p.target.machine()
            .setEUVar(0);
        final long received = p.commit(Long.MAX_VALUE);
        assertTrue(received > 0);
        assertEquals(
            500 + p.source.spec()
                .nativeReserve(),
            p.source.readEU());
        p.source.machine()
            .setEUVar(
                p.source.spec()
                    .capacity());
        p.target.machine()
            .setEUVar(Long.MAX_VALUE);
        advance(24);
        assertEquals(0, p.commit(1));
        assertEquals(Long.MAX_VALUE, p.target.readEU());
        p.target.machine()
            .setEUVar(-1);
        assertEquals(0, p.commit(1));
        assertEquals(-1, p.target.readEU());
    }

    public void staleColorChannelGridRoleVoltageAndTimeNeverCharge() throws Exception {
        final Pair p = new Pair(5, 4, 4);
        p.charge();
        final long raw = p.source.readEU(), epoch = p.frame.cache.epoch(5);
        p.target.machine()
            .getBaseMetaTileEntity()
            .setColorization((byte) 1);
        assertEquals(
            0,
            p.frame.cache.transfers()
                .commit(p.frame.cache, epoch, p.from, p.to, 19, MEEnergyTickClock.now()));
        p.source.machine()
            .setEUVar(0); // Refresh membership without funding the automatic periodic service.
        p.frame.cache.onUpdateTick();
        p.source.machine()
            .setEUVar(raw);
        p.targetNode.channel = false;
        p.target.meChannelsChanged(new MENetworkChannelsChanged());
        assertEquals(0, p.commit(19));
        p.targetNode.channel = true;
        p.target.meChannelsChanged(new MENetworkChannelsChanged());
        p.frame.cache.onUpdateTick();
        final Frame other = new Frame();
        assertEquals(
            0,
            p.frame.cache.transfers()
                .commit(other.cache, epoch, p.from, p.to, 19, MEEnergyTickClock.now()));
        assertEquals(
            0,
            p.frame.cache.transfers()
                .commit(p.frame.cache, p.frame.cache.epoch(5), p.to, p.from, 19, MEEnergyTickClock.now()));
        assertEquals(
            0,
            p.frame.cache.transfers()
                .commit(p.frame.cache, p.frame.cache.epoch(5), p.from, p.to, 19, MEEnergyTickClock.now() + 1));
        final IMEEnergyEndpoint high = hatch(Role.ENERGY, 6, 4);
        p.frame.add(high, 0);
        p.frame.cache.onUpdateTick();
        assertEquals(
            0,
            p.frame.cache.transfers()
                .commit(
                    p.frame.cache,
                    p.frame.cache.epoch(5),
                    p.from,
                    p.frame.cache.first(Role.ENERGY, 6, 0),
                    19,
                    MEEnergyTickClock.now()));
        assertEquals(raw, p.source.readEU());
        assertEquals(0, p.target.readEU());
        assertEquals(
            0,
            p.source.energyState()
                .lossAccount()
                .credit());
    }

    public void nativeMarkDirtyReentryCannotDebitTwice() throws Exception {
        final Frame frame = new Frame();
        final IMEEnergyEndpoint source = hatch(Role.DYNAMO, 5, 4), target = hatch(Role.ENERGY, 5, 4);
        final ReentrantBase base = new ReentrantBase();
        source.machine()
            .setBaseMetaTileEntity(base);
        source.machine()
            .onFirstTick(base);
        frame.add(source, 0);
        frame.add(target, 0);
        frame.cache.onUpdateTick();
        source.machine()
            .setEUVar(10000);
        advance(24);
        final Member from = frame.cache.first(Role.DYNAMO, 5, 0), to = frame.cache.first(Role.ENERGY, 5, 0);
        final long epoch = frame.cache.epoch(5);
        base.callback = () -> {
            assertEquals(
                0,
                frame.cache.transfers()
                    .commit(frame.cache, epoch, from, to, 19, MEEnergyTickClock.now()));
        };
        assertEquals(
            19,
            frame.cache.transfers()
                .commit(frame.cache, epoch, from, to, 19, MEEnergyTickClock.now()));
        assertEquals(9980, source.readEU());
        assertEquals(19, target.readEU());
        assertEquals(1, base.callbacks);
        assertEquals(
            1,
            frame.cache.transfers()
                .commits());
    }

    public static class ReentrantBase extends ControlledBase {

        Runnable callback;
        int callbacks;

        @Override
        public void markDirty() {
            super.markDirty();
            if (this.callback != null) {
                this.callbacks++;
                this.callback.run();
            }
        }
    }

    public void competingTransfersReadFreshBalancesAndDoNotOverdraw() throws Exception {
        final Pair p = new Pair(5, 4, 4);
        final IMEEnergyEndpoint second = hatch(Role.ENERGY, 5, 4);
        p.frame.add(second, 0);
        p.frame.cache.onUpdateTick();
        p.source.machine()
            .setEUVar(
                p.source.spec()
                    .nativeReserve() + 21);
        advance(24);
        assertEquals(19, p.commit(19));
        final Member toSecond = p.to.next();
        assertNotNull(toSecond);
        assertEquals(
            0,
            p.frame.cache.transfers()
                .commit(p.frame.cache, p.frame.cache.epoch(5), p.from, toSecond, 19, MEEnergyTickClock.now()));
        assertEquals(
            p.source.spec()
                .nativeReserve() + 1,
            p.source.readEU());
        assertEquals(0, second.readEU());
    }

    public void defaultAndColoredPoolsAreIsolatedWithoutUnmatchedDebits() throws Exception {
        final Frame frame = new Frame();
        final IMEEnergyEndpoint red = hatch(Role.DYNAMO, 5, 4), wildcard = hatch(Role.DYNAMO, 5, 4);
        final IMEEnergyEndpoint any = hatch(Role.ENERGY, 5, 4), blue = hatch(Role.ENERGY, 5, 4);
        frame.add(red, 1);
        frame.add(wildcard, 0);
        frame.add(any, 0);
        frame.add(blue, 2);
        any.machine()
            .setEUVar(
                any.spec()
                    .capacity() - 10);
        blue.machine()
            .setEUVar(
                blue.spec()
                    .capacity() - 10);
        frame.cache.onUpdateTick();
        red.machine()
            .setEUVar(
                red.spec()
                    .nativeReserve() + 11);
        wildcard.machine()
            .setEUVar(
                wildcard.spec()
                    .nativeReserve() + 11);
        advance(1);
        final long redTokens = red.energyState()
            .rateBudget()
            .tokens(MEEnergyTickClock.now());
        final long defaultTokens = wildcard.energyState()
            .rateBudget()
            .tokens(MEEnergyTickClock.now());
        assertEquals(
            0,
            frame.cache.transfers()
                .commit(
                    frame.cache,
                    frame.cache.epoch(5),
                    frame.cache.first(Role.DYNAMO, 5, 0),
                    frame.cache.first(Role.ENERGY, 5, 2),
                    10,
                    MEEnergyTickClock.now()));
        assertEquals(
            0,
            frame.cache.transfers()
                .commit(
                    frame.cache,
                    frame.cache.epoch(5),
                    frame.cache.first(Role.DYNAMO, 5, 1),
                    frame.cache.first(Role.ENERGY, 5, 0),
                    10,
                    MEEnergyTickClock.now()));
        assertEquals(
            redTokens,
            red.energyState()
                .rateBudget()
                .tokens(MEEnergyTickClock.now()));
        assertEquals(
            defaultTokens,
            wildcard.energyState()
                .rateBudget()
                .tokens(MEEnergyTickClock.now()));
        assertEquals(
            0,
            red.energyState()
                .lossAccount()
                .credit());
        assertEquals(
            0,
            wildcard.energyState()
                .lossAccount()
                .credit());
        ticks(frame, 20);
        assertEquals(
            any.spec()
                .capacity(),
            any.readEU());
        assertEquals(
            blue.spec()
                .capacity() - 10,
            blue.readEU());
        assertEquals(
            red.spec()
                .nativeReserve() + 11,
            red.readEU());
        assertEquals(
            wildcard.spec()
                .nativeReserve(),
            wildcard.readEU());
        assertEquals(
            0,
            red.energyState()
                .lossAccount()
                .credit());
        assertEquals(
            9,
            wildcard.energyState()
                .lossAccount()
                .credit());
        assertEquals(
            1,
            frame.cache.transfers()
                .commits());
    }

    public void mixedAmperagesPoolOnlyWithinMatchingVoltageAndColor() throws Exception {
        final Frame frame = new Frame();
        final IMEEnergyEndpoint low = hatch(Role.DYNAMO, 5, 4), high = hatch(Role.DYNAMO, 5, 16);
        final IMEEnergyEndpoint target = hatch(Role.ENERGY, 5, 16), different = hatch(Role.ENERGY, 6, 4);
        frame.add(low, 1);
        frame.add(high, 1);
        frame.add(target, 1);
        frame.add(different, 1);
        frame.cache.onUpdateTick();
        low.machine()
            .setEUVar(
                low.spec()
                    .capacity());
        high.machine()
            .setEUVar(
                high.spec()
                    .capacity());
        ticks(frame, 20);
        assertTrue(
            target.readEU() > low.spec()
                .capacity());
        assertEquals(0, different.readEU());
        final long net = target.readEU();
        final long debit = low.spec()
            .capacity() - low.readEU()
            + high.spec()
                .capacity()
            - high.readEU();
        assertEquals(
            big(
                low.energyState()
                    .lossAccount()
                    .credit()
                    + high.energyState()
                        .lossAccount()
                        .credit()),
            big(debit).multiply(NINETEEN)
                .subtract(big(net).multiply(TWENTY)));
    }

    public void batchesShareOneDeadlineAndStableIntermediateTicksDoNoQueries() throws Exception {
        final Frame frame = new Frame();
        final List<IMEEnergyEndpoint> sources = new ArrayList<>(), targets = new ArrayList<>();
        for (int index = 0; index < 200; index++) {
            final IMEEnergyEndpoint source = hatch(Role.DYNAMO, 5, 16), target = hatch(Role.ENERGY, 5, 4);
            frame.add(source, 0);
            frame.add(target, 0);
            sources.add(source);
            targets.add(target);
        }
        frame.cache.onUpdateTick();
        for (final IMEEnergyEndpoint source : sources) source.machine()
            .setEUVar(
                source.spec()
                    .capacity());
        ticks(frame, 23);
        for (final IMEEnergyEndpoint target : targets) assertEquals(
            target.spec()
                .capacity(),
            target.readEU());
        assertTrue(frame.cache.maxTickWork() <= MEEnergyGridCache.WORK_PER_TICK);
        assertEquals(
            0,
            frame.cache.group(5)
                .expirations());
        assertTrue(
            frame.cache.transfers()
                .attempts() <= 400);
        final long reads = frame.cache.transfers()
            .reads(), refreshes = frame.cache.refreshes();
        ticks(frame, 15); // Through tick 38, before the next scheduled service at 40.
        assertEquals(
            reads,
            frame.cache.transfers()
                .reads());
        assertEquals(refreshes, frame.cache.refreshes());
    }

    public void overloadedPlanningExpiresWithoutChargingAndDoesNotCatchUp() throws Exception {
        final Frame frame = new Frame();
        final IMEEnergyEndpoint source = hatch(Role.DYNAMO, 5, 256);
        frame.add(source, 0);
        final List<IMEEnergyEndpoint> targets = new ArrayList<>();
        for (int index = 0; index < 1200; index++) {
            final IMEEnergyEndpoint target = hatch(Role.ENERGY, 5, 4);
            frame.add(target, 0);
            targets.add(target);
        }
        source.machine()
            .setEUVar(
                source.spec()
                    .capacity());
        frame.cache.onUpdateTick();
        ticks(frame, 44); // Initial registration now refreshes in bounded batches before two overload rounds.
        assertTrue(
            frame.cache.group(5)
                .expirations() >= 2);
        assertEquals(
            source.spec()
                .capacity(),
            source.readEU());
        for (final IMEEnergyEndpoint target : targets) assertEquals(0, target.readEU());
        assertEquals(
            0,
            source.energyState()
                .lossAccount()
                .credit());
        assertTrue(frame.cache.maxTickWork() <= 512);
        final long rounds = frame.cache.group(5)
            .rounds();
        advance(10000);
        frame.cache.onUpdateTick();
        assertEquals(
            rounds,
            frame.cache.group(5)
                .rounds());
    }

    public void invalidationBetweenSlicesCancelsUncommittedWork() throws Exception {
        final Frame frame = new Frame();
        final IMEEnergyEndpoint source = hatch(Role.DYNAMO, 5, 256);
        final FakeNode node = frame.add(source, 0);
        for (int index = 0; index < 300; index++) frame.add(hatch(Role.ENERGY, 5, 4), 0);
        frame.cache.onUpdateTick();
        ticks(frame, 4);
        source.machine()
            .setEUVar(
                source.spec()
                    .capacity());
        ticks(frame, 16); // Tick 20: snapshot and partial planning, no finalized quota yet.
        final long raw = source.readEU();
        node.channel = false;
        source.meChannelsChanged(new MENetworkChannelsChanged());
        ticks(frame, 3);
        assertEquals(raw, source.readEU());
        assertEquals(
            0,
            frame.cache.transfers()
                .commits());
        assertTrue(
            frame.cache.group(5)
                .cancellations() > 0);
    }

    public void unfinishedExecutionAndFullTargetsDoNotReplayOrBurnCredit() throws Exception {
        final Frame frame = new Frame();
        final IMEEnergyEndpoint source = hatch(Role.DYNAMO, 13, 16777216);
        frame.add(source, 0);
        final List<IMEEnergyEndpoint> targets = new ArrayList<>();
        for (int index = 0; index < 130; index++) {
            final IMEEnergyEndpoint target = hatch(Role.ENERGY, 13, 4);
            frame.add(target, 0);
            targets.add(target);
        }
        frame.cache.onUpdateTick();
        source.machine()
            .setEUVar(
                source.spec()
                    .capacity());
        ticks(frame, 16); // Tier 13 phase: 131 collection + 260 planning leaves part of execution for tick 17.
        final long paid = source.spec()
            .capacity() - source.readEU();
        assertTrue(paid > 0);
        final IMEEnergyEndpoint last = targets.get(129);
        last.machine()
            .setEUVar(
                last.spec()
                    .capacity()); // External controller filled it after snapshot.
        source.machine()
            .getBaseMetaTileEntity()
            .setColorization((byte) 0);
        ticks(frame, 3);
        assertEquals(
            paid,
            source.spec()
                .capacity() - source.readEU());
        assertTrue(
            frame.cache.group(13)
                .cancellations() > 0);
        final long commits = frame.cache.transfers()
            .commits();
        ticks(frame, 17); // Already-paid targets stay full, and remaining current targets may be served next round.
        assertTrue(
            frame.cache.transfers()
                .commits() >= commits);
        for (final IMEEnergyEndpoint target : targets) assertTrue(
            target.readEU() <= target.spec()
                .capacity());
    }

    public void offlineCachesRemainConsumableAndRestorationNeverRefillsForFree() throws Exception {
        final Pair p = new Pair(5, 4, 4);
        p.source.machine()
            .setEUVar(
                p.source.spec()
                    .capacity());
        ticks(p.frame, 20);
        final long stock = p.target.readEU();
        assertTrue(stock > 100);
        p.frame.powered = false;
        p.frame.cache.powerChanged(new MENetworkPowerStatusChange());
        assertTrue(
            p.target.machine()
                .getBaseMetaTileEntity()
                .decreaseStoredEnergyUnits(100, false));
        final long source = p.source.readEU();
        ticks(p.frame, 100);
        assertEquals(stock - 100, p.target.readEU());
        assertEquals(source, p.source.readEU());
        p.source.machine()
            .setEUVar(
                p.source.spec()
                    .nativeReserve());
        p.frame.powered = true;
        p.frame.cache.powerChanged(new MENetworkPowerStatusChange());
        ticks(p.frame, 20);
        assertEquals(stock - 100, p.target.readEU());
    }

    public void targetFilledAfterSnapshotReleasesQuotaWithoutPayingAgain() throws Exception {
        final Frame frame = new Frame();
        final IMEEnergyEndpoint source = hatch(Role.DYNAMO, 13, 16777216);
        frame.add(source, 0);
        final List<IMEEnergyEndpoint> targets = new ArrayList<>();
        for (int index = 0; index < 130; index++) {
            final IMEEnergyEndpoint target = hatch(Role.ENERGY, 13, 4);
            frame.add(target, 0);
            targets.add(target);
        }
        frame.cache.onUpdateTick();
        source.machine()
            .setEUVar(
                source.spec()
                    .capacity());
        ticks(frame, 16);
        final IMEEnergyEndpoint last = targets.get(129);
        assertEquals(0, last.readEU());
        last.machine()
            .setEUVar(
                last.spec()
                    .capacity()); // Genuine external inventory change, no topology change.
        ticks(frame, 3);
        assertEquals(
            129,
            frame.cache.transfers()
                .commits());
        Member lastMember = frame.cache.first(Role.ENERGY, 13, 0);
        while (lastMember.endpoint() != last) lastMember = lastMember.next();
        assertEquals(
            last.spec()
                .capacity(),
            lastMember.fairness()
                .credit());
        final BigInteger net = big(
            last.spec()
                .capacity()).multiply(big(129));
        assertEquals(
            big(
                source.energyState()
                    .lossAccount()
                    .credit()),
            big(
                source.spec()
                    .capacity() - source.readEU()).multiply(NINETEEN)
                        .subtract(net.multiply(TWENTY)));
        assertTrue(
            frame.cache.transfers()
                .spaceLimited() > 0);
    }

    public void externalSourceDrainAfterSnapshotUsesCurrentStockAndRemainingSources() throws Exception {
        final Frame frame = new Frame();
        final IMEEnergyEndpoint source = hatch(Role.DYNAMO, 13, 16777216), backup = hatch(Role.DYNAMO, 13, 4);
        frame.add(source, 0);
        frame.add(backup, 0);
        final List<IMEEnergyEndpoint> targets = new ArrayList<>();
        for (int index = 0; index < 130; index++) {
            final IMEEnergyEndpoint target = hatch(Role.ENERGY, 13, 4);
            frame.add(target, 0);
            targets.add(target);
        }
        frame.cache.onUpdateTick();
        source.machine()
            .setEUVar(
                source.spec()
                    .capacity());
        backup.machine()
            .setEUVar(
                backup.spec()
                    .capacity());
        ticks(frame, 16);
        final long external = source.readEU() - source.spec()
            .nativeReserve();
        assertTrue(
            source.machine()
                .getBaseMetaTileEntity()
                .decreaseStoredEnergyUnits(external, false));
        ticks(frame, 3);
        BigInteger net = BigInteger.ZERO;
        for (final IMEEnergyEndpoint target : targets) net = net.add(big(target.readEU()));
        final BigInteger gross = big(
            source.spec()
                .capacity() - source.readEU()
                - external).add(
                    big(
                        backup.spec()
                            .capacity() - backup.readEU()));
        assertEquals(
            big(
                source.energyState()
                    .lossAccount()
                    .credit()
                    + backup.energyState()
                        .lossAccount()
                        .credit()),
            gross.multiply(NINETEEN)
                .subtract(net.multiply(TWENTY)));
        assertTrue(
            frame.cache.transfers()
                .stockLimited() > 0);
        assertTrue(
            backup.readEU() < backup.spec()
                .capacity());
        assertTrue(
            source.readEU() >= source.spec()
                .nativeReserve());
    }

    public void migrationAfterPartialExecutionNeverReplaysOldGridWork() throws Exception {
        final Frame first = new Frame(), second = new Frame();
        final IMEEnergyEndpoint source = hatch(Role.DYNAMO, 13, 16777216);
        final FakeNode sourceNode = first.add(source, 0);
        final List<IMEEnergyEndpoint> targets = new ArrayList<>();
        for (int index = 0; index < 130; index++) {
            final IMEEnergyEndpoint target = hatch(Role.ENERGY, 13, 4);
            first.add(target, 0);
            targets.add(target);
        }
        first.cache.onUpdateTick();
        source.machine()
            .setEUVar(
                source.spec()
                    .capacity());
        ticks(first, 16);
        BigInteger before = BigInteger.ZERO;
        for (final IMEEnergyEndpoint target : targets) before = before.add(big(target.readEU()));
        assertTrue(before.signum() > 0);
        final long raw = source.readEU();
        final int credit = source.energyState()
            .lossAccount()
            .credit();
        first.cache.removeNode(sourceNode.node, source);
        sourceNode.frame = second;
        second.cache.addNode(sourceNode.node, source);
        source.gridChanged();
        final IMEEnergyEndpoint next = hatch(Role.ENERGY, 13, 4);
        second.add(next, 0);
        second.cache.onUpdateTick();
        assertEquals(
            0,
            source.energyState()
                .rateBudget()
                .tokens(MEEnergyTickClock.now()));
        assertEquals(raw, source.readEU());
        assertEquals(
            credit,
            source.energyState()
                .lossAccount()
                .credit());
        for (int tick = 0; tick < 20; tick++) {
            advance(1);
            first.cache.onUpdateTick();
            second.cache.onUpdateTick();
        }
        BigInteger after = BigInteger.ZERO;
        for (final IMEEnergyEndpoint target : targets) after = after.add(big(target.readEU()));
        assertEquals(before, after);
        assertEquals(
            next.spec()
                .capacity(),
            next.readEU());
        assertEquals(
            big(
                source.energyState()
                    .lossAccount()
                    .credit()),
            big(
                source.spec()
                    .capacity() - source.readEU()).multiply(NINETEEN)
                        .subtract(
                            after.add(big(next.readEU()))
                                .multiply(TWENTY)));
    }

    public void scarceContinuousSupplyPreservesNominalWeightsOverManyEpochs() throws Exception {
        final Frame frame = new Frame();
        final IMEEnergyEndpoint source = hatch(Role.DYNAMO, 5, 4), small = hatch(Role.ENERGY, 5, 4),
            large = hatch(Role.ENERGY, 5, 16);
        // Establish both consumers before applying scarce supply. Cold empty targets now intentionally
        // receive only spare precharge; fairness within the running class must still preserve 1:4.
        attach(small);
        attach(large);
        small.machine()
            .setEUVar(
                small.spec()
                    .power());
        large.machine()
            .setEUVar(
                large.spec()
                    .power());
        frame.add(source, 0);
        frame.add(small, 0);
        frame.add(large, 0);
        frame.cache.onUpdateTick();
        long smallTotal = 0, largeTotal = 0;
        for (int tick = 0; tick < 20000; tick++) {
            source.machine()
                .setEUVar(
                    source.spec()
                        .capacity());
            ticks(frame, 1);
            smallTotal += small.readEU();
            largeTotal += large.readEU();
            small.machine()
                .getBaseMetaTileEntity()
                .decreaseStoredEnergyUnits(small.readEU(), false);
            large.machine()
                .getBaseMetaTileEntity()
                .decreaseStoredEnergyUnits(large.readEU(), false);
        }
        assertTrue(smallTotal > 0);
        assertTrue(
            Math.abs(largeTotal - 4 * smallTotal) <= large.spec()
                .power() * 40);
        assertTrue(
            frame.cache.group(5)
                .maxServiceGap() > 20); // Scarcity is not a guarantee of continuous service.
    }

    public void lateServiceCannotExtendScheduledDeadline() throws Exception {
        final Frame frame = new Frame();
        final IMEEnergyEndpoint source = hatch(Role.DYNAMO, 13, 16777216);
        frame.add(source, 0);
        for (int index = 0; index < 400; index++) frame.add(hatch(Role.ENERGY, 13, 4), 0);
        frame.cache.onUpdateTick();
        ticks(frame, 3); // Finish bounded indexing while still before the phase-16 deadline scenario.
        source.machine()
            .setEUVar(
                source.spec()
                    .capacity());
        advance(15);
        frame.cache.onUpdateTick(); // Scheduled at 16: deadline remains 19, not 21.
        ticks(frame, 2);
        assertEquals(
            1,
            frame.cache.group(13)
                .expirations());
        assertEquals(
            0,
            frame.cache.transfers()
                .commits());
        assertEquals(
            source.spec()
                .capacity(),
            source.readEU());
    }

    public void multipleVoltageGroupsShareOneCacheBudgetAndRemainIsolated() throws Exception {
        final Frame frame = new Frame();
        final IMEEnergyEndpoint[] sources = new IMEEnergyEndpoint[9];
        final IMEEnergyEndpoint[][] targets = new IMEEnergyEndpoint[9][180];
        final long[] debited = new long[9];
        for (int group = 0; group < 9; group++) {
            sources[group] = hatch(Role.DYNAMO, 5 + group, 64);
            frame.add(sources[group], 0);
            for (int index = 0; index < targets[group].length; index++) {
                targets[group][index] = hatch(Role.ENERGY, 5 + group, 4);
                frame.add(targets[group][index], 0);
            }
        }
        frame.cache.onUpdateTick();
        for (int tick = 0; tick < 40; tick++) {
            for (final IMEEnergyEndpoint source : sources) source.machine()
                .setEUVar(
                    source.spec()
                        .capacity());
            ticks(frame, 1);
            for (int group = 0; group < 9; group++) debited[group] += sources[group].spec()
                .capacity() - sources[group].readEU();
        }
        for (int group = 0; group < 9; group++) {
            long net = 0;
            for (final IMEEnergyEndpoint target : targets[group]) net += target.readEU();
            assertTrue(net > 0);
            assertEquals(
                big(
                    sources[group].energyState()
                        .lossAccount()
                        .credit()),
                big(debited[group]).multiply(NINETEEN)
                    .subtract(big(net).multiply(TWENTY)));
        }
        assertTrue(frame.cache.maxTickWork() > 0 && frame.cache.maxTickWork() <= 512);
        assertTrue(
            frame.cache.transfers()
                .reads() < 1629 * 10);
    }

    public void wideSnapshotsCommitLongFragmentsWithoutAggregateOverflow() throws Exception {
        final Frame frame = new Frame();
        final List<IMEEnergyEndpoint> sources = new ArrayList<>(), targets = new ArrayList<>();
        BigInteger initial = BigInteger.ZERO;
        for (int index = 0; index < 50; index++) {
            final IMEEnergyEndpoint source = hatch(Role.DYNAMO, 13, 16777216),
                target = hatch(Role.ENERGY, 13, 16777216);
            frame.add(source, 0);
            frame.add(target, 0);
            sources.add(source);
            targets.add(target);
            source.machine()
                .setEUVar(
                    source.spec()
                        .capacity());
            initial = initial.add(big(source.readEU()));
        }
        frame.cache.onUpdateTick();
        advance(36);
        frame.cache.onUpdateTick();
        assertTrue(
            frame.cache.group(13)
                .wide());
        BigInteger debit = initial, net = BigInteger.ZERO, credit = BigInteger.ZERO;
        for (final IMEEnergyEndpoint source : sources) {
            debit = debit.subtract(big(source.readEU()));
            credit = credit.add(
                big(
                    source.energyState()
                        .lossAccount()
                        .credit()));
            assertTrue(
                source.readEU() >= source.spec()
                    .nativeReserve());
        }
        for (final IMEEnergyEndpoint target : targets) {
            net = net.add(big(target.readEU()));
            assertTrue(
                target.readEU() <= target.spec()
                    .capacity());
        }
        assertTrue(net.compareTo(big(Long.MAX_VALUE)) > 0);
        assertEquals(
            credit,
            debit.multiply(NINETEEN)
                .subtract(net.multiply(TWENTY)));
    }

    public void realAeChannelEngineTransfersAndLocalGtConsumptionBalance() throws Exception {
        final IMEEnergyEndpoint source = nativeHost(Role.DYNAMO, 0), target = nativeHost(Role.ENERGY, 1);
        AEApi.instance()
            .createGridConnection(source.getActionableNode(), target.getActionableNode());
        source.machine()
            .setEUVar(
                source.spec()
                    .capacity());
        nativeTicks(source, 80);
        assertTrue(source.isActive());
        assertTrue(target.isActive());
        assertTrue(target.readEU() > 0);
        assertTrue(
            target.machine()
                .getBaseMetaTileEntity()
                .decreaseStoredEnergyUnits(100, false));
        final long debit = source.spec()
            .capacity() - source.readEU(), net = target.readEU() + 100;
        assertEquals(
            big(
                source.energyState()
                    .lossAccount()
                    .credit()),
            big(debit).multiply(NINETEEN)
                .subtract(big(net).multiply(TWENTY)));
        assertTrue(
            cache(source).transfers()
                .commits() > 0);
    }

    public void randomGenerationConsumptionOutagesAndPaintConserveEveryTick() throws Exception {
        final Frame frame = new Frame();
        final IMEEnergyEndpoint[] sources = { hatch(Role.DYNAMO, 5, 4), hatch(Role.DYNAMO, 5, 16),
            hatch(Role.DYNAMO, 5, 64) };
        final IMEEnergyEndpoint[] targets = { hatch(Role.ENERGY, 5, 4), hatch(Role.ENERGY, 5, 16),
            hatch(Role.ENERGY, 5, 64) };
        for (int index = 0; index < 3; index++) {
            frame.add(sources[index], index);
            frame.add(targets[index], index);
        }
        frame.cache.onUpdateTick();
        final Random random = new Random(20261107);
        BigInteger generated = BigInteger.ZERO, consumed = BigInteger.ZERO, debit = BigInteger.ZERO,
            net = BigInteger.ZERO;
        for (int tick = 1; tick <= 2000; tick++) {
            for (final IMEEnergyEndpoint source : sources) {
                final long amount = Math.min(
                    source.spec()
                        .capacity() - source.readEU(),
                    random.nextInt(
                        (int) source.spec()
                            .power() * 2));
                assertTrue(
                    source.machine()
                        .getBaseMetaTileEntity()
                        .increaseStoredEnergyUnits(amount, true));
                generated = generated.add(big(amount));
            }
            for (final IMEEnergyEndpoint target : targets) {
                final long amount = Math.min(
                    target.readEU(),
                    random.nextInt(
                        (int) target.spec()
                            .power()));
                assertTrue(
                    target.machine()
                        .getBaseMetaTileEntity()
                        .decreaseStoredEnergyUnits(amount, false));
                consumed = consumed.add(big(amount));
            }
            if (tick % 71 == 0) {
                frame.powered = !frame.powered;
                frame.cache.powerChanged(new MENetworkPowerStatusChange());
            }
            if (tick % 53 == 0) targets[random.nextInt(3)].machine()
                .getBaseMetaTileEntity()
                .setColorization((byte) (random.nextInt(4) - 1));
            BigInteger beforeSource = BigInteger.ZERO, beforeTarget = BigInteger.ZERO;
            for (final IMEEnergyEndpoint source : sources) beforeSource = beforeSource.add(big(source.readEU()));
            for (final IMEEnergyEndpoint target : targets) beforeTarget = beforeTarget.add(big(target.readEU()));
            ticks(frame, 1);
            BigInteger afterSource = BigInteger.ZERO, afterTarget = BigInteger.ZERO, credit = BigInteger.ZERO;
            for (final IMEEnergyEndpoint source : sources) {
                afterSource = afterSource.add(big(source.readEU()));
                credit = credit.add(
                    big(
                        source.energyState()
                            .lossAccount()
                            .credit()));
            }
            for (final IMEEnergyEndpoint target : targets) {
                afterTarget = afterTarget.add(big(target.readEU()));
                assertTrue(
                    target.readEU() >= 0 && target.readEU() <= target.spec()
                        .capacity());
            }
            debit = debit.add(beforeSource.subtract(afterSource));
            net = net.add(afterTarget.subtract(beforeTarget));
            assertEquals(
                "credit tick " + tick,
                credit,
                debit.multiply(NINETEEN)
                    .subtract(net.multiply(TWENTY)));
            assertEquals(
                "stock tick " + tick,
                generated,
                afterSource.add(afterTarget)
                    .add(consumed)
                    .add(debit.subtract(net)));
        }
        assertTrue(
            frame.cache.transfers()
                .commits() > 0);
    }

    public void nativeWorldNbtResumePreservesPrepaidRoundingAndDoesNotSaveTokens() throws Exception {
        final short id = 31998;
        final IMetaTileEntity old = GregTechAPI.METATILEENTITIES[id];
        try {
            GregTechAPI.METATILEENTITIES[id] = hatch(Role.DYNAMO, 5, 4).machine();
            final ControlledBase base = new ControlledBase();
            base.setInitialValuesAsNBT(null, id);
            final IMEEnergyEndpoint source = (IMEEnergyEndpoint) base.getMetaTileEntity();
            source.machine()
                .onFirstTick(base);
            final Frame first = new Frame();
            first.add(source, 0);
            final IMEEnergyEndpoint target = hatch(Role.ENERGY, 5, 4);
            first.add(target, 0);
            first.cache.onUpdateTick();
            source.machine()
                .setEUVar(100000);
            advance(24);
            assertEquals(
                1,
                first.cache.transfers()
                    .commit(
                        first.cache,
                        first.cache.epoch(5),
                        first.cache.first(Role.DYNAMO, 5, 0),
                        first.cache.first(Role.ENERGY, 5, 0),
                        1,
                        MEEnergyTickClock.now()));
            final NBTTagCompound saved = new NBTTagCompound();
            base.writeToNBT(saved);
            source.machine()
                .onUnload();
            final ControlledBase reload = new ControlledBase();
            reload.readFromNBT(saved);
            reload.isDead = false;
            final IMEEnergyEndpoint restored = (IMEEnergyEndpoint) reload.getMetaTileEntity();
            restored.machine()
                .onFirstTick(reload);
            final Frame second = new Frame();
            second.add(restored, 0);
            final IMEEnergyEndpoint next = hatch(Role.ENERGY, 5, 4);
            second.add(next, 0);
            second.cache.onUpdateTick();
            assertEquals(99998, restored.readEU());
            assertEquals(
                18,
                restored.energyState()
                    .lossAccount()
                    .credit());
            assertEquals(
                0,
                restored.energyState()
                    .rateBudget()
                    .tokens(MEEnergyTickClock.now()));
            advance(1);
            assertEquals(
                1,
                second.cache.transfers()
                    .commit(
                        second.cache,
                        second.cache.epoch(5),
                        second.cache.first(Role.DYNAMO, 5, 0),
                        second.cache.first(Role.ENERGY, 5, 0),
                        1,
                        MEEnergyTickClock.now()));
            assertEquals(99997, restored.readEU());
            assertEquals(
                17,
                restored.energyState()
                    .lossAccount()
                    .credit());
        } finally {
            GregTechAPI.METATILEENTITIES[id] = old;
        }
    }
}
