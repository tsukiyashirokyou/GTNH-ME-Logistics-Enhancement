package com.kyou.metransferdrive.tile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DriveSlotLayoutTest {

    @Test
    public void everyPhysicalSlotBelongsToExactlyOneGroup() {
        final boolean[] seen = new boolean[DriveSlotLayout.TOTAL_SLOTS];
        for (int group = 0; group < DriveSlotLayout.GROUP_COUNT; group++) {
            for (int index = 0; index < DriveSlotLayout.SLOTS_PER_GROUP; index++) {
                final int slot = DriveSlotLayout.slot(group, index);
                assertFalse(seen[slot]);
                seen[slot] = true;
                assertEquals(group, DriveSlotLayout.group(slot));
                assertEquals(index, DriveSlotLayout.indexInGroup(slot));
            }
        }
        for (final boolean slotSeen : seen) {
            assertTrue(slotSeen);
        }
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void rejectsSlotAfterLastGroup() {
        DriveSlotLayout.slot(DriveSlotLayout.GROUP_COUNT, 0);
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void rejectsNegativePhysicalSlot() {
        DriveSlotLayout.group(-1);
    }
}
