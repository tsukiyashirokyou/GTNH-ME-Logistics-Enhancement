package com.kyou.metransferdrive.gui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
