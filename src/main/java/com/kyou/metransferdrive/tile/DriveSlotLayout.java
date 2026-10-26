package com.kyou.metransferdrive.tile;

/** Stable physical slot numbering for the eight future routing groups. */
public final class DriveSlotLayout {

    public static final int GROUP_COUNT = 8;
    public static final int SLOTS_PER_GROUP = 8;
    public static final int TOTAL_SLOTS = GROUP_COUNT * SLOTS_PER_GROUP;

    private DriveSlotLayout() {}

    public static int slot(final int group, final int indexInGroup) {
        if (group < 0 || group >= GROUP_COUNT || indexInGroup < 0 || indexInGroup >= SLOTS_PER_GROUP) {
            throw new IndexOutOfBoundsException("Invalid drive group or slot");
        }
        return group * SLOTS_PER_GROUP + indexInGroup;
    }

    public static int group(final int slot) {
        checkSlot(slot);
        return slot / SLOTS_PER_GROUP;
    }

    public static int indexInGroup(final int slot) {
        checkSlot(slot);
        return slot % SLOTS_PER_GROUP;
    }

    private static void checkSlot(final int slot) {
        if (slot < 0 || slot >= TOTAL_SLOTS) {
            throw new IndexOutOfBoundsException("Invalid drive slot: " + slot);
        }
    }
}
