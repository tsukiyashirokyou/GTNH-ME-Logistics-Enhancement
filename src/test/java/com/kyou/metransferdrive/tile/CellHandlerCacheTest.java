package com.kyou.metransferdrive.tile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import com.kyou.metransferdrive.transfer.TargetRegistry;

import appeng.api.config.AccessRestriction;
import appeng.api.config.Actionable;
import appeng.api.config.FuzzyMode;
import appeng.api.exceptions.AppEngException;
import appeng.api.implementations.items.IStorageCell;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.ICellHandler;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.ISaveProvider;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IAEStackType;
import appeng.api.storage.data.IItemList;
import appeng.me.helpers.AENetworkProxy;
import appeng.me.storage.CellInventory;
import appeng.me.storage.CellInventoryHandler;
import appeng.me.storage.MEInventoryHandler;
import appeng.me.storage.NetworkInventoryHandler;
import appeng.tile.inventory.AppEngInternalInventory;
import appeng.tile.inventory.IAEStackInventory;
import appeng.tile.inventory.InvOperation;

/**
 * Real pinned AE inventory callbacks, cell count/NBT algorithms and network handlers. Forge side detection, registry,
 * grid publication and stack identities/lists are doubles; not a running Grid/ItemWatcher integration test.
 */
@SuppressWarnings({ "rawtypes", "unchecked" })
public class CellHandlerCacheTest {

    private static final BaseActionSource SOURCE = new BaseActionSource();
    private static final IAEStackType<?>[] TYPES = { type("cache_test_item", 1), type("cache_test_fluid", 1000),
        type("cache_test_essentia", 1) };

    @Test
    public void synchronousQueryThenTrailingDirtyCallbackKeepsThePublishedHandler() throws Exception {
        for (IAEStackType type : TYPES) {
            final TestDrive drive = new TestDrive();
            drive.install(0, type, 100);
            assertEquals(Arrays.asList(0, -1), drive.callbacks);
            final MEInventoryHandler published = drive.published.get(type)
                .get(0);
            assertSame(published.getInternal(), drive.getNativeCellHandler(0));
            drive.getInternalInventory()
                .markDirty();
            assertSame(published.getInternal(), drive.getNativeCellHandler(0));
            assertEquals(1, drive.constructions);
            assertEquals(1, drive.publications);
        }
    }

    @Test
    public void alternatingNetworkAndInternalOperationsPreserveNativeCountsAndNbt() throws Exception {
        for (IAEStackType type : TYPES) {
            final TestDrive drive = new TestDrive();
            drive.install(0, type, 100);
            final NetworkInventoryHandler network = drive.networks.get(type);
            assertEquals(10, extract(drive.getNativeCellHandler(0), type, 10));
            drive.getInternalInventory()
                .markDirty();
            assertEquals(1, extract(network, type, 1));
            assertCount(drive, 0, type, 89);
            assertNull(network.injectItems(stack(type, 7), Actionable.MODULATE, SOURCE));
            assertCount(drive, 0, type, 96);
            assertEquals(6, extract(drive.getNativeCellHandler(0), type, 6));
            assertCount(drive, 0, type, 90);
            // Initial 100 + injection 7 = source 90 + internal output 16 + network output 1.
            assertEquals(
                107,
                drive.getInternalInventory()
                    .getStackInSlot(0)
                    .getTagCompound()
                    .getLong("count") + 16
                    + 1);
            assertEquals(1, drive.constructions);
        }
    }

    @Test
    public void hundredMillionNativeCountsStaySharedDuringAlternatingNetworkAccess() throws Exception {
        for (IAEStackType type : TYPES) {
            final TestDrive drive = new TestDrive();
            drive.install(0, type, 100_000_000);
            final IMEInventory internal = drive.getNativeCellHandler(0);
            final NetworkInventoryHandler network = drive.networks.get(type);
            assertEquals(7, extract(network, type, 7));
            assertEquals(90_000_000, extract(internal, type, 90_000_000));
            drive.getInternalInventory()
                .markDirty();
            assertCount(drive, 0, type, 9_999_993);
            assertNull(network.injectItems(stack(type, 11), Actionable.MODULATE, SOURCE));
            assertCount(drive, 0, type, 10_000_004);
            assertEquals(10_000_000, extract(internal, type, 10_000_000));
            assertEquals(4, extract(network, type, 4));
            assertCount(drive, 0, type, 0);
            assertSame(internal, drive.getNativeCellHandler(0));
            assertEquals(1, drive.constructions);
        }
    }

    @Test
    public void removalKeepsTheRemainingPublishedHandlerAndReturnsTheLatestCell() throws Exception {
        for (IAEStackType type : TYPES) {
            final TestDrive drive = new TestDrive();
            drive.install(0, type, 100);
            drive.install(1, type, 40);
            drive.callbacks.clear();
            final ItemStack removed = drive.getInternalInventory()
                .decrStackSize(0, 1);
            assertEquals(Arrays.asList(0, -1), drive.callbacks);
            assertNull(drive.getNativeCellHandler(0));
            assertSame(
                drive.published.get(type)
                    .get(0)
                    .getInternal(),
                drive.getNativeCellHandler(1));
            assertEquals(1, extract(drive.networks.get(type), type, 1));
            assertEquals(2, extract(drive.getNativeCellHandler(1), type, 2));
            assertCount(drive, 1, type, 37);
            assertEquals(
                100,
                removed.getTagCompound()
                    .getLong("count"));
            assertEquals(3, drive.publications);
        }
    }

    @Test
    public void nativeNbtReloadAndReinsertionKeepTheLatestCounts() throws Exception {
        for (IAEStackType type : TYPES) {
            final TestDrive original = new TestDrive();
            original.install(0, type, 100);
            extract(original.getNativeCellHandler(0), type, 10);
            extract(original.networks.get(type), type, 1);
            final NBTTagCompound state = new NBTTagCompound();
            original.writeToNBT_AEBaseInvTile(state);
            final CellItem item = (CellItem) original.getInternalInventory()
                .getStackInSlot(0)
                .getItem();
            // Only registry lookup is substituted; nested NBT comes from the real save above.
            final CellInventoryNbt.Loaded decoded = CellInventoryNbt.read(state, tag -> {
                final ItemStack cell = new ItemStack(item);
                cell.setTagCompound(
                    (NBTTagCompound) tag.getCompoundTag("tag")
                        .copy());
                return cell;
            });
            final TestDrive reloaded = new TestDrive();
            reloaded.getInternalInventory()
                .setInventorySlotContents(0, decoded.cells[0]);
            assertCount(reloaded, 0, type, 89);
            final ItemStack removed = reloaded.getInternalInventory()
                .decrStackSize(0, 1);
            reloaded.getInternalInventory()
                .setInventorySlotContents(0, removed);
            assertEquals(9, extract(reloaded.networks.get(type), type, 9));
            assertCount(reloaded, 0, type, 80);
            assertCount(original, 0, type, 89);
            assertNotSame(original.getNativeCellHandler(0), reloaded.getNativeCellHandler(0));
        }
    }

    @Test
    public void sixtyFourSlotsModesAndEightGroupsReuseTheirNativeHandlers() throws Exception {
        final TestDrive drive = new TestDrive();
        for (int slot = 0; slot < 64; slot++) {
            drive.install(slot, TYPES[slot % TYPES.length], 100 + slot);
        }
        final IMEInventory[] handlers = new IMEInventory[64];
        for (int slot = 0; slot < 64; slot++) {
            handlers[slot] = drive.getNativeCellHandler(slot);
        }
        final int constructed = drive.constructions;
        for (AccessRestriction mode : new AccessRestriction[] { AccessRestriction.READ, AccessRestriction.WRITE,
            AccessRestriction.READ_WRITE }) {
            drive.setAccessMode(mode);
            for (int group = 0; group < 8; group++) {
                drive.setTransferGroupRoute(group, TargetRegistry.Route.automatic());
            }
            for (int slot = 0; slot < 64; slot++) {
                final IAEStackType type = TYPES[slot % TYPES.length];
                final MEInventoryHandler exposed = drive.exposed(slot, type);
                assertSame(handlers[slot], drive.getNativeCellHandler(slot));
                assertEquals(mode, exposed.getAccess());
                assertEquals(
                    mode.hasPermission(AccessRestriction.READ),
                    exposed.extractItems(stack(type, 1), Actionable.SIMULATE, SOURCE) != null);
                assertEquals(
                    mode.hasPermission(AccessRestriction.WRITE),
                    exposed.injectItems(stack(type, 1), Actionable.SIMULATE, SOURCE) == null);
                assertEquals(1, extract(handlers[slot], type, 1));
                drive.getInternalInventory()
                    .markDirty();
            }
        }
        assertEquals(constructed, drive.constructions);
        assertEquals(64, drive.publications);
        for (int slot = 0; slot < 64; slot++) {
            assertCount(drive, slot, TYPES[slot % TYPES.length], 97 + slot);
        }
    }

    @Test
    public void idlePowerReentrySeesACompletedCacheWithoutAnotherConstruction() throws Exception {
        final TestDrive drive = new TestDrive();
        final boolean[] queried = { false };
        drive.proxy.onIdle = () -> {
            if (!queried[0]) {
                queried[0] = true;
                assertTrue(drive.getNativeCellHandler(0) instanceof CellInventoryHandler);
            }
        };
        drive.install(0, TYPES[0], 100);
        assertTrue(queried[0]);
        assertEquals(1, drive.constructions);
        assertSame(
            drive.published.get(TYPES[0])
                .get(0)
                .getInternal(),
            drive.getNativeCellHandler(0));
    }

    @Test
    public void factoryReentryCannotExposeAPartialCacheOrReplaceCells() throws Exception {
        final TestDrive drive = new TestDrive();
        drive.onConstruction = () -> {
            try {
                drive.getNativeCellHandler(0);
                fail("A recursive factory must not observe incomplete handlers");
            } catch (IllegalStateException expected) {
                assertEquals("Recursive cell handler construction", expected.getMessage());
            }
            try {
                drive.protectedCells.setInventorySlotContents(0, null);
                fail("A factory must not replace the inventory being built");
            } catch (IllegalStateException expected) {
                assertEquals("Cannot replace cells while constructing their handlers", expected.getMessage());
            }
            // A native constructor may request a save. This is not a second structural change.
            drive.getInternalInventory()
                .markDirty();
        };
        drive.install(0, TYPES[0], 100);
        assertEquals(1, drive.constructions);
        assertSame(
            drive.published.get(TYPES[0])
                .get(0)
                .getInternal(),
            drive.getNativeCellHandler(0));
        assertCount(drive, 0, TYPES[0], 100);
    }

    @Test
    public void worldNbtReadInvalidatesOnceWithoutPublishingPartiallyLoadedSlots() throws Exception {
        final TestDrive drive = new TestDrive();
        drive.install(0, TYPES[0], 100);
        drive.publishOnChange = false;
        drive.readFromNBT_AEBaseInvTile(new NBTTagCompound());
        for (int slot = 0; slot < 64; slot++) {
            assertNull(drive.getNativeCellHandler(slot));
        }
        assertEquals(1, drive.constructions);
        assertEquals(1, drive.publications);
        drive.publishOnChange = true;
        drive.install(63, TYPES[2], 80);
        assertEquals(2, drive.constructions);
        assertCount(drive, 63, TYPES[2], 80);
    }

    private static long extract(final IMEInventory inventory, final IAEStackType type, final long count) {
        final IAEStack result = inventory.extractItems(stack(type, count), Actionable.MODULATE, SOURCE);
        return result == null ? 0 : result.getStackSize();
    }

    private static void assertCount(final TestDrive drive, final int slot, final IAEStackType type,
        final long expected) {
        final IMEInventory handler = drive.getNativeCellHandler(slot);
        final IAEStack available = handler.getAvailableItem(stack(type, 1), 0);
        assertEquals(expected, available == null ? 0 : available.getStackSize());
        assertEquals(
            expected,
            drive.getInternalInventory()
                .getStackInSlot(slot)
                .getTagCompound()
                .getLong("count"));
    }

    private static final class TestDrive extends TileMETransferDrive {

        TestProxy proxy;
        final List<Integer> callbacks = new ArrayList<>();
        final Map<IAEStackType, List<MEInventoryHandler>> published = new IdentityHashMap<>();
        final Map<IAEStackType, NetworkInventoryHandler> networks = new IdentityHashMap<>();
        int constructions;
        int publications;
        boolean publishOnChange = true;
        Runnable onConstruction = () -> {};
        final IInventory protectedCells;

        TestDrive() throws Exception {
            this.protectedCells = this.getInternalInventory();
            // Inject only our own inventory field; no AE2 field or bytecode is modified.
            final Field cells = TileMETransferDrive.class.getDeclaredField("cells");
            cells.setAccessible(true);
            cells.set(this, new AppEngInternalInventory(this, 64, 1) {

                @Override
                protected boolean eventsEnabled() {
                    return true;
                }
            });
        }

        @Override
        public AENetworkProxy getProxy() {
            if (this.proxy == null) {
                this.proxy = new TestProxy(this);
            }
            return this.proxy;
        }

        @Override
        public void onChangeInventory(final IInventory inventory, final int slot, final InvOperation operation,
            final ItemStack removed, final ItemStack added) {
            this.callbacks.add(slot);
            super.onChangeInventory(inventory, slot, operation, removed, added);
            if (slot >= 0 && this.publishOnChange) {
                // Query/cache during the first host callback, before AE's trailing markDirty callback.
                this.published.clear();
                this.networks.clear();
                for (IAEStackType type : TYPES) {
                    final List handlers = this.getCellArray(type);
                    this.published.put(type, new ArrayList<>(handlers));
                    final NetworkInventoryHandler network = new NetworkInventoryHandler(type, null);
                    for (Object handler : handlers) {
                        network.addNewStorage((IMEInventoryHandler) handler);
                    }
                    this.networks.put(type, network);
                }
                this.publications++;
            }
        }

        @Override
        Iterable<IAEStackType<?>> cellStackTypes() {
            return Arrays.asList(TYPES);
        }

        @Override
        ICellHandler findCellHandler(final ItemStack cell) {
            return (ICellHandler) Proxy.newProxyInstance(
                ICellHandler.class.getClassLoader(),
                new Class<?>[] { ICellHandler.class },
                (proxy, method, args) -> {
                    if ("cellIdleDrain".equals(method.getName())) {
                        return 0.5;
                    }
                    if ("getCellInventory".equals(method.getName())) {
                        final CellItem item = (CellItem) cell.getItem();
                        if (args[2] != item.type) {
                            return null;
                        }
                        this.constructions++;
                        this.onConstruction.run();
                        return new CellInventoryHandler(new NativeCell(cell, (ISaveProvider) args[1]), item.type) {};
                    }
                    throw new AssertionError(method.getName());
                });
        }

        void install(final int slot, final IAEStackType type, final long count) {
            final ItemStack cell = new ItemStack(new CellItem(type));
            final NBTTagCompound nbt = new NBTTagCompound();
            nbt.setShort("types", (short) 1);
            nbt.setLong("count", count);
            nbt.setTag("#0", new NBTTagCompound());
            nbt.setLong("@0", count);
            cell.setTagCompound(nbt);
            this.getInternalInventory()
                .setInventorySlotContents(slot, cell);
        }

        MEInventoryHandler exposed(final int slot, final IAEStackType type) {
            for (Object entry : this.getCellArray(type)) {
                final MEInventoryHandler handler = (MEInventoryHandler) entry;
                if (handler.getInternal() == this.getNativeCellHandler(slot)) {
                    return handler;
                }
            }
            throw new AssertionError("Missing exposed handler");
        }
    }

    private static final class TestProxy extends AENetworkProxy {

        Runnable onIdle = () -> {};

        TestProxy(final TestDrive drive) {
            super(drive, "test", null, true);
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public void setIdlePowerUsage(final double idle) {
            this.onIdle.run();
        }
    }

    private static final class NativeCell extends CellInventory {

        NativeCell(final ItemStack cell, final ISaveProvider provider) throws AppEngException {
            super(cell, provider);
        }

        @Override
        protected IAEStack readStack(final NBTTagCompound tag) {
            return stack(this.getStackType(), tag.getLong("Cnt"));
        }

        @Override
        protected String getStackTypeTag() {
            return "types";
        }

        @Override
        protected String getStackCountTag() {
            return "count";
        }
    }

    private static final class CellItem extends Item implements IStorageCell {

        final IAEStackType type;

        CellItem(final IAEStackType type) {
            this.type = type;
        }

        @Override
        public IAEStackType getStackType() {
            return this.type;
        }

        @Override
        public int getBytes(ItemStack cell) {
            return 16_777_216;
        }

        @Override
        public int BytePerType(ItemStack cell) {
            return 8;
        }

        @Override
        public int getBytesPerType(ItemStack cell) {
            return 8;
        }

        @Override
        public int getTotalTypes(ItemStack cell) {
            return 63;
        }

        @Override
        public boolean storableInStorageCell() {
            return false;
        }

        @Override
        public boolean isStorageCell(ItemStack cell) {
            return true;
        }

        @Override
        public double getIdleDrain() {
            return 0.5;
        }

        @Override
        public boolean isEditable(ItemStack cell) {
            return true;
        }

        @Override
        public IInventory getUpgradesInventory(ItemStack cell) {
            return new InventoryBasic("upgrades", false, 0);
        }

        @Override
        public IAEStackInventory getConfigAEInventory(ItemStack cell) {
            return new IAEStackInventory(null, 0);
        }

        @Override
        public FuzzyMode getFuzzyMode(ItemStack cell) {
            return FuzzyMode.IGNORE_ALL;
        }

        @Override
        public void setFuzzyMode(ItemStack cell, FuzzyMode mode) {}
    }

    private static IAEStackType type(final String id, final int unit) {
        return (IAEStackType) Proxy.newProxyInstance(
            IAEStackType.class.getClassLoader(),
            new Class<?>[] { IAEStackType.class },
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getId":
                        return id;
                    case "getAmountPerByte":
                        return 8;
                    case "getAmountPerUnit":
                        return unit;
                    case "createPrimitiveList":
                        return list();
                    default:
                        throw new AssertionError(method.getName());
                }
            });
    }

    private static IAEStack stack(final IAEStackType type, final long count) {
        final long[] amount = { count };
        final Class contract = type == TYPES[0] ? IAEItemStack.class
            : type == TYPES[1] ? IAEFluidStack.class : IAEStack.class;
        return (IAEStack) Proxy
            .newProxyInstance(IAEStack.class.getClassLoader(), new Class<?>[] { contract }, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getStackType":
                        return type;
                    case "isSameType":
                        return ((IAEStack) args[0]).getStackType() == type;
                    case "copy":
                        return stack(type, amount[0]);
                    case "getStackSize":
                        return amount[0];
                    case "setStackSize":
                        amount[0] = (long) args[0];
                        return proxy;
                    case "incStackSize":
                        amount[0] += (long) args[0];
                        return proxy;
                    case "decStackSize":
                        amount[0] -= (long) args[0];
                        return proxy;
                    case "add":
                        amount[0] += ((IAEStack) args[0]).getStackSize();
                        return null;
                    case "isCraftable":
                        return false;
                    case "isItem":
                        return contract == IAEItemStack.class;
                    case "isFluid":
                        return contract == IAEFluidStack.class;
                    case "getItem":
                        return new Item();
                    case "writeToNBT":
                        ((NBTTagCompound) args[0]).setLong("Cnt", amount[0]);
                        return null;
                    default:
                        throw new AssertionError(method.getName());
                }
            });
    }

    private static IItemList list() {
        final List<IAEStack> values = new ArrayList<>();
        return (IItemList) Proxy.newProxyInstance(
            IItemList.class.getClassLoader(),
            new Class<?>[] { IItemList.class },
            (proxy, method, args) -> {
                values.removeIf(value -> value.getStackSize() <= 0);
                switch (method.getName()) {
                    case "findPrecise":
                        return values.isEmpty() ? null : values.get(0);
                    case "add":
                    case "addStorage":
                        if (values.isEmpty()) {
                            values.add(((IAEStack) args[0]).copy());
                        } else {
                            values.get(0)
                                .incStackSize(((IAEStack) args[0]).getStackSize());
                        }
                        return null;
                    case "size":
                        return values.size();
                    case "isEmpty":
                        return values.isEmpty();
                    case "iterator":
                        return values.iterator();
                    default:
                        throw new AssertionError(method.getName());
                }
            });
    }
}
