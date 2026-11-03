package com.kyou.gtnhmelogisticsenhancement.transfer;

import java.util.Arrays;

import net.minecraft.nbt.NBTTagCompound;

import com.kyou.gtnhmelogisticsenhancement.tile.DriveSlotLayout;

/** Independent group clocks with one coalesced work bit per group and fair, bounded selection. */
final class GroupTransferSchedule {

    private static final String PERIODS_KEY = "transferGroupPeriods";
    private final TransferClock[] clocks = new TransferClock[DriveSlotLayout.GROUP_COUNT];
    private final boolean[] ready = new boolean[DriveSlotLayout.GROUP_COUNT];
    private int nextGroup;

    GroupTransferSchedule() {
        for (int group = 0; group < this.clocks.length; group++) {
            this.clocks[group] = new TransferClock();
        }
    }

    /** Return only this tick's expirations; pending work is coalesced rather than queued repeatedly. */
    int tick() {
        int due = 0;
        for (int group = 0; group < this.clocks.length; group++) {
            if (this.clocks[group].tick()) {
                this.ready[group] = true;
                due |= 1 << group;
            }
        }
        return due;
    }

    int nextReadyGroup() {
        for (int offset = 0; offset < this.ready.length; offset++) {
            final int group = (this.nextGroup + offset) % this.ready.length;
            if (this.ready[group]) {
                this.nextGroup = (group + 1) % this.ready.length;
                return group;
            }
        }
        return -1;
    }

    boolean hasReadyGroup() {
        for (final boolean pending : this.ready) {
            if (pending) {
                return true;
            }
        }
        return false;
    }

    void complete(final int group) {
        checkGroup(group);
        this.ready[group] = false;
    }

    void clearReady() {
        Arrays.fill(this.ready, false);
    }

    int getSeconds(final int group) {
        checkGroup(group);
        return this.clocks[group].getSeconds();
    }

    void setSeconds(final int group, final int seconds) {
        checkGroup(group);
        this.clocks[group].setSeconds(seconds);
        this.ready[group] = false;
    }

    void writeToNBT(final NBTTagCompound data) {
        final int[] periods = new int[this.clocks.length];
        for (int group = 0; group < periods.length; group++) {
            periods[group] = this.clocks[group].getSeconds();
        }
        data.setIntArray(PERIODS_KEY, periods);
        data.removeTag("transferPeriodSeconds");
    }

    /** Old saves stored only seconds, so loading either format starts a complete countdown as before. */
    void readFromNBT(final NBTTagCompound data) {
        final boolean grouped = data.hasKey(PERIODS_KEY);
        final int[] periods = data.hasKey(PERIODS_KEY, 11) ? data.getIntArray(PERIODS_KEY) : new int[0];
        final int legacy = data.getInteger("transferPeriodSeconds");
        for (int group = 0; group < this.clocks.length; group++) {
            final int seconds = grouped ? periods.length == this.clocks.length ? periods[group] : 0 : legacy;
            this.setSeconds(group, validSeconds(seconds) ? seconds : TransferClock.DEFAULT_SECONDS);
        }
        this.nextGroup = 0;
    }

    private static boolean validSeconds(final int seconds) {
        return seconds >= TransferClock.MIN_SECONDS && seconds <= TransferClock.MAX_SECONDS;
    }

    private static void checkGroup(final int group) {
        if (group < 0 || group >= DriveSlotLayout.GROUP_COUNT) {
            throw new IndexOutOfBoundsException("Invalid drive group: " + group);
        }
    }
}
