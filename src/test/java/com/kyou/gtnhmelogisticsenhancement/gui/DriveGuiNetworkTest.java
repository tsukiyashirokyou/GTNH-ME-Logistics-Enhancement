package com.kyou.gtnhmelogisticsenhancement.gui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class DriveGuiNetworkTest {

    @Test
    public void requestRoundTripKeepsStableIdsSessionRevisionAndUnicodeAlias() {
        final DriveGuiNetwork.ActionMessage original = new DriveGuiNetwork.ActionMessage();
        original.window = 15;
        final String alias = String.join("", java.util.Collections.nCopies(64, "\u7f51"));
        original.action = new DriveGuiAction(
            DriveGuiAction.Kind.UPDATE_TARGET,
            UUID.randomUUID(),
            123,
            7,
            UUID.randomUUID(),
            alias,
            "-2147483648");
        final ByteBuf buffer = Unpooled.buffer();
        try {
            original.toBytes(buffer);
            final DriveGuiNetwork.ActionMessage decoded = new DriveGuiNetwork.ActionMessage();
            decoded.fromBytes(buffer);
            assertEquals(15, decoded.window);
            assertEquals(original.action.kind, decoded.action.kind);
            assertEquals(original.action.session, decoded.action.session);
            assertEquals(original.action.target, decoded.action.target);
            assertEquals(123, decoded.action.revision);
            assertEquals(7, decoded.action.group);
            assertEquals(alias, decoded.action.text);
            assertEquals("-2147483648", decoded.action.number);
        } finally {
            buffer.release();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownOperationIsRejectedBeforeDispatch() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            buffer.writeInt(1)
                .writeByte(255);
            new DriveGuiNetwork.ActionMessage().fromBytes(buffer);
        } finally {
            buffer.release();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void oversizedAliasIsRejectedWithoutAllocatingAnUnboundedString() {
        final ByteBuf buffer = header();
        try {
            buffer.writeShort(257);
            new DriveGuiNetwork.ActionMessage().fromBytes(buffer);
        } finally {
            buffer.release();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void truncatedAliasIsRejectedBeforeDispatch() {
        final ByteBuf buffer = header();
        try {
            buffer.writeShort(64)
                .writeByte(1);
            new DriveGuiNetwork.ActionMessage().fromBytes(buffer);
        } finally {
            buffer.release();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void trailingPayloadIsRejected() {
        final ByteBuf buffer = header();
        try {
            buffer.writeShort(0)
                .writeShort(0)
                .writeByte(1);
            new DriveGuiNetwork.ActionMessage().fromBytes(buffer);
        } finally {
            buffer.release();
        }
    }

    @Test
    public void delayedOldPageClicksAndMalformedVanillaClicksAreRejected() {
        assertTrue(DriveGuiLogic.validClick(0, 0, 0, 0));
        assertFalse(DriveGuiLogic.validClick(0, 0, 0, 1));
        assertTrue(DriveGuiLogic.validClick(8, 0, 0, 1));
        assertTrue(DriveGuiLogic.validClick(64, 0, 0, 1));
        assertFalse(DriveGuiLogic.validClick(100, 0, 0, 1));
        assertFalse(DriveGuiLogic.validClick(-999, 0, 2, 1));
        assertFalse(DriveGuiLogic.validClick(-1, 0, 2, 1));
        assertFalse(DriveGuiLogic.validClick(8, 9, 2, 1));
        assertFalse(DriveGuiLogic.validClick(-999, 1, 5, 1));
        assertFalse(DriveGuiLogic.validClick(8, 0, 7, 1));
        assertTrue(DriveGuiLogic.validClick(-999, 0, 0, 1));
        assertTrue(DriveGuiLogic.validClick(-999, 0, 5, 1));
        assertTrue(DriveGuiLogic.validClick(-999, 2, 5, 1));
        assertTrue(DriveGuiLogic.validClick(8, 1, 5, 1));
    }

    @Test
    public void numberKeyCannotSendAnEntireStackIntoAOneCellSlot() {
        assertTrue(DriveGuiLogic.validHotbarSwap(0, 2, 1));
        assertTrue(DriveGuiLogic.validHotbarSwap(0, 2, 0));
        assertFalse(DriveGuiLogic.validHotbarSwap(0, 2, 2));
        assertTrue(DriveGuiLogic.validHotbarSwap(64, 2, 64));
        assertTrue(DriveGuiLogic.validHotbarSwap(0, 0, 64));
    }

    @Test(expected = IllegalArgumentException.class)
    public void malformedUtf8IsRejectedRatherThanNormalizedAsAnAlias() {
        final ByteBuf buffer = header();
        try {
            buffer.writeShort(2)
                .writeByte(0xC3)
                .writeByte(0x28)
                .writeShort(1)
                .writeByte('0');
            new DriveGuiNetwork.ActionMessage().fromBytes(buffer);
        } finally {
            buffer.release();
        }
    }

    @Test
    public void toggleRequestRetainsStableIdAndRevision() {
        final DriveGuiNetwork.ActionMessage request = new DriveGuiNetwork.ActionMessage();
        request.window = 1;
        request.action = new DriveGuiAction(
            DriveGuiAction.Kind.TOGGLE_TARGET,
            UUID.randomUUID(),
            55,
            7,
            UUID.randomUUID(),
            "",
            "");
        final ByteBuf buffer = Unpooled.buffer();
        try {
            request.toBytes(buffer);
            final DriveGuiNetwork.ActionMessage decoded = new DriveGuiNetwork.ActionMessage();
            decoded.fromBytes(buffer);
            assertEquals(request.action.target, decoded.action.target);
            assertEquals(request.action.session, decoded.action.session);
            assertEquals(55, decoded.action.revision);
            assertEquals(7, decoded.action.group);
            assertEquals(DriveGuiAction.Kind.TOGGLE_TARGET, decoded.action.kind);
        } finally {
            buffer.release();
        }
    }

    @Test
    public void storagePriorityRequestsUseTheExistingEnvelopeAndPreserveSignedLimits() {
        for (final String number : new String[] { "-2147483648", "2147483647", "-9999999999999999999999999999999" }) {
            final DriveGuiNetwork.ActionMessage request = new DriveGuiNetwork.ActionMessage();
            request.window = 4;
            request.action = new DriveGuiAction(
                DriveGuiAction.Kind.STORAGE_PRIORITY,
                UUID.randomUUID(),
                77,
                0,
                null,
                "",
                number);
            final ByteBuf buffer = Unpooled.buffer();
            try {
                request.toBytes(buffer);
                final DriveGuiNetwork.ActionMessage decoded = new DriveGuiNetwork.ActionMessage();
                decoded.fromBytes(buffer);
                assertEquals(4, decoded.window);
                assertEquals(DriveGuiAction.Kind.STORAGE_PRIORITY, decoded.action.kind);
                assertEquals(request.action.session, decoded.action.session);
                assertEquals(77, decoded.action.revision);
                assertNull(decoded.action.target);
                assertEquals(number, decoded.action.number);
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    public void groupFillRequestsRoundTripThroughTheExistingProtocol() {
        final DriveGuiNetwork.ActionMessage request = new DriveGuiNetwork.ActionMessage();
        request.window = 42;
        request.action = new DriveGuiAction(DriveGuiAction.Kind.FILL_PERCENT, UUID.randomUUID(), 7, 6, null, "", "80");
        final ByteBuf buffer = Unpooled.buffer();
        try {
            request.toBytes(buffer);
            final DriveGuiNetwork.ActionMessage decoded = new DriveGuiNetwork.ActionMessage();
            decoded.fromBytes(buffer);
            assertEquals(42, decoded.window);
            assertEquals(DriveGuiAction.Kind.FILL_PERCENT, decoded.action.kind);
            assertEquals(6, decoded.action.group);
            assertEquals("80", decoded.action.number);
            assertEquals(request.action.session, decoded.action.session);
            assertEquals(7, decoded.action.revision);
        } finally {
            buffer.release();
        }
    }

    @Test
    public void maximumSelectionsAndUnicodeAliasesFitTheNativeSnapshotBudget() {
        final net.minecraft.nbt.NBTTagCompound state = new net.minecraft.nbt.NBTTagCompound();
        state.setInteger("storagePriority", Integer.MIN_VALUE);
        final net.minecraft.nbt.NBTTagList targets = new net.minecraft.nbt.NBTTagList();
        final net.minecraft.nbt.NBTTagList ids = new net.minecraft.nbt.NBTTagList();
        final java.util.Random random = new java.util.Random(54321);
        for (int index = 0; index < 64; index++) {
            final net.minecraft.nbt.NBTTagCompound target = new net.minecraft.nbt.NBTTagCompound();
            final String id = UUID.randomUUID()
                .toString();
            ids.appendTag(new net.minecraft.nbt.NBTTagString(id));
            target.setString("id", id);
            final StringBuilder alias = new StringBuilder();
            for (int character = 0; character < 64; character++) {
                alias.append((char) (0x4E00 + random.nextInt(20000)));
            }
            target.setString("name", alias.toString());
            target.setString("status", "OFFLINE_OR_FORBIDDEN");
            target.setInteger("priority", Integer.MAX_VALUE);
            target.setInteger("dimension", -123);
            target.setInteger("x", 29999999);
            target.setInteger("y", 255);
            target.setInteger("z", -29999999);
            targets.appendTag(target);
        }
        state.setTag("targets", targets);
        for (int group = 0; group < 8; group++) {
            state.setTag("selected" + group, ids.copy());
            state.setString("route" + group, "SPECIFIC");
            state.setInteger("fill" + group, group * 10);
        }
        final DriveGuiNetwork.StateMessage request = new DriveGuiNetwork.StateMessage();
        request.window = 1;
        request.state = state;
        final ByteBuf buffer = Unpooled.buffer();
        try {
            request.toBytes(buffer);
            assertTrue(buffer.readableBytes() <= 32773);
            final DriveGuiNetwork.StateMessage decoded = new DriveGuiNetwork.StateMessage();
            decoded.fromBytes(buffer);
            assertEquals(state, decoded.state);
        } finally {
            buffer.release();
        }
    }

    private static ByteBuf header() {
        return Unpooled.buffer()
            .writeInt(1)
            .writeByte(DriveGuiAction.Kind.UPDATE_TARGET.ordinal())
            .writeLong(0)
            .writeLong(1)
            .writeLong(1)
            .writeInt(0)
            .writeBoolean(false);
    }

    @Test
    public void inventoryEnvelopeRetainsPageVersionAndSequenceWithoutClientItemNbt() {
        final DriveGuiNetwork.ClickMessage original = new DriveGuiNetwork.ClickMessage();
        original.window = 12;
        original.session = UUID.randomUUID();
        original.pageRevision = 5;
        original.configurationRevision = 21;
        original.sequence = 8;
        original.slot = 64;
        original.button = 0;
        original.mode = 1;
        final ByteBuf buffer = Unpooled.buffer();
        try {
            original.toBytes(buffer);
            assertEquals(56, buffer.readableBytes());
            final DriveGuiNetwork.ClickMessage decoded = new DriveGuiNetwork.ClickMessage();
            decoded.fromBytes(buffer);
            assertEquals(original.session, decoded.session);
            assertEquals(5, decoded.pageRevision);
            assertEquals(21, decoded.configurationRevision);
            assertEquals(8, decoded.sequence);
            assertEquals(64, decoded.slot);
            assertEquals(1, decoded.mode);
        } finally {
            buffer.release();
        }
    }

    @Test
    public void playerShiftAndDoubleClickCannotUseAnOldPageEvenWhenTheirSlotIdIsUnchanged() {
        assertTrue(DriveGuiLogic.validClick(64, 0, 1, 0));
        assertTrue(DriveGuiLogic.validClick(64, 0, 1, 1));
        assertFalse(DriveGuiLogic.validView(0, 1, 2, 1));
        assertFalse(DriveGuiLogic.validView(0, 2, 3, 2));
        assertTrue(DriveGuiLogic.validView(2, 2, 4, 3));
    }

    @Test
    public void duplicateOutOfOrderAndInvalidSequencesCannotRepeatAnInventoryMutation() {
        assertFalse(DriveGuiLogic.validView(3, 3, 10, 10));
        assertFalse(DriveGuiLogic.validView(3, 3, 9, 10));
        assertFalse(DriveGuiLogic.validView(3, 3, -1, 10));
        assertFalse(DriveGuiLogic.validView(3, 3, 0, 0));
        assertTrue(DriveGuiLogic.validView(3, 3, 12, 10));
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidWindowIsRejectedBeforeReadingActionData() {
        final ByteBuf buffer = Unpooled.buffer()
            .writeInt(-1);
        try {
            new DriveGuiNetwork.ActionMessage().fromBytes(buffer);
        } finally {
            buffer.release();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void oversizedSnapshotIsRejectedBeforeNbtDecompression() {
        final ByteBuf buffer = Unpooled.buffer()
            .writeInt(1)
            .writeZero(32770);
        try {
            new DriveGuiNetwork.StateMessage().fromBytes(buffer);
        } finally {
            buffer.release();
        }
    }
}
