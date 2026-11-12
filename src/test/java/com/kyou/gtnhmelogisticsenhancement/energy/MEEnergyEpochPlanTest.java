package com.kyou.gtnhmelogisticsenhancement.energy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Random;

import org.junit.Test;

import com.kyou.gtnhmelogisticsenhancement.energy.MEEnergyEpochPlan.Target;

public class MEEnergyEpochPlanTest {

    @Test
    public void bonusReservationReachesCapacityWithoutSpendingCreditBeforeCommit() {
        final MEEnergyEpochPlan plan = new MEEnergyEpochPlan();
        final Target target = new Target(1);
        plan.begin(0, 0, 20);
        plan.addSource(0, 100);
        plan.addTarget(target, 0, 24);
        plan.plan();
        assertEquals(24, plan.quota(0));
        assertEquals(24, target.credit());
        assertEquals(-1, target.lastServedTick());
        plan.record(0, 0, 0, 10, 1);
        assertEquals(14, target.credit());
        assertEquals(14, plan.quota(0));
        plan.cancel();
        assertEquals(14, target.credit());
        plan.begin(0, 2, 20);
        plan.addSource(0, 100);
        plan.addTarget(target, 0, 14);
        plan.plan();
        assertEquals(14, plan.quota(0)); // Same epoch/round does not issue another allowance.
        plan.record(0, 0, 0, 14, 3);
        assertEquals(0, target.credit());
        assertEquals(-1, plan.nextTargetIndex());
        assertEquals(3, target.lastServedTick());
    }

    @Test
    public void replanningCannotDoubleReserveTheSameUnspentCredit() {
        final MEEnergyEpochPlan plan = new MEEnergyEpochPlan();
        final Target target = new Target(1);
        for (int retry = 0; retry < 4; retry++) {
            plan.begin(0, retry, 20);
            plan.addSource(0, 1000);
            plan.addTarget(target, 0, 24);
            plan.plan();
            assertEquals(24, target.credit());
            assertEquals(24, plan.quota(0));
        }
        assertThrows(IllegalArgumentException.class, () -> plan.begin(0, 4, 20));
        assertFalse(plan.canRecord(0, 0, 0, 24, 4));
        plan.cancel();
        assertEquals(24, target.credit()); // Expiration/cancellation never burns entitlement.
    }

    @Test
    public void planningSlicesAndCancellationProduceNoFinalQuotaOrActualPayment() {
        final MEEnergyEpochPlan plan = new MEEnergyEpochPlan();
        final Target[] targets = { new Target(1), new Target(1), new Target(1) };
        plan.begin(0, 0, 20);
        plan.addSource(0, 100);
        for (final Target target : targets) plan.addTarget(target, 0, 24);
        assertFalse(plan.planNext(1));
        assertThrows(IllegalStateException.class, () -> plan.quota(0));
        assertEquals(20, targets[0].credit());
        assertEquals(-1, targets[0].lastServedTick());
        plan.cancel();
        plan.begin(0, 1, 20);
        plan.addSource(0, 100);
        for (final Target target : targets) plan.addTarget(target, 0, 24);
        int slices = 0;
        while (!plan.planNext(1)) assertTrue(++slices < 7);
        for (int index = 0; index < targets.length; index++) {
            assertEquals(24, plan.quota(index));
            assertEquals(24, targets[index].credit());
        }
    }

    @Test
    public void bonusSlicesOnlyVisitTargetsWithAnUnplannedDeficit() {
        final MEEnergyEpochPlan plan = new MEEnergyEpochPlan();
        final Target small = new Target(1), hungry = new Target(1), nearlyFull = new Target(1);
        plan.begin(0, 0, 10);
        plan.addSource(0, 100);
        plan.addTarget(small, 0, 4);
        plan.addTarget(hungry, 0, 24);
        plan.addTarget(nearlyFull, 0, 1);
        int visits = 1;
        while (!plan.planNext(1)) visits++;
        assertEquals(5, visits); // Three primary visits and two bonus visits for the hungry target.
        assertEquals(4, plan.quota(0));
        assertEquals(24, plan.quota(1));
        assertEquals(1, plan.quota(2));
        assertEquals(4, small.credit());
        assertEquals(24, hungry.credit());
        assertEquals(1, nearlyFull.credit());
    }

    @Test
    public void exhaustedSupplySkipsBonusWorkButStillGrantsEveryPrimaryQuantum() {
        final MEEnergyEpochPlan plan = new MEEnergyEpochPlan();
        final Target[] targets = { new Target(1), new Target(1), new Target(1) };
        plan.begin(0, 0, 10);
        plan.addSource(0, 10);
        for (final Target target : targets) plan.addTarget(target, 0, 24);
        assertFalse(plan.planNext(1));
        assertFalse(plan.planNext(1));
        assertTrue(plan.planNext(1));
        assertEquals(10, plan.quota(0));
        assertEquals(0, plan.quota(1));
        assertEquals(0, plan.quota(2));
        for (final Target target : targets) assertEquals(10, target.credit());
    }

    @Test
    public void cancellingACompactedBonusPassResumesAtTheNextUnmetTarget() {
        final MEEnergyEpochPlan plan = new MEEnergyEpochPlan();
        final Target[] targets = { new Target(1), new Target(1), new Target(1) };
        plan.begin(0, 0, 10);
        plan.addSource(0, 100);
        plan.addTarget(targets[0], 0, 1);
        plan.addTarget(targets[1], 0, 24);
        plan.addTarget(targets[2], 0, 24);
        assertFalse(plan.planNext(4)); // Primary pass, then the first unmet target's bonus.
        plan.startCancel();
        assertFalse(plan.cancelNext(1));
        plan.startCancel(); // Repeated invalidations must preserve both cleanup progress and resume order.
        assertFalse(plan.cancelNext(1));
        assertTrue(plan.cancelNext(1));
        plan.begin(1, 10, 10);
        plan.addSource(0, 1);
        for (final Target target : targets) plan.addTarget(target, 0, 24);
        plan.plan();
        assertEquals(0, plan.quota(0));
        assertEquals(0, plan.quota(1));
        assertEquals(1, plan.quota(2));
    }

    @Test
    public void sliceableCancellationImmediatelyRevokesQuotasAndReleasesOnlyVisitedOwners() {
        final MEEnergyEpochPlan plan = new MEEnergyEpochPlan();
        final Target[] targets = { new Target(1), new Target(1), new Target(1) };
        plan.begin(0, 0, 20);
        plan.addSource(0, 100);
        for (final Target target : targets) plan.addTarget(target, 0, 24);
        plan.plan();
        assertTrue(plan.canRecord(0, 0, 0, 24, 0));
        plan.startCancel();
        assertFalse(plan.isReady());
        assertFalse(plan.canRecord(0, 0, 0, 24, 0));
        assertThrows(IllegalStateException.class, () -> plan.quota(0));
        assertThrows(IllegalStateException.class, () -> plan.addSource(0, 1));
        assertThrows(IllegalStateException.class, () -> plan.addTarget(new Target(1), 0, 24));
        assertThrows(IllegalArgumentException.class, () -> plan.cancelNext(0));
        assertThrows(IllegalStateException.class, targets[0]::clearCredit);
        assertFalse(plan.cancelNext(1));
        targets[0].clearCredit();
        assertThrows(IllegalStateException.class, targets[1]::clearCredit);
        assertThrows(IllegalStateException.class, targets[2]::clearCredit);
        plan.startCancel();
        assertFalse(plan.cancelNext(1));
        targets[1].clearCredit();
        assertTrue(plan.cancelNext(1));
        targets[2].clearCredit();
        assertEquals(0, plan.targetCount());
        plan.begin(0, 1, 20);
        plan.addSource(0, 100);
        for (final Target target : targets) plan.addTarget(target, 0, 24);
        plan.plan();
        for (int index = 0; index < targets.length; index++) assertEquals(0, plan.quota(index));
    }

    @Test
    public void sufficientSupplyRefillsRepeatedNativeMulti125PercentDemand() {
        final MEEnergyEpochPlan plan = new MEEnergyEpochPlan();
        final Target target = new Target(4);
        long stock = target.capacity();
        for (int epoch = 0; epoch < 100; epoch++) {
            final long demand = 5 * 10; // A native 4 A Multi hatch can be consumed at 125% nominal power.
            assertTrue(stock >= demand);
            stock -= demand;
            plan.begin(epoch, epoch * 10L, 10);
            plan.addSource(0, 100);
            plan.addTarget(target, 0, target.capacity() - stock);
            plan.plan();
            final long supplied = plan.quota(0);
            assertEquals(demand, supplied);
            plan.record(epoch, 0, 0, supplied, epoch * 10L);
            stock += supplied;
            assertEquals(target.capacity(), stock);
        }
    }

    @Test
    public void knownRunning125PercentLoadCannotBeStarvedByTwentyColdCaches() {
        final MEEnergyEpochPlan plan = new MEEnergyEpochPlan();
        final Target running = new Target(4);
        final Target[] cold = new Target[20];
        for (int index = 0; index < cold.length; index++) cold[index] = new Target(4);
        long stock = running.capacity();
        for (int epoch = 0; epoch < 100; epoch++) {
            assertTrue(stock >= 50);
            stock -= 50;
            plan.begin(epoch, epoch * 10L, 10);
            plan.addSource(0, 50); // Exactly enough for the running load; no spare precharge power.
            for (final Target target : cold) plan.addTarget(target, 0, target.capacity());
            plan.addTarget(running, 0, running.capacity() - stock, 50);
            plan.plan();
            for (int index = 0; index < cold.length; index++) assertEquals(0, plan.quota(index));
            assertEquals(50, plan.quota(cold.length));
            plan.record(epoch, cold.length, 0, 50, epoch * 10L);
            stock += 50;
            assertEquals(running.capacity(), stock);
        }
    }

    @Test
    public void insufficientMaintenanceSupplyStillPreservesNominalWeights() {
        final MEEnergyEpochPlan plan = new MEEnergyEpochPlan();
        final Target small = new Target(1), large = new Target(3), cold = new Target(100);
        final long[] delivered = new long[3];
        for (int epoch = 0; epoch < 100; epoch++) {
            plan.begin(epoch, epoch * 20L, 20);
            plan.addSource(0, 40);
            plan.addTarget(small, 0, 24, 24);
            plan.addTarget(large, 0, 72, 72);
            plan.addTarget(cold, 0, cold.capacity());
            plan.plan();
            assertEquals(0, plan.quota(2));
            int index;
            while ((index = plan.nextTargetIndex()) >= 0) {
                final long quantity = plan.quota(index);
                delivered[index] += quantity;
                plan.record(epoch, index, 0, quantity, epoch * 20L);
            }
        }
        assertEquals(1000, delivered[0]);
        assertEquals(3000, delivered[1]);
        assertEquals(0, delivered[2]);
    }

    @Test
    public void sparePowerPrechargesUsingTheOriginalWeightsAfterMaintenance() {
        final MEEnergyEpochPlan plan = new MEEnergyEpochPlan();
        final Target running = new Target(1), small = new Target(1), large = new Target(3);
        final long[] delivered = new long[3];
        for (int epoch = 0; epoch < 100; epoch++) {
            plan.begin(epoch, epoch * 20L, 20);
            plan.addSource(0, 60);
            plan.addTarget(running, 0, 20, 20);
            plan.addTarget(small, 0, 24);
            plan.addTarget(large, 0, 72);
            plan.plan();
            assertEquals(20, plan.quota(0));
            int index;
            while ((index = plan.nextTargetIndex()) >= 0) {
                final long quantity = plan.quota(index);
                delivered[index] += quantity;
                plan.record(epoch, index, 0, quantity, epoch * 20L);
            }
        }
        assertEquals(2000, delivered[0]);
        assertEquals(1000, delivered[1]);
        assertEquals(3000, delivered[2]);
    }

    @Test
    public void maintenanceAndPrechargeReplansCannotReplayEitherInitialCreditGrant() {
        final MEEnergyEpochPlan plan = new MEEnergyEpochPlan();
        final Target running = new Target(1);
        for (int retry = 0; retry <= 3; retry++) {
            plan.begin(0, retry, 10);
            plan.addSource(0, 2);
            plan.addTarget(running, 0, 24, 2);
            plan.plan();
            assertEquals(2, plan.quota(0));
            assertEquals(20, running.credit()); // One maintenance and one precharge primary grant in total.
        }
        plan.record(0, 0, 0, 2, 3);
        assertEquals(18, running.credit());
        plan.startCancel();
        assertFalse(plan.canRecord(0, 0, 0, 1, 3));
        assertTrue(plan.cancelNext(1));
        running.clearCredit();
    }

    @Test
    public void maintenanceCompletedTargetsAreNotVisitedAgainForPrecharge() {
        final MEEnergyEpochPlan plan = new MEEnergyEpochPlan();
        plan.begin(0, 0, 10);
        plan.addSource(0, 100);
        plan.addTarget(new Target(1), 0, 4, 4);
        plan.addTarget(new Target(1), 0, 1, 1);
        assertFalse(plan.planNext(1));
        assertTrue(plan.planNext(1));
        assertEquals(4, plan.quota(0));
        assertEquals(1, plan.quota(1));
    }

    @Test
    public void unfinishedServiceQuantumCannotDivertMaintenanceReserveIntoColdPrecharge() {
        for (final int period : new int[] { 1, 5, 10, 20 }) {
            final MEEnergyEpochPlan plan = new MEEnergyEpochPlan();
            final Target running = new Target(4);
            final long partial = 4L * period - 1;
            plan.begin(0, 0, period);
            plan.addSource(0, partial);
            plan.addTarget(running, 0, partial);
            plan.plan();
            plan.record(0, 0, 0, partial, 0); // Leave only 1 EU in the current weighted service quantum.
            plan.begin(1, period, period);
            plan.addSource(0, running.capacity());
            plan.addTarget(new Target(1), 0, 24); // This cold target is first in the ordinary rotation.
            plan.addTarget(running, 0, running.capacity(), running.capacity());
            plan.plan();
            assertEquals("period " + period, 0, plan.quota(0));
            assertEquals("period " + period, running.capacity(), plan.quota(1));
            assertEquals(1, plan.nextTargetIndex());
            assertEquals(running.capacity(), plan.executionLimit(1));
        }
    }

    @Test
    public void smallSourceFragmentsFinishMaintenanceBeforeAnyColdPrecharge() {
        final MEEnergyEpochPlan plan = new MEEnergyEpochPlan();
        plan.begin(0, 0, 10);
        plan.addSource(0, 48);
        plan.addTarget(new Target(1), 0, 24, 12);
        plan.addTarget(new Target(1), 0, 24);
        plan.plan();
        assertEquals(24, plan.quota(0));
        assertEquals(24, plan.quota(1));
        for (int fragment = 0; fragment < 4; fragment++) {
            assertEquals(0, plan.nextTargetIndex());
            assertEquals(12 - fragment * 3, plan.executionLimit(0));
            plan.record(0, 0, 0, 3, 0);
        }
        assertEquals(12, plan.quota(0));
        assertEquals(12, plan.executionLimit(0));
        assertEquals(1, plan.nextTargetIndex()); // Maintenance is paid; ordinary precharge may now proceed.
    }

    @Test
    public void fragmentedExecutionRotatesBetweenRunningTargetsBeforeColdTargets() {
        final MEEnergyEpochPlan plan = new MEEnergyEpochPlan();
        plan.begin(0, 0, 10);
        plan.addSource(0, 200);
        plan.addTarget(new Target(1), 0, 24, 6);
        plan.addTarget(new Target(3), 0, 72, 12);
        plan.addTarget(new Target(1), 0, 24);
        plan.plan();
        final int[] expected = { 0, 1, 0, 1, 1, 1 };
        for (final int index : expected) {
            assertEquals(index, plan.nextTargetIndex());
            assertTrue(plan.executionLimit(index) >= 3);
            plan.record(0, index, 0, 3, 0);
        }
        assertEquals(18, plan.quota(0));
        assertEquals(60, plan.quota(1));
        assertEquals(2, plan.nextTargetIndex());
    }

    @Test
    public void partiallyPaidMaintenanceIsReplannedWithoutReplayAfterSlicedCancellation() {
        final MEEnergyEpochPlan plan = new MEEnergyEpochPlan();
        final Target running = new Target(1), cold = new Target(1);
        plan.begin(0, 0, 10);
        plan.addSource(0, 100);
        plan.addTarget(running, 0, 24, 12);
        plan.addTarget(cold, 0, 24);
        plan.plan();
        assertEquals(0, plan.nextTargetIndex());
        plan.record(0, 0, 0, 5, 0);
        assertEquals(7, plan.executionLimit(0));
        assertEquals(19, running.credit());
        plan.startCancel();
        assertThrows(IllegalStateException.class, () -> plan.executionLimit(0));
        assertFalse(plan.cancelNext(1));
        plan.startCancel();
        assertTrue(plan.cancelNext(1));
        plan.begin(0, 1, 10);
        plan.addSource(0, 100);
        plan.addTarget(running, 0, 19, 7);
        plan.addTarget(cold, 0, 24);
        plan.plan();
        assertEquals(19, plan.quota(0));
        assertEquals(19, running.credit());
        assertEquals(0, plan.nextTargetIndex());
        assertEquals(7, plan.executionLimit(0));
        plan.record(0, 0, 0, 7, 1);
        assertEquals(12, running.credit());
        assertEquals(1, plan.nextTargetIndex());
    }

    @Test
    public void unfinishedExecutionPrefixGetsTheNextPlanningAndExecutionTurn() {
        final MEEnergyEpochPlan plan = new MEEnergyEpochPlan();
        final Target[] targets = { new Target(1), new Target(1), new Target(1) };
        for (int epoch = 0; epoch < 6; epoch++) {
            plan.begin(epoch, epoch * 20, 20);
            plan.addSource(0, 72);
            for (final Target target : targets) plan.addTarget(target, 0, 24);
            plan.plan();
            final int index = plan.nextTargetIndex();
            assertEquals(epoch % 3, index);
            plan.record(epoch, index, 0, plan.quota(index), epoch * 20);
            // A hard work slice only completed this first target; begin() cancels the remaining plan.
        }
    }

    @Test
    public void constrainedSupplyRotatesAndPreservesNominalWeightOverRepeatedEpochs() {
        final MEEnergyEpochPlan plan = new MEEnergyEpochPlan();
        final Target small = new Target(1);
        final Target large = new Target(3);
        final long[] delivered = new long[2];
        for (int epoch = 0; epoch < 100; epoch++) {
            final long now = epoch * 20;
            plan.begin(epoch, now, 20);
            plan.addSource(0, 40);
            plan.addTarget(small, 0, 24);
            plan.addTarget(large, 0, 72);
            plan.plan();
            int index;
            while ((index = plan.nextTargetIndex()) >= 0) {
                final long quantity = plan.quota(index);
                delivered[index] += quantity;
                plan.record(epoch, index, 0, quantity, now);
            }
        }
        assertEquals(1000, delivered[0]);
        assertEquals(3000, delivered[1]);
        assertTrue(small.credit() <= 24);
        assertTrue(large.credit() <= 72);
    }

    @Test
    public void defaultTargetsParticipateOnlyInTheirOwnColorPool() {
        final MEEnergyEpochPlan plan = new MEEnergyEpochPlan();
        final Target first = new Target(1);
        final Target second = new Target(1);
        plan.begin(0, 0, 20);
        plan.addSource(0, 10);
        plan.addSource(1, 10);
        plan.addTarget(first, 0, 10);
        plan.addTarget(second, 2, 10);
        plan.plan();
        assertEquals(10, plan.quota(0));
        assertEquals(0, plan.quota(1));
        assertEquals(10, plan.colorFlowLimit(0, 0, 100));
        assertEquals(0, plan.colorFlowLimit(1, 0, 100));
        assertEquals(0, plan.colorFlowLimit(0, 2, 100));
        assertFalse(plan.canRecord(0, 1, 1, 1, 0));
        assertFalse(plan.canRecord(0, 0, 1, 1, 0));
        plan.record(0, 0, 0, 10, 0);
        assertEquals(0, first.credit());
        assertEquals(10, second.credit()); // The unmatched target still receives its primary credit.
    }

    @Test
    public void unrelatedSupplyDoesNotKeepUnmatchedTargetsInBonusPasses() {
        final MEEnergyEpochPlan plan = new MEEnergyEpochPlan();
        final Target[] targets = { new Target(1), new Target(1), new Target(1) };
        plan.begin(0, 0, 1);
        plan.addSource(1, 100);
        for (final Target target : targets) plan.addTarget(target, 0, 24);
        int visits = 1;
        while (!plan.planNext(1)) visits++;
        assertEquals(3, visits);
        for (int index = 0; index < targets.length; index++) {
            assertEquals(1, targets[index].credit());
            assertEquals(0, plan.quota(index));
        }
    }

    @Test
    public void largeSnapshotsPromoteAndExecuteWithoutSummingActualBalancesIntoLong() {
        final long power = 9007199254740992L;
        final long capacity = power * 24;
        final MEEnergyEpochPlan plan = new MEEnergyEpochPlan();
        plan.begin(0, 0, 20);
        for (int index = 0; index < 50; index++) {
            plan.addSource(1, capacity);
            plan.addTarget(new Target(power), 1, capacity);
        }
        plan.plan();
        assertTrue(plan.usesWideArithmetic());
        int executed = 0;
        int index;
        while ((index = plan.nextTargetIndex()) >= 0) {
            assertEquals(capacity, plan.quota(index));
            plan.record(0, index, 1, capacity, 0);
            executed++;
        }
        assertEquals(50, executed);
    }

    @Test
    public void fullOfflineOrMigratedTargetsCannotAccumulateIdleCredit() {
        final MEEnergyEpochPlan plan = new MEEnergyEpochPlan();
        final Target target = new Target(1);
        plan.begin(0, 0, 20);
        plan.addTarget(target, 0, 24);
        plan.plan(); // No supply: only the primary quantum accrues.
        assertEquals(20, target.credit());
        assertThrows(IllegalStateException.class, target::clearCredit);
        plan.cancel();
        target.clearCredit();
        plan.begin(0, 1, 20);
        plan.addSource(0, 100);
        plan.addTarget(target, 0, 24);
        plan.plan();
        assertEquals(20, plan.quota(0)); // Only the previously unissued bonus round can grant credit.
        plan.cancel();
        target.clearCredit();
        plan.begin(0, 2, 20);
        plan.addSource(0, 100);
        plan.addTarget(target, 0, 24);
        plan.plan();
        assertEquals(0, plan.quota(0)); // Neither grant stamp can be reissued by toggling.
        plan.cancel();
        target.reset(); // Migration creates a fresh fairness lifetime, not an EU balance.
        plan.begin(1, 20, 20);
        plan.addSource(0, 100);
        plan.addTarget(target, 0, 0);
        plan.plan();
        assertEquals(0, target.credit());
        assertEquals(0, plan.quota(0));
    }

    @Test
    public void allSupportedPeriodsFillEveryFeasibleGapAndCommitExactlyThePlannedFlow() {
        final Random random = new Random(24120);
        final MEEnergyEpochPlan plan = new MEEnergyEpochPlan();
        for (int sample = 0; sample < 400; sample++) {
            final long now = sample * 20L;
            final int period = new int[] { 1, 5, 10, 20 }[sample % 4];
            final long[] supplies = new long[17];
            final int count = 1 + random.nextInt(6);
            final int[] colors = new int[count];
            final long[] demand = new long[count];
            plan.begin(sample, now, period);
            for (int source = 0; source < 8; source++) {
                final int color = random.nextInt(5);
                final int quantity = random.nextInt(31);
                supplies[color] += quantity;
                plan.addSource(color, quantity);
            }
            for (int target = 0; target < count; target++) {
                final Target state = new Target(1 + random.nextInt(4));
                colors[target] = random.nextInt(5);
                demand[target] = random.nextInt((int) state.capacity() + 1);
                plan.addTarget(state, colors[target], demand[target]);
            }
            final long expected = MEEnergyGroupPlannerTest.minCutOracle(supplies, colors, demand);
            long input = 0;
            for (final long supply : supplies) input += supply;
            int slices = 0;
            while (!plan.planNext(1)) assertTrue(++slices <= count * 24);
            long delivered = 0;
            int target;
            while ((target = plan.nextTargetIndex()) >= 0) {
                final long wanted = plan.quota(target);
                assertTrue(wanted <= demand[target]);
                for (int source = 0; source < 17; source++) {
                    final long fragment = plan.colorFlowLimit(source, colors[target], plan.quota(target));
                    if (fragment == 0) continue;
                    plan.record(sample, target, source, fragment, now);
                    supplies[source] -= fragment;
                    assertTrue(supplies[source] >= 0);
                    delivered += fragment;
                }
                assertEquals(0, plan.quota(target));
            }
            long remaining = 0;
            for (final long supply : supplies) remaining += supply;
            assertEquals(input, remaining + delivered);
            assertEquals("sample " + sample, expected, delivered);
        }
    }

    @Test
    public void supplyBelowOneQuantumStillPreservesLongTermWeights() {
        final MEEnergyEpochPlan plan = new MEEnergyEpochPlan();
        final Target small = new Target(4), large = new Target(16);
        final long[] delivered = new long[2];
        for (int epoch = 0; epoch < 1000; epoch++) {
            plan.begin(epoch, epoch * 20L, 20);
            plan.addSource(0, 76);
            plan.addTarget(small, 0, 96);
            plan.addTarget(large, 0, 384);
            plan.plan();
            int index;
            while ((index = plan.nextTargetIndex()) >= 0) {
                final long q = plan.quota(index);
                delivered[index] += q;
                plan.record(epoch, index, 0, q, epoch * 20L);
            }
        }
        assertEquals(76000, delivered[0] + delivered[1]);
        assertTrue(Math.abs(delivered[0] * 4 - delivered[1]) <= 400);
    }

    @Test
    public void serviceProgressModuloNeverOverflowsNearLongCapacity() {
        final long power = Long.MAX_VALUE / 24;
        final Target target = new Target(power);
        final MEEnergyEpochPlan plan = new MEEnergyEpochPlan();
        plan.begin(0, 0, 20);
        plan.addSource(0, target.capacity());
        plan.addTarget(target, 0, target.capacity());
        plan.plan();
        plan.record(0, 0, 0, power * 18, 0);
        plan.cancel();
        plan.begin(1, 20, 20);
        plan.addSource(0, target.capacity());
        plan.addTarget(target, 0, target.capacity());
        plan.plan();
        assertEquals(power * 22, plan.quota(0));
        plan.record(1, 0, 0, power * 19, 20);
        plan.cancel();
        plan.begin(2, 40, 20);
        plan.addSource(0, target.capacity());
        plan.addTarget(target, 0, target.capacity());
        plan.plan();
        assertEquals(power * 23, plan.quota(0));
    }

    @Test
    public void invalidSnapshotsDeadlinesAndStaleCommitsAreRejected() {
        final MEEnergyEpochPlan plan = new MEEnergyEpochPlan();
        final Target target = new Target(1);
        assertThrows(IllegalArgumentException.class, () -> new Target(0));
        assertThrows(ArithmeticException.class, () -> new Target(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> plan.begin(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> plan.begin(0, 0, 21));
        plan.begin(5, 100, 20);
        plan.addSource(0, 100);
        plan.addTarget(target, 0, 24);
        assertThrows(IllegalArgumentException.class, () -> plan.addTarget(new Target(1), 0, 24, -1));
        assertThrows(IllegalArgumentException.class, () -> plan.addTarget(new Target(1), 0, 24, 25));
        assertThrows(IllegalArgumentException.class, () -> plan.addTarget(target, 0, 24));
        assertThrows(IllegalArgumentException.class, () -> plan.addTarget(new Target(1), 0, 25));
        assertThrows(IllegalArgumentException.class, () -> plan.planNext(0));
        plan.plan();
        assertFalse(plan.canRecord(4, 0, 0, 1, 100));
        assertFalse(plan.canRecord(5, 0, 0, 25, 100));
        assertFalse(plan.canRecord(5, 0, 0, 0, 100));
        assertThrows(IllegalStateException.class, () -> plan.record(5, 0, 0, 25, 100));
        assertThrows(IllegalArgumentException.class, () -> plan.begin(4, 100, 20));
        assertThrows(IllegalArgumentException.class, () -> plan.begin(5, 100, 10));
        assertEquals(103, plan.deadlineTick());
        plan.record(5, 0, 0, 1, 103);
        assertFalse(plan.canRecord(5, 0, 0, 1, 102));
        assertFalse(plan.canRecord(5, 0, 0, 1, 104));
        assertEquals(23, target.credit());
        plan.cancel();
        assertFalse(plan.isReady());
    }
}
