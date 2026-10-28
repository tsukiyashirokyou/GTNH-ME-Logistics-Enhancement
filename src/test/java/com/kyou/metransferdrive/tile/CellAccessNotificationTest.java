package com.kyou.metransferdrive.tile;

import static com.kyou.metransferdrive.tile.CellHandlerCacheTest.TYPES;
import static com.kyou.metransferdrive.tile.CellHandlerCacheTest.assertCount;
import static com.kyou.metransferdrive.tile.CellHandlerCacheTest.extract;
import static com.kyou.metransferdrive.tile.CellHandlerCacheTest.stack;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import appeng.api.config.AccessRestriction;
import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IAEStackType;
import appeng.me.storage.MEInventoryHandler;
import appeng.me.storage.NetworkInventoryHandler;
import appeng.util.IterationCounter;

/**
 * Production access transitions with pinned native cell/network inventory handlers and real slot callbacks. The Grid
 * publication boundary and watcher subscriber are doubles, not a live NetworkMonitor/ItemWatcher/Level Emitter test.
 */
@SuppressWarnings({ "rawtypes", "unchecked" })
public class CellAccessNotificationTest {

    @Test
    public void losingReadPublishesOnlyLocalQuantityAfterOldModeStructuralEvent() throws Exception {
        final Drive drive = new Drive();
        drive.install(0, TYPES[0], 100);
        drive.install(1, TYPES[0], 40);
        drive.other.install(0, TYPES[0], 900);
        drive.start();
        final IMEInventory nativeHandler = drive.getNativeCellHandler(0);
        final int constructions = drive.constructions;
        drive.change(AccessRestriction.WRITE);
        assertEquals(AccessRestriction.READ_WRITE, drive.eventModes.get(0));
        assertEquals(1, drive.notices.size());
        assertEquals(-140, drive.notices.get(0).delta);
        assertEquals(900, drive.notices.get(0).networkCount);
        assertEquals(1, drive.notices.get(0).entries);
        assertCount(drive, 0, TYPES[0], 100);
        assertCount(drive, 1, TYPES[0], 40);
        assertSame(nativeHandler, drive.getNativeCellHandler(0));
        assertEquals(constructions, drive.constructions);
    }

    @Test
    public void restoringReadPublishesActualRemainingStockForEveryRegisteredType() throws Exception {
        for (IAEStackType type : TYPES) {
            final Drive drive = new Drive();
            drive.install(0, type, 100);
            drive.setAccessMode(AccessRestriction.WRITE);
            assertEquals(17, extract(drive.getNativeCellHandler(0), type, 17));
            drive.start();
            drive.change(AccessRestriction.READ);
            assertEquals(AccessRestriction.WRITE, drive.eventModes.get(0));
            assertEquals(1, drive.notices.size());
            assertEquals(83, drive.notices.get(0).delta);
            assertEquals(83, drive.notices.get(0).networkCount);
            assertCount(drive, 0, type, 83);
        }
    }

    @Test
    public void unchangedReadVisibilityAndEmptyCellsDoNotPublishInventedChanges() throws Exception {
        final Drive drive = new Drive();
        drive.install(0, TYPES[0], 100);
        drive.start();
        drive.change(AccessRestriction.READ);
        drive.change(AccessRestriction.READ_WRITE);
        drive.change(AccessRestriction.READ_WRITE);
        assertTrue(drive.notices.isEmpty());
        assertEquals(2, drive.eventModes.size());
        final Drive empty = new Drive();
        empty.start();
        empty.change(AccessRestriction.WRITE);
        empty.change(AccessRestriction.READ);
        assertTrue(empty.notices.isEmpty());
    }

    @Test
    public void sixtyFourMixedCellsAggregateOncePerTypeWithoutRebuildingHandlers() throws Exception {
        final Drive drive = new Drive();
        final Map<IAEStackType, Long> counts = new IdentityHashMap<>();
        final IMEInventory[] handlers = new IMEInventory[64];
        for (int slot = 0; slot < 64; slot++) {
            final IAEStackType type = TYPES[slot % TYPES.length];
            drive.install(slot, type, 100 + slot);
            counts.merge(type, 100L + slot, Long::sum);
        }
        for (int slot = 0; slot < 64; slot++) {
            handlers[slot] = drive.getNativeCellHandler(slot);
        }
        final int constructions = drive.constructions;
        drive.start();
        drive.change(AccessRestriction.WRITE);
        assertEquals(3, drive.notices.size());
        for (Notice notice : drive.notices) {
            assertEquals(-counts.get(notice.type), notice.delta);
            assertEquals(0, notice.networkCount);
            assertEquals(1, notice.entries);
        }
        drive.notices.clear();
        drive.change(AccessRestriction.READ_WRITE);
        for (Notice notice : drive.notices) {
            assertEquals((long) counts.get(notice.type), notice.delta);
            assertEquals(notice.delta, notice.networkCount);
        }
        for (int slot = 0; slot < 64; slot++) {
            assertSame(handlers[slot], drive.getNativeCellHandler(slot));
        }
        assertEquals(constructions, drive.constructions);
        assertEquals(2, drive.eventModes.size());
    }

    @Test
    public void alternatingNetworkInternalAndSlotCallbacksPreserveCountsAndSharedCache() throws Exception {
        for (IAEStackType type : TYPES) {
            final Drive drive = new Drive();
            drive.install(0, type, 100);
            drive.start();
            final IMEInventory handler = drive.getNativeCellHandler(0);
            final NetworkInventoryHandler network = drive.network(type);
            assertEquals(7, extract(network, type, 7));
            assertEquals(11, extract(handler, type, 11));
            drive.getInternalInventory()
                .markDirty();
            drive.change(AccessRestriction.WRITE);
            assertEquals(-82, drive.notices.get(0).delta);
            assertEquals(9, extract(handler, type, 9));
            network.injectItems(stack(type, 3), Actionable.MODULATE, new BaseActionSource());
            drive.getInternalInventory()
                .markDirty();
            drive.change(AccessRestriction.READ);
            assertEquals(76, drive.notices.get(1).delta);
            assertEquals(76, drive.notices.get(1).networkCount);
            assertSame(handler, drive.getNativeCellHandler(0));
            assertEquals(1, drive.constructions);
            assertCount(drive, 0, type, 76);
            final net.minecraft.item.ItemStack removed = drive.getInternalInventory()
                .decrStackSize(0, 1);
            drive.getInternalInventory()
                .setInventorySlotContents(0, removed);
            drive.change(AccessRestriction.WRITE);
            assertEquals(-76, drive.notices.get(2).delta);
            assertCount(drive, 0, type, 76);
        }
    }

    @Test
    public void inactiveUnavailableOrUnregisteredGridDoesNotReceivePhantomDelta() throws Exception {
        for (int state = 0; state < 3; state++) {
            final Drive drive = new Drive();
            drive.install(0, TYPES[0], 100);
            drive.start();
            drive.active = state != 0;
            drive.available = state != 1;
            drive.registered = state != 2;
            drive.change(AccessRestriction.WRITE);
            assertTrue(drive.notices.isEmpty());
            assertEquals(
                AccessRestriction.WRITE,
                drive.exposed(0, TYPES[0])
                    .getAccess());
            assertCount(drive, 0, TYPES[0], 100);
        }
    }

    @Test
    public void structuralActivationSettlesBeforeVisibilitySnapshotAndLossCancelsPublication() throws Exception {
        final Drive newlyActive = new Drive();
        newlyActive.install(0, TYPES[0], 100);
        newlyActive.start();
        newlyActive.active = false;
        newlyActive.onStructure = () -> {
            assertEquals(AccessRestriction.READ_WRITE, newlyActive.getAccessMode());
            newlyActive.active = true;
        };
        newlyActive.change(AccessRestriction.WRITE);
        assertEquals(-100, newlyActive.notices.get(0).delta);
        final Drive lost = new Drive();
        lost.install(0, TYPES[0], 100);
        lost.start();
        lost.onStructure = () -> lost.available = false;
        lost.change(AccessRestriction.WRITE);
        assertTrue(lost.notices.isEmpty());
        assertCount(lost, 0, TYPES[0], 100);
    }

    @Test
    public void notificationReentryQueuesNextTickAndCannotReplaceCells() throws Exception {
        final Drive drive = new Drive();
        drive.install(0, TYPES[0], 100);
        drive.start();
        final IMEInventory handler = drive.getNativeCellHandler(0);
        drive.onNotice = () -> {
            assertEquals(AccessRestriction.WRITE, drive.getAccessMode());
            assertTrue(drive.isTransferConfigurationLocked());
            assertEquals(null, drive.protectedCells.decrStackSize(0, 1));
            drive.setAccessMode(AccessRestriction.READ);
            drive.applyPendingAccessMode();
            assertEquals(AccessRestriction.WRITE, drive.getAccessMode());
        };
        drive.change(AccessRestriction.WRITE);
        assertEquals(1, drive.notices.size());
        assertEquals(AccessRestriction.WRITE, drive.getAccessMode());
        drive.onNotice = () -> {};
        drive.applyPendingAccessMode();
        assertEquals(AccessRestriction.READ, drive.getAccessMode());
        assertEquals(2, drive.notices.size());
        assertEquals(100, drive.notices.get(1).delta);
        assertSame(handler, drive.getNativeCellHandler(0));
        assertEquals(1, drive.constructions);
    }

    @Test
    public void structuralCallbackReentryCannotOverwriteTheInProgressCommit() throws Exception {
        final Drive drive = new Drive();
        drive.install(0, TYPES[0], 100);
        drive.start();
        drive.onStructure = () -> drive.setAccessMode(AccessRestriction.READ);
        drive.change(AccessRestriction.WRITE);
        assertEquals(AccessRestriction.WRITE, drive.getAccessMode());
        assertEquals(-100, drive.notices.get(0).delta);
        drive.onStructure = () -> {};
        drive.applyPendingAccessMode();
        assertEquals(AccessRestriction.READ, drive.getAccessMode());
        assertEquals(100, drive.notices.get(1).delta);
    }

    @Test
    public void topologyLossDuringPublicationDoesNotSendRemainingTypesToAnotherGrid() throws Exception {
        final Drive drive = new Drive();
        for (int slot = 0; slot < TYPES.length; slot++) {
            drive.install(slot, TYPES[slot], 100);
        }
        drive.start();
        drive.onNotice = () -> drive.available = false;
        drive.change(AccessRestriction.WRITE);
        assertEquals(1, drive.notices.size());
        for (int slot = 0; slot < TYPES.length; slot++) {
            assertCount(drive, slot, TYPES[slot], 100);
            assertEquals(
                AccessRestriction.WRITE,
                drive.exposed(slot, TYPES[slot])
                    .getAccess());
        }
    }

    @Test
    public void requestDoesNotMutatePermissionsInCallerStackAndPendingModeSurvivesNbt() throws Exception {
        final Drive drive = new Drive();
        drive.install(0, TYPES[0], 100);
        drive.start();
        drive.setAccessMode(AccessRestriction.WRITE);
        assertEquals(AccessRestriction.READ_WRITE, drive.getAccessMode());
        assertEquals(
            AccessRestriction.READ_WRITE,
            drive.exposed(0, TYPES[0])
                .getAccess());
        assertTrue(drive.eventModes.isEmpty());
        assertTrue(drive.notices.isEmpty());
        final NBTTagCompound tag = new NBTTagCompound();
        drive.writeAccessMode(tag);
        final Drive loaded = new Drive();
        loaded.readAccessMode(tag);
        assertEquals(AccessRestriction.WRITE, loaded.getAccessMode());
        assertFalse(loaded.isTransferConfigurationLocked());
        drive.applyPendingAccessMode();
        assertEquals(AccessRestriction.WRITE, drive.getAccessMode());
        assertFalse(drive.isTransferConfigurationLocked());
    }

    @Test
    public void exactAggregationDoesNotOverflowOrHideOppositeSignCancellation() {
        final CellVisibilityChange change = new CellVisibilityChange();
        final IMEInventoryHandler first = available(Long.MAX_VALUE);
        final IMEInventoryHandler second = available(Long.MAX_VALUE);
        change.collect(new IMEInventoryHandler[] { first, second }, -1);
        BigInteger total = BigInteger.ZERO;
        for (IAEStack<?> delta : change.deltas()
            .get(TYPES[0])) {
            assertTrue(delta.getStackSize() < 0);
            total = total.add(BigInteger.valueOf(delta.getStackSize()));
        }
        assertEquals(
            BigInteger.valueOf(Long.MAX_VALUE)
                .multiply(BigInteger.valueOf(-2)),
            total);
        change.collect(new IMEInventoryHandler[] { first, second }, 1);
        assertTrue(
            change.deltas()
                .isEmpty());
    }

    private static IMEInventoryHandler available(final long count) {
        return (IMEInventoryHandler) Proxy.newProxyInstance(
            IMEInventoryHandler.class.getClassLoader(),
            new Class<?>[] { IMEInventoryHandler.class },
            (proxy, method, args) -> {
                if ("getStackType".equals(method.getName())) {
                    return TYPES[0];
                }
                if ("getAvailableItems".equals(method.getName())) {
                    ((appeng.api.storage.data.IItemList) args[0]).add(stack(TYPES[0], count));
                    return args[0];
                }
                throw new AssertionError(method.getName());
            });
    }

    private static final class Notice {

        IAEStackType type;
        long delta;
        long networkCount;
        int entries;
    }

    private static final class Drive extends CellHandlerCacheTest.TestDrive {

        final CellHandlerCacheTest.TestDrive other = new CellHandlerCacheTest.TestDrive();
        final List<Notice> notices = new ArrayList<>();
        final List<AccessRestriction> eventModes = new ArrayList<>();
        final Map<IAEStackType, NetworkInventoryHandler> queriedNetworks = new IdentityHashMap<>();
        final IGrid grid;
        boolean started;
        boolean active = true;
        boolean available = true;
        boolean registered = true;
        Runnable onStructure = () -> {};
        Runnable onNotice = () -> {};

        Drive() throws Exception {
            final IStorageGrid storage = (IStorageGrid) Proxy.newProxyInstance(
                IStorageGrid.class.getClassLoader(),
                new Class<?>[] { IStorageGrid.class },
                (proxy, method, args) -> {
                    if (!"postAlterationOfStoredItems".equals(method.getName())) {
                        throw new AssertionError(method.getName());
                    }
                    final Notice notice = new Notice();
                    notice.type = (IAEStackType) args[0];
                    for (Object entry : (Iterable) args[1]) {
                        notice.delta = Math.addExact(notice.delta, ((IAEStack) entry).getStackSize());
                        notice.entries++;
                    }
                    // Mirrors the native postChange boundary's *full* count lookup, using a real network handler.
                    final IAEStack visible = this.queriedNetworks.get(notice.type)
                        .getAvailableItem(stack(notice.type, 1), IterationCounter.fetchNewId());
                    notice.networkCount = visible == null ? 0 : visible.getStackSize();
                    this.notices.add(notice);
                    this.onNotice.run();
                    return null;
                });
            this.grid = (IGrid) Proxy.newProxyInstance(
                IGrid.class.getClassLoader(),
                new Class<?>[] { IGrid.class },
                (proxy, method, args) -> {
                    if ("getCache".equals(method.getName())) {
                        return storage;
                    }
                    throw new AssertionError(method.getName());
                });
        }

        void start() throws Exception {
            final Field ready = TileMETransferDrive.class.getDeclaredField("ready");
            ready.setAccessible(true);
            ready.setBoolean(this, true);
            this.started = true;
        }

        void change(final AccessRestriction mode) {
            this.setAccessMode(mode);
            this.applyPendingAccessMode();
        }

        @Override
        IGrid accessNotificationGrid() {
            return this.started && this.active && this.available && this.registered ? this.grid : null;
        }

        @Override
        void postCellArrayUpdate() {
            this.eventModes.add(this.getAccessMode());
            // A synchronous grid query must retain exactly the wrappers that the following commit updates.
            for (IAEStackType type : TYPES) {
                this.queriedNetworks.put(type, this.network(type));
            }
            this.onStructure.run();
        }

        NetworkInventoryHandler network(final IAEStackType type) {
            final NetworkInventoryHandler network = new NetworkInventoryHandler(type, null);
            for (Object entry : this.getCellArray(type)) {
                network.addNewStorage((MEInventoryHandler) entry);
            }
            for (Object entry : this.other.getCellArray(type)) {
                network.addNewStorage((MEInventoryHandler) entry);
            }
            return network;
        }
    }
}
