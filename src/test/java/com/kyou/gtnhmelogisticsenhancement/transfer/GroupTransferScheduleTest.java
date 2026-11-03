package com.kyou.gtnhmelogisticsenhancement.transfer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import com.kyou.gtnhmelogisticsenhancement.tile.DriveSlotLayout;

public class GroupTransferScheduleTest {

    @Test
    public void newAndMissingNbtPeriodsWaitOneHourBeforeBecomingReady() {
        final GroupTransferSchedule fresh = new GroupTransferSchedule();
        final GroupTransferSchedule loaded = new GroupTransferSchedule();
        loaded.setSeconds(0, 1);
        loaded.readFromNBT(new NBTTagCompound());
        assertAllDefault(fresh);
        assertAllDefault(loaded);
        for (int tick = 1; tick < 72000; tick++) {
            assertEquals(0, fresh.tick());
            assertEquals(0, loaded.tick());
        }
        assertEquals((1 << DriveSlotLayout.GROUP_COUNT) - 1, fresh.tick());
        assertEquals((1 << DriveSlotLayout.GROUP_COUNT) - 1, loaded.tick());
    }

    @Test
    public void existingSixtySecondPeriodsRemainUnchangedAcrossLegacyMigrationAndGroupedReload() {
        final NBTTagCompound legacy = new NBTTagCompound();
        legacy.setInteger("transferPeriodSeconds", 60);
        final GroupTransferSchedule migrated = new GroupTransferSchedule();
        migrated.readFromNBT(legacy);
        final NBTTagCompound grouped = new NBTTagCompound();
        migrated.writeToNBT(grouped);
        final GroupTransferSchedule restored = new GroupTransferSchedule();
        restored.readFromNBT(grouped);
        for (int group = 0; group < DriveSlotLayout.GROUP_COUNT; group++) {
            assertEquals(60, migrated.getSeconds(group));
            assertEquals(60, restored.getSeconds(group));
        }
        for (int tick = 1; tick < 1200; tick++) {
            assertEquals(0, migrated.tick());
            assertEquals(0, restored.tick());
        }
        assertEquals((1 << DriveSlotLayout.GROUP_COUNT) - 1, migrated.tick());
        assertEquals((1 << DriveSlotLayout.GROUP_COUNT) - 1, restored.tick());
    }

    @Test
    public void invalidLegacyPeriodsUseTheNewOneHourDefault() {
        for (final int invalid : new int[] { -1, 0, 86401, Integer.MAX_VALUE }) {
            final NBTTagCompound legacy = new NBTTagCompound();
            legacy.setInteger("transferPeriodSeconds", invalid);
            final GroupTransferSchedule schedule = new GroupTransferSchedule();
            schedule.readFromNBT(legacy);
            assertAllDefault(schedule);
        }
    }

    @Test
    public void groupsExpireIndependentlyAndChangingOneDoesNotResetAnother() {
        final GroupTransferSchedule schedule = new GroupTransferSchedule();
        schedule.setSeconds(0, 1);
        schedule.setSeconds(1, 2);
        for (int tick = 0; tick < 19; tick++) {
            assertEquals(0, schedule.tick());
        }
        schedule.setSeconds(1, 3);
        assertEquals(1, schedule.tick());
        assertEquals(0, schedule.nextReadyGroup());
        schedule.complete(0);
        for (int tick = 21; tick < 79; tick++) {
            assertEquals(tick % 20 == 0 ? 1 : 0, schedule.tick());
            schedule.complete(0);
        }
        assertEquals(2, schedule.tick());
        assertEquals(1, schedule.nextReadyGroup());
    }

    @Test
    public void busyGroupsRotateFairlyAndRepeatedExpirationsNeverBuildABacklog() {
        final GroupTransferSchedule schedule = new GroupTransferSchedule();
        for (int group = 0; group < DriveSlotLayout.GROUP_COUNT; group++) {
            schedule.setSeconds(group, 1);
        }
        for (int tick = 0; tick < 200; tick++) {
            schedule.tick();
        }
        for (int round = 0; round < 3; round++) {
            for (int group = 0; group < DriveSlotLayout.GROUP_COUNT; group++) {
                assertEquals(group, schedule.nextReadyGroup());
            }
        }
        for (int group = 0; group < DriveSlotLayout.GROUP_COUNT; group++) {
            schedule.complete(group);
        }
        assertFalse(schedule.hasReadyGroup());
        assertEquals(-1, schedule.nextReadyGroup());
        for (int tick = 0; tick < 19; tick++) {
            assertEquals(0, schedule.tick());
        }
        assertEquals((1 << DriveSlotLayout.GROUP_COUNT) - 1, schedule.tick());
    }

    @Test
    public void cancelingOneGroupLeavesOtherDueWorkReady() {
        final GroupTransferSchedule schedule = new GroupTransferSchedule();
        schedule.setSeconds(0, 1);
        schedule.setSeconds(1, 1);
        for (int tick = 0; tick < 20; tick++) {
            schedule.tick();
        }
        schedule.setSeconds(0, 5);
        assertTrue(schedule.hasReadyGroup());
        assertEquals(1, schedule.nextReadyGroup());
    }

    @Test
    public void legacyPeriodMigratesToEveryGroupWithACompleteCountdown() {
        final NBTTagCompound legacy = new NBTTagCompound();
        legacy.setInteger("transferPeriodSeconds", 2);
        final GroupTransferSchedule schedule = new GroupTransferSchedule();
        schedule.readFromNBT(legacy);
        for (int group = 0; group < DriveSlotLayout.GROUP_COUNT; group++) {
            assertEquals(2, schedule.getSeconds(group));
        }
        for (int tick = 0; tick < 39; tick++) {
            assertEquals(0, schedule.tick());
        }
        assertEquals((1 << DriveSlotLayout.GROUP_COUNT) - 1, schedule.tick());
    }

    @Test
    public void nbtRoundTripKeepsEachPeriodAndRestartsCountdownAsLegacyLoadingDid() {
        final GroupTransferSchedule original = new GroupTransferSchedule();
        final int[] periods = new int[DriveSlotLayout.GROUP_COUNT];
        for (int group = 0; group < periods.length; group++) {
            periods[group] = group + 1;
            original.setSeconds(group, periods[group]);
        }
        for (int tick = 0; tick < 19; tick++) {
            original.tick();
        }
        final NBTTagCompound data = new NBTTagCompound();
        data.setInteger("transferPeriodSeconds", 123);
        original.writeToNBT(data);
        assertFalse(data.hasKey("transferPeriodSeconds"));
        assertArrayEquals(periods, data.getIntArray("transferGroupPeriods"));
        final GroupTransferSchedule restored = new GroupTransferSchedule();
        restored.readFromNBT(data);
        for (int group = 0; group < periods.length; group++) {
            assertEquals(periods[group], restored.getSeconds(group));
        }
        for (int tick = 0; tick < 19; tick++) {
            assertEquals(0, restored.tick());
        }
        assertEquals(1, restored.tick());
        final NBTTagCompound rewritten = new NBTTagCompound();
        restored.writeToNBT(rewritten);
        assertEquals(data, rewritten);
    }

    @Test
    public void damagedGroupedDataNeverFallsBackToTheOldGlobalSetting() {
        final NBTTagCompound data = new NBTTagCompound();
        data.setInteger("transferPeriodSeconds", 9);
        data.setString("transferGroupPeriods", "damaged");
        final GroupTransferSchedule schedule = new GroupTransferSchedule();
        schedule.readFromNBT(data);
        assertAllDefault(schedule);
        data.setIntArray("transferGroupPeriods", new int[] { 2 });
        schedule.readFromNBT(data);
        assertAllDefault(schedule);
        final int[] periods = new int[DriveSlotLayout.GROUP_COUNT];
        Arrays.fill(periods, 2);
        periods[1] = 0;
        periods[2] = Integer.MAX_VALUE;
        data.setIntArray("transferGroupPeriods", periods);
        schedule.readFromNBT(data);
        assertEquals(2, schedule.getSeconds(0));
        assertEquals(TransferClock.DEFAULT_SECONDS, schedule.getSeconds(1));
        assertEquals(TransferClock.DEFAULT_SECONDS, schedule.getSeconds(2));
        assertEquals(2, schedule.getSeconds(DriveSlotLayout.GROUP_COUNT - 1));
    }

    private static void assertAllDefault(final GroupTransferSchedule schedule) {
        for (int group = 0; group < DriveSlotLayout.GROUP_COUNT; group++) {
            assertEquals(3600, schedule.getSeconds(group));
        }
    }
}
