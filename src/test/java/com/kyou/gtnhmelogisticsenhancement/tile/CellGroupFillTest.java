package com.kyou.gtnhmelogisticsenhancement.tile;

import static org.junit.Assert.*;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import appeng.api.config.AccessRestriction;
import appeng.api.storage.IMEInventory;

/** Production tile/configuration API with the same substituted Grid boundary as the Handler regressions. */
public class CellGroupFillTest {

    @Test
    public void allGroupsPersistWithoutRebuildingHandlersOrChangingTheirAccess() throws Exception {
        final CellHandlerCacheTest.TestDrive drive = new CellHandlerCacheTest.TestDrive();
        final IMEInventory<?>[] handlers = new IMEInventory<?>[64];
        for (int slot = 0; slot < 64; slot++) {
            drive.install(slot, CellHandlerCacheTest.TYPES[slot % 3], 100 + slot);
        }
        for (int slot = 0; slot < 64; slot++) {
            handlers[slot] = drive.getNativeCellHandler(slot);
        }
        final int constructions = drive.constructions, publications = drive.publications;
        final long revision = drive.getTransferConfigurationRevision();
        for (int group = 0; group < 8; group++) drive.setTransferGroupFillPercent(group, group * 10);
        assertEquals(revision + 8, drive.getTransferConfigurationRevision());
        for (int slot = 0; slot < 64; slot++) {
            assertSame(handlers[slot], drive.getNativeCellHandler(slot));
            CellHandlerCacheTest.assertCount(drive, slot, CellHandlerCacheTest.TYPES[slot % 3], 100 + slot);
        }
        assertEquals(constructions, drive.constructions);
        assertEquals(publications, drive.publications);
        assertEquals(AccessRestriction.READ_WRITE, drive.getAccessMode());
        final NBTTagCompound saved = new NBTTagCompound();
        drive.writeTransferState(saved);
        final CellHandlerCacheTest.TestDrive restored = new CellHandlerCacheTest.TestDrive();
        restored.readTransferState(saved);
        for (int group = 0; group < 8; group++) assertEquals(group * 10, restored.getTransferGroupFillPercent(group));
        final long unchanged = restored.getTransferConfigurationRevision();
        restored.setTransferGroupFillPercent(4, 40);
        assertEquals(unchanged, restored.getTransferConfigurationRevision());
    }

    @Test
    public void damagedFillNbtIsQuarantinedAndCannotBeEdited() throws Exception {
        final CellHandlerCacheTest.TestDrive drive = new CellHandlerCacheTest.TestDrive();
        final NBTTagCompound saved = new NBTTagCompound();
        drive.writeTransferState(saved);
        saved.getCompoundTag("transferRouting")
            .getTagList("routes", 10)
            .getCompoundTagAt(3)
            .setInteger("fillPercent", -1);
        drive.readTransferState(saved);
        assertTrue(drive.isTransferConfigurationLocked());
        assertEquals("invalid_routing_configuration", drive.getTransferStatus());
        assertThrows(IllegalStateException.class, () -> drive.setTransferGroupFillPercent(3, 80));
        final NBTTagCompound resaved = new NBTTagCompound();
        drive.writeTransferState(resaved);
        assertTrue(resaved.hasKey("invalidTransferRouting"));
    }

    @Test
    public void unsupportedRoutingKeepsItsEvidenceAcrossSaveWithoutBeingMigrated() throws Exception {
        for (int version : new int[] { 1, 2, 99 }) {
            final CellHandlerCacheTest.TestDrive drive = new CellHandlerCacheTest.TestDrive();
            drive.install(0, CellHandlerCacheTest.TYPES[0], 100);
            final IMEInventory<?> handler = drive.getNativeCellHandler(0);
            final NBTTagCompound saved = new NBTTagCompound();
            drive.writeTransferState(saved);
            saved.getCompoundTag("transferRouting")
                .setInteger("version", version);
            final NBTTagCompound original = (NBTTagCompound) saved.getCompoundTag("transferRouting")
                .copy();
            drive.readTransferState(saved);
            assertTrue(drive.isTransferConfigurationLocked());
            assertEquals("invalid_routing_configuration", drive.getTransferStatus());
            assertSame(handler, drive.getNativeCellHandler(0));
            CellHandlerCacheTest.assertCount(drive, 0, CellHandlerCacheTest.TYPES[0], 100);
            final NBTTagCompound resaved = new NBTTagCompound();
            drive.writeTransferState(resaved);
            assertFalse(resaved.hasKey("transferRouting"));
            assertEquals(
                original,
                resaved.getCompoundTag("invalidTransferRouting")
                    .getCompoundTag("transferRouting"));
            final CellHandlerCacheTest.TestDrive reloaded = new CellHandlerCacheTest.TestDrive();
            reloaded.readTransferState(resaved);
            assertTrue(reloaded.isTransferConfigurationLocked());
        }
    }
}
