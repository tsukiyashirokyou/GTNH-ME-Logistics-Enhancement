package com.kyou.gtnhmelogisticsenhancement.tile;

import static com.kyou.gtnhmelogisticsenhancement.tile.CellHandlerCacheTest.TYPES;
import static com.kyou.gtnhmelogisticsenhancement.tile.CellHandlerCacheTest.assertCount;
import static com.kyou.gtnhmelogisticsenhancement.tile.CellHandlerCacheTest.extract;
import static com.kyou.gtnhmelogisticsenhancement.tile.CellHandlerCacheTest.stack;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagString;

import org.junit.Test;

import com.kyou.gtnhmelogisticsenhancement.transfer.RecoveryEvidence;
import com.kyou.gtnhmelogisticsenhancement.transfer.TargetRegistry;
import com.kyou.gtnhmelogisticsenhancement.transfer.TransferTarget;

import appeng.api.config.AccessRestriction;
import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.data.IAEStackType;
import appeng.helpers.IPriorityHost;
import appeng.me.storage.MEInventoryHandler;
import appeng.me.storage.NetworkInventoryHandler;

/** Pinned AE2 native cell/network ordering with a substituted Grid event boundary; not a running Minecraft Grid. */
@SuppressWarnings({ "rawtypes", "unchecked" })
public class CellStoragePriorityTest {

    @Test
    public void defaultAndStrictNbtRoundTripsSupportTheEntireSignedIntRange() throws Exception {
        final Drive loaded = new Drive();
        assertTrue(loaded instanceof IPriorityHost);
        assertEquals(0, loaded.getPriority());
        for (int priority : new int[] { Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE }) {
            final Drive original = new Drive();
            original.setPriority(priority);
            final NBTTagCompound tag = new NBTTagCompound();
            original.writeStoragePriority(tag);
            // Reload creates a new tile; replacing non-null fake cells requires Forge's Platform bootstrap.
            final Drive reloaded = new Drive();
            reloaded.readStoragePriority(tag);
            assertEquals(priority, reloaded.getPriority());
            reloaded.install(0, TYPES[0], 100);
            assertEquals(
                priority,
                reloaded.exposed(0, TYPES[0])
                    .getPriority());
        }
        loaded.setPriority(Integer.MAX_VALUE);
        loaded.readStoragePriority(new NBTTagCompound());
        assertEquals(0, loaded.getPriority());
        final NBTTagCompound wrong = new NBTTagCompound();
        wrong.setLong("storagePriority", Long.MAX_VALUE);
        loaded.readStoragePriority(wrong);
        assertEquals(0, loaded.getPriority());
        wrong.setString("storagePriority", "2147483648");
        loaded.readStoragePriority(wrong);
        assertEquals(0, loaded.getPriority());
    }

    @Test
    public void nativeNetworkInjectsHighFirstAndExtractsLowFirstIncludingSignedEndpoints() throws Exception {
        for (IAEStackType type : TYPES) {
            for (int priority : new int[] { -100, 100, Integer.MIN_VALUE, Integer.MAX_VALUE }) {
                final Drive drive = new Drive();
                drive.install(0, type, 100);
                drive.other.install(0, type, 100);
                final IMEInventory handler = drive.getNativeCellHandler(0);
                drive.start();
                drive.setPriority(priority);
                drive.applyPendingStoragePriority();
                final NetworkInventoryHandler network = drive.ordered.get(type);
                assertEquals(
                    priority > 0 ? priority : 0,
                    ((IMEInventoryHandler) network.getHandlers()
                        .get(0)).getPriority());
                assertNull(network.injectItems(stack(type, 20), Actionable.MODULATE, new BaseActionSource()));
                assertCount(drive, 0, type, priority > 0 ? 120 : 100);
                assertCount(drive.other, 0, type, priority > 0 ? 100 : 120);
                assertEquals(10, extract(network, type, 10));
                assertCount(drive, 0, type, priority > 0 ? 120 : 90);
                assertCount(drive.other, 0, type, priority > 0 ? 90 : 120);
                assertSame(handler, drive.getNativeCellHandler(0));
                assertEquals(1, drive.constructions);
                assertEquals(1, drive.events);
            }
        }
    }

    @Test
    public void changingPriorityReordersExistingCellsWithoutReinsertionOrQuantityNotifications() throws Exception {
        final Drive drive = new Drive();
        drive.install(0, TYPES[0], 100);
        drive.other.install(0, TYPES[0], 100);
        drive.forbidVisibilityProbe = true;
        final IMEInventory handler = drive.getNativeCellHandler(0);
        drive.start();
        for (int priority : new int[] { 50, -50, 7 }) {
            final long revision = drive.getTransferConfigurationRevision();
            drive.setPriority(priority);
            assertTrue(drive.isTransferConfigurationLocked());
            assertEquals(revision, drive.getTransferConfigurationRevision());
            drive.applyPendingStoragePriority();
            assertEquals(revision + 1, drive.getTransferConfigurationRevision());
            assertSame(handler, drive.getNativeCellHandler(0));
            assertSame(
                drive.exposed(0, TYPES[0]),
                drive.ordered.get(TYPES[0])
                    .getHandlers()
                    .get(priority > 0 ? 0 : 1));
            assertCount(drive, 0, TYPES[0], 100);
        }
        assertEquals(3, drive.events);
        assertEquals(1, drive.constructions);
        drive.setPriority(7);
        drive.applyPendingStoragePriority();
        assertEquals(3, drive.events);
    }

    @Test
    public void allSixtyFourHandlersKeepTheirAccessModeAndContentsAcrossPriorityUpdates() throws Exception {
        for (AccessRestriction mode : new AccessRestriction[] { AccessRestriction.READ, AccessRestriction.WRITE,
            AccessRestriction.READ_WRITE }) {
            final Drive drive = new Drive();
            drive.setAccessMode(mode);
            final IMEInventory[] handlers = new IMEInventory[64];
            for (int slot = 0; slot < 64; slot++) {
                drive.install(slot, TYPES[slot % TYPES.length], 100 + slot);
            }
            for (int slot = 0; slot < 64; slot++) {
                handlers[slot] = drive.getNativeCellHandler(slot);
            }
            final int constructions = drive.constructions;
            drive.start();
            drive.setPriority(Integer.MIN_VALUE);
            drive.applyPendingStoragePriority();
            for (int slot = 0; slot < 64; slot++) {
                final IAEStackType type = TYPES[slot % TYPES.length];
                assertEquals(
                    Integer.MIN_VALUE,
                    drive.exposed(slot, type)
                        .getPriority());
                assertEquals(
                    mode,
                    drive.exposed(slot, type)
                        .getAccess());
                assertSame(handlers[slot], drive.getNativeCellHandler(slot));
                assertCount(drive, slot, type, 100 + slot);
            }
            assertEquals(constructions, drive.constructions);
            assertEquals(1, drive.events);
        }
    }

    @Test
    public void priorityDoesNotChangeTargetOrderingRoutesPeriodOrTransferJobState() throws Exception {
        final Drive drive = new Drive();
        final TransferTarget first = new TransferTarget(UUID.randomUUID(), 0, 0, 64, 0, "first", -7);
        final TransferTarget second = new TransferTarget(UUID.randomUUID(), 0, 1, 64, 0, "second", 100);
        drive.registerTransferTarget(first);
        drive.registerTransferTarget(second);
        final TargetRegistry.Route route = TargetRegistry.Route.specific(second.getId());
        drive.setTransferGroupRoute(0, route);
        final List<TransferTarget> targets = drive.getTransferTargets();
        final String status = drive.getTransferStatus();
        final NBTTagCompound transferBefore = new NBTTagCompound();
        drive.writeTransferState(transferBefore);
        drive.start();
        drive.setPriority(123);
        drive.applyPendingStoragePriority();
        final NBTTagCompound transferAfter = new NBTTagCompound();
        drive.writeTransferState(transferAfter);
        assertEquals(transferBefore, transferAfter);
        assertEquals(targets, drive.getTransferTargets());
        assertSame(route, drive.getTransferGroupRoute(0));
        assertEquals(status, drive.getTransferStatus());
        assertEquals(-7, first.getPriority());
        assertEquals(100, second.getPriority());
    }

    @Test
    public void callbackChangesAreDeferredAndCannotReplaceCellsOrNestAccessChanges() throws Exception {
        final Drive drive = new Drive();
        drive.install(0, TYPES[0], 100);
        final IMEInventory handler = drive.getNativeCellHandler(0);
        drive.start();
        drive.onEvent = () -> {
            assertTrue(drive.isTransferConfigurationLocked());
            assertEquals(10, drive.getPriority());
            assertNull(drive.protectedCells.decrStackSize(0, 1));
            try {
                drive.protectedCells.setInventorySlotContents(0, null);
                fail("A synchronous event must not replace cells");
            } catch (IllegalStateException expected) {
                assertEquals("Cannot replace cells while updating storage priority", expected.getMessage());
            }
            drive.setPriority(-20);
            drive.setAccessMode(AccessRestriction.READ);
            drive.applyPendingStoragePriority();
            drive.applyPendingAccessMode();
            assertEquals(10, drive.getPriority());
            assertEquals(AccessRestriction.READ_WRITE, drive.getAccessMode());
        };
        drive.setPriority(10);
        drive.applyPendingStoragePriority();
        assertEquals(1, drive.events);
        assertEquals(10, drive.getPriority());
        drive.onEvent = () -> {};
        drive.applyPendingStoragePriority();
        assertEquals(-20, drive.getPriority());
        drive.applyPendingAccessMode();
        assertEquals(AccessRestriction.READ, drive.getAccessMode());
        assertSame(handler, drive.getNativeCellHandler(0));
        assertCount(drive, 0, TYPES[0], 100);
    }

    @Test
    public void queuedPrioritySurvivesSaveAndOfflineReloadBeforeTheNextTick() throws Exception {
        final Drive drive = new Drive();
        drive.install(0, TYPES[2], 100);
        drive.start();
        drive.setPriority(Integer.MAX_VALUE);
        assertEquals(0, drive.getPriority());
        final NBTTagCompound saved = new NBTTagCompound();
        drive.writeStoragePriority(saved);
        final Drive loaded = new Drive();
        loaded.readStoragePriority(saved);
        loaded.install(0, TYPES[2], 100);
        assertEquals(Integer.MAX_VALUE, loaded.getPriority());
        assertEquals(
            Integer.MAX_VALUE,
            loaded.exposed(0, TYPES[2])
                .getPriority());
        assertFalse(loaded.isTransferConfigurationLocked());
        assertEquals(0, loaded.events);
        loaded.start();
        loaded.postCellArrayUpdate();
        assertEquals(
            Integer.MAX_VALUE,
            ((IMEInventoryHandler) loaded.ordered.get(TYPES[2])
                .getHandlers()
                .get(0)).getPriority());
    }

    @Test
    public void recoveryPackedItemRestoresStoragePriorityThroughTheProductionRestoreEntry() throws Exception {
        final NBTTagCompound state = new NBTTagCompound();
        state.setInteger("storagePriority", -567);
        final ItemStack packed = RecoveryEvidence.pack(new ItemStack(new Item()), state);
        final Drive drive = new Drive();
        drive.restoreRecoveryItem(packed);
        assertEquals(-567, drive.getPriority());
        final NBTTagCompound saved = new NBTTagCompound();
        drive.writeStoragePriority(saved);
        assertEquals(-567, saved.getInteger("storagePriority"));
    }

    @Test
    public void pendingRecoveryStillPreventsPriorityChanges() throws Exception {
        final Drive drive = new Drive();
        final NBTTagCompound damaged = new NBTTagCompound();
        damaged.setTag("transferRecovery", new NBTTagString("untrusted transaction evidence"));
        drive.readTransferState(damaged);
        assertTrue(drive.hasTransferRecovery());
        try {
            drive.setPriority(100);
            fail("Recovery lock must cover source storage priority");
        } catch (IllegalStateException expected) {
            assertEquals(0, drive.getPriority());
            assertEquals(0, drive.events);
        }
    }

    private static final class Drive extends CellHandlerCacheTest.TestDrive {

        final CellHandlerCacheTest.TestDrive other = new CellHandlerCacheTest.TestDrive();
        final Map<IAEStackType, NetworkInventoryHandler> ordered = new IdentityHashMap<>();
        int events;
        boolean forbidVisibilityProbe;
        Runnable onEvent = () -> {};

        Drive() throws Exception {}

        void start() throws Exception {
            final Field ready = TileMETransferDrive.class.getDeclaredField("ready");
            ready.setAccessible(true);
            ready.setBoolean(this, true);
        }

        @Override
        IGrid accessNotificationGrid() {
            if (this.forbidVisibilityProbe) {
                throw new AssertionError("Priority must not inspect or notify visible quantities");
            }
            return null;
        }

        @Override
        void postCellArrayUpdate() {
            this.events++;
            for (IAEStackType type : TYPES) {
                final NetworkInventoryHandler network = new NetworkInventoryHandler(type, null);
                for (Object handler : this.getCellArray(type)) {
                    network.addNewStorage((IMEInventoryHandler) handler);
                }
                for (Object handler : this.other.getCellArray(type)) {
                    network.addNewStorage((MEInventoryHandler) handler);
                }
                this.ordered.put(type, network);
            }
            this.onEvent.run();
        }
    }
}
