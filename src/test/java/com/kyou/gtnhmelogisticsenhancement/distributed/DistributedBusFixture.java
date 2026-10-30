package com.kyou.gtnhmelogisticsenhancement.distributed;

import static org.junit.Assert.*;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.ForgeDirection;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.value.sync.ModularSyncManager;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.widgets.slot.ItemSlot;
import com.cleanroommc.modularui.widgets.slot.ModularSlot;

import appeng.helpers.ICellRestriction;
import appeng.items.storage.ItemAdvancedStorageCell;
import appeng.items.storage.ItemBasicStorageCell;
import appeng.items.storage.ItemExtremeStorageCell;
import appeng.util.item.AEItemStack;
import gregtech.api.interfaces.ITexture;
import gregtech.api.metatileentity.implementations.MTEHatchOutputBus;
import gregtech.api.util.GTUtility.ItemId;
import gregtech.api.util.ItemEjectionHelper;

/** Real GT helper/MTE superclass and AE cell policy; insertion callbacks are deliberately simulated destinations. */
public class DistributedBusFixture {

    public void nativeGtWrenchRecognizesDriveAndPickaxeFallbackRemainsAvailable() {
        final com.kyou.gtnhmelogisticsenhancement.block.BlockMETransferDrive block = new com.kyou.gtnhmelogisticsenhancement.block.BlockMETransferDrive();
        final gregtech.common.tools.ToolWrench wrench = new gregtech.common.tools.ToolWrench();
        for (int meta = 0; meta < 16; meta++) {
            assertEquals("wrench", block.getHarvestTool(meta));
            assertEquals(0, block.getHarvestLevel(meta));
            assertTrue(wrench.isMinableBlock(block, meta));
            assertTrue(block.isToolEffective("wrench", meta));
        }
        final net.minecraft.item.ItemPickaxe pickaxe = new net.minecraft.item.ItemPickaxe(Item.ToolMaterial.IRON) {};
        // Fixed ForgeHooks.canHarvestBlock falls back to player/item harvesting when toolLevel < 0.
        assertEquals(-1, pickaxe.getHarvestLevel(new ItemStack(pickaxe), "wrench"));
        assertTrue(pickaxe.func_150897_b(block));
    }

    private static final Item ITEM = new Item();
    private static final Item RECIPE_BUS = new Item();
    private static final Item RECIPE_CARD = new Item();

    public static void registries() throws Exception {
        NativeOutputFixture.registries();
        final java.lang.reflect.Field home = cpw.mods.fml.relauncher.FMLInjectionData.class
            .getDeclaredField("minecraftHome");
        home.setAccessible(true);
        home.set(null, new java.io.File("."));
        // Minimal FML metadata/side bootstrap; this deliberately does NOT send any mod lifecycle events.
        final cpw.mods.fml.common.Loader fml = cpw.mods.fml.common.Loader.instance();
        final java.lang.reflect.Field names = cpw.mods.fml.common.Loader.class.getDeclaredField("namedMods");
        names.setAccessible(true);
        names.set(fml, new java.util.HashMap<>());
        cpw.mods.fml.common.FMLCommonHandler.instance()
            .beginLoading(cpw.mods.fml.server.FMLServerHandler.instance());
        final cpw.mods.fml.common.LoadController control = new cpw.mods.fml.common.LoadController(fml);
        final java.lang.reflect.Field controller = cpw.mods.fml.common.Loader.class.getDeclaredField("modController");
        controller.setAccessible(true);
        controller.set(fml, control);
        final cpw.mods.fml.common.ModMetadata metadata = new cpw.mods.fml.common.ModMetadata();
        metadata.modId = "appliedenergistics2";
        final cpw.mods.fml.common.DummyModContainer container = new cpw.mods.fml.common.DummyModContainer(metadata);
        final java.lang.reflect.Field active = control.getClass()
            .getDeclaredField("activeContainer");
        active.setAccessible(true);
        active.set(control, container);
        final java.lang.reflect.Field logSide = cpw.mods.fml.relauncher.FMLRelaunchLog.class.getDeclaredField("side");
        logSide.setAccessible(true);
        logSide.set(null, cpw.mods.fml.relauncher.Side.SERVER);
        final java.lang.reflect.Field objects = control.getClass()
            .getDeclaredField("modObjectList");
        objects.setAccessible(true);
        objects.set(
            control,
            com.google.common.collect.HashBiMap
                .create(Collections.singletonMap(container, appeng.core.AppEng.instance())));
        appeng.core.AEConfig.instance = new appeng.core.AEConfig(new java.io.File("build/test-ae/config.cfg"));
        ((net.minecraft.launchwrapper.LaunchClassLoader) DistributedBusFixture.class.getClassLoader())
            .registerTransformer("appeng.transformer.asm.ASMIntegration");
        Item.itemRegistry.addObject(31010, "gtnhmelogisticsenhancement:bus_test", ITEM);
        Item.itemRegistry.addObject(31011, "gtnhmelogisticsenhancement:recipe_bus_test", RECIPE_BUS);
        Item.itemRegistry.addObject(31012, "gtnhmelogisticsenhancement:recipe_card_test", RECIPE_CARD);
    }

    private static ItemStack item(final int meta, final int amount) {
        return new ItemStack(ITEM, amount, meta);
    }

    private static ItemId id(final int meta) {
        return ItemId.createNoCopy(ITEM, meta, null);
    }

    private static MTEDistributedMEOutputBus bus() {
        return new MTEDistributedMEOutputBus("test.distributed", 4, new String[0], new ITexture[0][][]);
    }

    private static void receive(final DistributedItemBusState state, final int meta, final long amount) {
        final PartitionStore<ItemId>.Transaction transaction = state.store()
            .transaction(true);
        assertTrue(transaction.offer(id(meta), amount));
        transaction.commit();
    }

    private static void actual(final MTEDistributedMEOutputBus bus, final int meta, final int amount) {
        final ItemEjectionHelper helper = new ItemEjectionHelper(Collections.singletonList(bus), true);
        assertEquals(1, helper.ejectItems(Collections.singletonList(item(meta, amount)), 1));
        helper.commit();
    }

    public void mteInstancesAndTransactionsAreIndependent() {
        final MTEDistributedMEOutputBus first = bus();
        final MTEDistributedMEOutputBus second = (MTEDistributedMEOutputBus) first.newMetaEntity(null);
        assertEquals(
            MTEHatchOutputBus.class,
            first.getClass()
                .getSuperclass());
        assertNotSame(first.state(), second.state());
        assertNotSame(
            first.state()
                .store(),
            second.state()
                .store());
        assertNotSame(
            first.state()
                .output(),
            second.state()
                .output());
        assertEquals(1, first.getRealInventory().length);
        first.configurePartitions(1);
        actual(first, 0, 350);
        actual(first, 1, 1000);
        assertEquals(
            350,
            first.state()
                .store()
                .quantity(id(0)));
        assertEquals(
            1000,
            first.state()
                .store()
                .quantity(id(1)));
        assertEquals(
            0,
            second.state()
                .store()
                .identityCount());
        final ItemEjectionHelper check = new ItemEjectionHelper(Collections.singletonList(first), true, true);
        assertEquals(0, check.ejectItems(Collections.singletonList(item(2, 1)), 1));
        assertFalse(appeng.api.storage.ICellProvider.class.isAssignableFrom(first.getClass()));
        assertFalse(appeng.api.storage.ICellContainer.class.isAssignableFrom(first.getClass()));
    }

    public void genericBusHazardsAreDisabled() {
        final MTEDistributedMEOutputBus bus = bus();
        assertFalse(bus.pushOutputInventory());
        assertFalse(bus.allowPullStack(null, 0, ForgeDirection.NORTH, item(0, 1)));
        assertFalse(bus.allowPutStack(null, 0, ForgeDirection.NORTH, item(0, 1)));
        assertFalse(bus.shouldDropItemAt(0));
        assertFalse(bus.acceptsConfigCopy());
        assertFalse(bus.pasteCopiedData(null, new NBTTagCompound()));
        assertTrue(
            bus.getCopiedData(null)
                .hasNoTags());
        assertFalse(bus.isLocked());
        bus.onScrewdriverRightClick(ForgeDirection.NORTH, null, 0, 0, 0, null);
        assertFalse(bus.isLocked());
        assertEquals(1, bus.getInventoryStackLimit());
    }

    public void fortyTickScheduleDoesNotBankIdleTime() {
        final DistributedItemBusState state = new DistributedItemBusState();
        for (int tick = 0; tick < 400; tick++) assertFalse(state.tick());
        receive(state, 0, 100);
        for (int tick = 1; tick < 40; tick++) assertFalse(state.tick());
        assertTrue(state.tick());
        state.setPeriod(Integer.MAX_VALUE);
        for (int tick = 0; tick < 80; tick++) assertFalse(state.tick());
    }

    public void completePartialAndRejectedInsertionConserveStock() {
        for (final int left : new int[] { 0, 30, 100 }) {
            final DistributedItemBusState state = new DistributedItemBusState();
            receive(state, 0, 100);
            final AtomicInteger calls = new AtomicInteger();
            state.flush((identity, amount) -> {
                calls.incrementAndGet();
                assertEquals(100, amount);
                return left == 0 ? null : new DistributedItemBusState.Remainder(identity, left);
            }, () -> {});
            assertEquals(
                left,
                state.store()
                    .quantity(id(0)));
            assertEquals(1, calls.get());
            assertFalse(state.locked());
            assertEquals(
                BigInteger.valueOf(left),
                state.store()
                    .totalQuantity());
        }
    }

    public void outputAmountsNeverExceedIntBridge() {
        final DistributedItemBusState state = new DistributedItemBusState();
        receive(state, 0, (long) Integer.MAX_VALUE + 42);
        state.flush((identity, amount) -> {
            assertEquals(Integer.MAX_VALUE, amount);
            return null;
        }, () -> {});
        assertEquals(
            42,
            state.store()
                .quantity(id(0)));
        state.flush((identity, amount) -> {
            assertEquals(42, amount);
            return null;
        }, () -> {});
        assertEquals(
            0,
            state.store()
                .identityCount());
    }

    public void fiveThousandEmergencyIdentitiesRotateInBoundedBatches() throws Exception {
        final DistributedItemBusState state = new DistributedItemBusState();
        for (int meta = 0; meta < 5000; meta++) receive(state, meta, 100);
        final Set<ItemId> visited = new HashSet<>();
        for (int cycle = 0; cycle < 313; cycle++) {
            final AtomicInteger calls = new AtomicInteger();
            state.flush((identity, amount) -> {
                calls.incrementAndGet();
                visited.add(identity);
                return new DistributedItemBusState.Remainder(identity, amount);
            }, () -> {});
            assertEquals(cycle == 312 ? 8 : 16, calls.get());
        }
        assertEquals(5000, visited.size());
        assertEquals(
            BigInteger.valueOf(500000),
            state.store()
                .totalQuantity());
        final NBTTagCompound saved = state.write();
        System.out.println(
            "Distributed bus synthetic 5000-type fixture: identitiesVisited=" + visited.size()
                + ", gzipNBTBytes="
                + net.minecraft.nbt.CompressedStreamTools.compress(saved).length
                + ", maxIdentitiesPerFlush=16; no Minecraft world/MSPT measurement");
        final DistributedItemBusState restored = DistributedItemBusState.read(saved, null);
        assertEquals(
            id(0),
            restored.store()
                .nextOutputBatch(16)
                .get(0).identity);
        assertEquals(
            5000,
            restored.store()
                .identityCount());
        assertEquals(
            state.store()
                .totalQuantity(),
            restored.store()
                .totalQuantity());
        assertTrue(
            saved.toString()
                .length() > 100000); // Full disk snapshot is intentionally O(identities).
    }

    public void uncertainOutputCannotReplayAcrossSaveReload() {
        final DistributedItemBusState state = new DistributedItemBusState();
        receive(state, 0, 100);
        state.flush((identity, amount) -> { throw new IllegalStateException("may have inserted"); }, () -> {});
        assertTrue(state.locked());
        assertEquals(
            100,
            state.store()
                .quantity(id(0)));
        final DistributedItemBusState restored = DistributedItemBusState.read(state.write(), null);
        assertTrue(restored.locked());
        assertTrue(
            restored.write()
                .hasKey("flight"));
        assertFalse(restored.flush((identity, amount) -> {
            fail("must not replay");
            return null;
        }, () -> {}));
    }

    public void badRemainderNeverDebitsAndPersistsEvidence() {
        for (final long remainder : new long[] { -1, 101, Long.MAX_VALUE }) {
            final DistributedItemBusState state = new DistributedItemBusState();
            receive(state, 0, 100);
            state.flush((identity, amount) -> new DistributedItemBusState.Remainder(identity, remainder), () -> {});
            assertTrue(state.locked());
            assertEquals(
                100,
                state.store()
                    .quantity(id(0)));
        }
        final DistributedItemBusState state = new DistributedItemBusState();
        receive(state, 0, 100);
        state.flush((identity, amount) -> new DistributedItemBusState.Remainder(id(1), 10), () -> {});
        assertTrue(state.locked());
    }

    public void synchronousRemovalDisarmsOldBusAndPreservesJournal() {
        final DistributedItemBusState state = new DistributedItemBusState();
        receive(state, 0, 100);
        final NBTTagCompound[] drop = { null };
        state.flush((identity, amount) -> {
            drop[0] = state.write();
            state.stop();
            return null;
        }, () -> {});
        assertEquals(
            100,
            state.store()
                .quantity(id(0)));
        final DistributedItemBusState placed = DistributedItemBusState.read(drop[0], null);
        assertTrue(placed.locked());
        assertFalse(state.flush((identity, amount) -> null, () -> {}));
    }

    private static ItemBasicStorageCell registeredCell;

    private static ItemStack card() {
        if (registeredCell != null) return new ItemStack(registeredCell);
        final ItemBasicStorageCell item = new ItemBasicStorageCell(appeng.items.materials.MaterialType.Cell1kPart, 1);
        Item.itemRegistry.addObject(31020, "gtnhmelogisticsenhancement:capacity_test", item);
        registeredCell = item;
        return new ItemStack(item);
    }

    public void cardCopyCapacityAndStoredContentsStayUnchanged() {
        final ItemStack card = card();
        final appeng.api.storage.IMEInventoryHandler<appeng.api.storage.data.IAEItemStack> inventory = (appeng.api.storage.IMEInventoryHandler<appeng.api.storage.data.IAEItemStack>) appeng.me.storage.CellInventory
            .getCell(card, null, appeng.util.item.AEItemStackType.ITEM_STACK_TYPE);
        inventory.injectItems(AEItemStack.create(item(0, 123)), appeng.api.config.Actionable.MODULATE, null);
        final ItemStack before = card.copy();
        final CellCapacityPolicy policy = CellCapacityPolicy.inspect(card);
        assertTrue(policy.valid());
        assertEquals(7936, policy.capacity(4));
        assertTrue(ItemStack.areItemStacksEqual(before, card));
        final DistributedItemBusState state = new DistributedItemBusState();
        state.updateCard(card);
        assertEquals(
            0,
            state.store()
                .identityCount());
        assertEquals(
            7936,
            state.store()
                .capacity());
        state.updateCard(null);
        assertEquals(
            1600,
            state.store()
                .capacity());
        assertTrue(ItemStack.areItemStacksEqual(before, card));
    }

    public void nativeRestrictionAndPreciseFiltersSurviveReloadBeforeInput() {
        final ItemStack card = card();
        final ItemBasicStorageCell cell = (ItemBasicStorageCell) card.getItem();
        cell.setCellRestriction(card, new ICellRestriction.CellRestrictionData((byte) 1, 500));
        cell.getConfigAEInventory(card)
            .putAEStackInSlot(0, AEItemStack.create(item(0, 1)));
        final ItemStack before = card.copy();
        final CellCapacityPolicy policy = CellCapacityPolicy.inspect(card);
        assertTrue(policy.valid());
        assertEquals(500, policy.capacity(4));
        assertEquals(1, policy.types());
        assertTrue(policy.accepts(id(0)));
        assertFalse(policy.accepts(id(1)));
        assertTrue(ItemStack.areItemStacksEqual(before, card));
        final DistributedItemBusState state = new DistributedItemBusState();
        state.updateCard(card);
        final DistributedItemBusState loaded = DistributedItemBusState.read(state.write(), card);
        assertEquals(
            1,
            loaded.store()
                .effectivePartitions());
        assertEquals(
            500,
            loaded.store()
                .quota());
        assertFalse(
            loaded.output()
                .storePartial(item(1, 1), true));
        assertTrue(
            loaded.output()
                .storePartial(item(0, 1), true));
        assertEquals(
            0,
            loaded.store()
                .identityCount());
    }

    public void nativeAdvancedAndExtremeFiniteCellsAreAccepted() {
        final ItemStack advanced = new ItemStack(
            new ItemAdvancedStorageCell(appeng.items.materials.MaterialType.Cell256kPart, 256));
        assertTrue(
            CellCapacityPolicy.inspect(advanced)
                .valid());
        final ItemStack extreme = new ItemStack(new ItemExtremeStorageCell("test", 1000000000L, 1, 8, 1));
        assertTrue(
            CellCapacityPolicy.inspect(extreme)
                .valid());
        assertEquals(
            (1000000000L - 8) * 8,
            CellCapacityPolicy.inspect(extreme)
                .capacity(16));
    }

    public void invalidCardsAreNotExpansionAndNeverTrimStock() {
        assertFalse(
            CellCapacityPolicy.inspect(item(0, 1))
                .valid());
        assertFalse(
            CellCapacityPolicy
                .inspect(
                    new ItemStack(new ItemBasicStorageCell(com.google.common.base.Optional.of("unknown-subclass")) {}))
                .valid());
        final ItemStack invalid = card();
        invalid.stackSize = 2;
        assertFalse(
            CellCapacityPolicy.inspect(invalid)
                .valid());
        final DistributedItemBusState state = new DistributedItemBusState();
        receive(state, 0, 10000);
        state.updateCard(invalid);
        assertEquals(
            10000,
            state.store()
                .quantity(id(0)));
        assertEquals(
            0,
            state.store()
                .capacity());
        assertFalse(
            state.output()
                .storePartial(item(1, 1), true));
        state.updateCard(null);
        assertEquals(
            10000,
            state.store()
                .quantity(id(0)));
    }

    public void mteMachineItemNbtPreservesCardAndAllStock() {
        final MTEDistributedMEOutputBus old = bus();
        final ItemStack card = card();
        old.getRealInventory()[0] = card;
        old.onContentsChanged(0);
        receive(old.state(), 0, Long.MAX_VALUE);
        receive(old.state(), 1, 2000);
        old.configurePeriod(80);
        final NBTTagCompound dropped = new NBTTagCompound();
        old.setItemNBT(dropped);
        assertTrue(
            old.state()
                .locked());
        assertFalse(old.shouldDropItemAt(0));
        final MTEDistributedMEOutputBus placed = bus();
        // This is the exact CommonBaseMetaTileEntity inventory-before-MTE-load sequence (without a world).
        placed.getRealInventory()[0] = ItemStack.loadItemStackFromNBT(
            dropped.getTagList("Inventory", 10)
                .getCompoundTagAt(0));
        placed.loadNBTData(dropped);
        assertTrue(ItemStack.areItemStacksEqual(card, placed.getRealInventory()[0]));
        assertEquals(
            Long.MAX_VALUE,
            placed.state()
                .store()
                .quantity(id(0)));
        assertEquals(
            2000,
            placed.state()
                .store()
                .quantity(id(1)));
        assertEquals(
            80,
            placed.state()
                .period());
        assertFalse(
            placed.state()
                .locked());
    }

    public void safeRecipeRejectsEveryPayloadBearingIngredient() {
        final SafeDistributedBusRecipe recipe = new SafeDistributedBusRecipe(
            item(0, 1),
            new ItemStack(RECIPE_BUS, 1, 5),
            new ItemStack(RECIPE_CARD));
        final InventoryCrafting crafting = new InventoryCrafting(new Container() {

            @Override
            public boolean canInteractWith(final net.minecraft.entity.player.EntityPlayer player) {
                return true;
            }
        }, 3, 3);
        crafting.setInventorySlotContents(0, new ItemStack(RECIPE_CARD));
        crafting.setInventorySlotContents(8, new ItemStack(RECIPE_BUS, 1, 5));
        assertTrue(recipe.matches(crafting, null));
        assertFalse(
            recipe.getCraftingResult(crafting)
                .hasTagCompound());
        for (final String field : Arrays.asList("Inventory", "cache", "cachedItems", "cachedStack", "cell", "future")) {
            final NBTTagCompound tag = new NBTTagCompound();
            tag.setString(field, "payload");
            crafting.getStackInSlot(8)
                .setTagCompound(tag);
            assertFalse(recipe.matches(crafting, null));
        }
    }

    public void corruptEnvelopeIsRetainedAndNeverBecomesUsableEmptyStock() {
        final NBTTagCompound corrupt = new NBTTagCompound();
        corrupt.setLong("period", -1);
        final DistributedItemBusState state = DistributedItemBusState.read(corrupt, null);
        assertTrue(state.locked());
        assertEquals(
            corrupt,
            state.write()
                .getCompoundTag("quarantine"));
        assertTrue(
            DistributedItemBusState.read(state.write(), null)
                .locked());
    }

    public void configurationBoundsAndTypeLimitRetainLegacyStock() {
        final DistributedItemBusState state = new DistributedItemBusState();
        assertThrows(IllegalArgumentException.class, () -> state.setPartitions(0));
        assertThrows(IllegalArgumentException.class, () -> state.setPartitions(17));
        assertThrows(IllegalArgumentException.class, () -> state.setPeriod(39));
        for (int meta = 0; meta < 16; meta++) receive(state, meta, 100);
        state.setPartitions(1);
        assertEquals(
            16,
            state.store()
                .identityCount());
        assertEquals(
            BigInteger.valueOf(1600),
            state.store()
                .totalQuantity());
        state.store()
            .setTypeLimit(0);
        state.store()
            .configure(1, 0);
        assertFalse(
            state.output()
                .storePartial(item(17, 1), true));
        state.flush((identity, amount) -> null, () -> {});
        assertEquals(
            0,
            state.store()
                .identityCount());
    }

    public void totalBeyondLongAndEmergencyPagesAreExact() {
        final DistributedItemBusState state = new DistributedItemBusState();
        for (int meta = 0; meta < 40; meta++) receive(state, meta, Long.MAX_VALUE);
        assertEquals(
            BigInteger.valueOf(Long.MAX_VALUE)
                .multiply(BigInteger.valueOf(40)),
            state.store()
                .totalQuantity());
        assertEquals(
            BigInteger.valueOf(Long.MAX_VALUE)
                .multiply(BigInteger.valueOf(36)),
            state.store()
                .emergencyQuantity());
        assertEquals(
            16,
            state.store()
                .emergencyPage(0, 16)
                .size());
        assertEquals(
            36,
            state.store()
                .emergencyIdentityCount());
        assertEquals(
            4,
            state.store()
                .emergencyPage(32, 16)
                .size());
        assertEquals(
            0,
            state.store()
                .emergencyPage(64, 16)
                .size());
        final DistributedItemBusState restored = DistributedItemBusState.read(state.write(), null);
        assertEquals(
            state.store()
                .totalQuantity(),
            restored.store()
                .totalQuantity());
    }

    @SuppressWarnings("unchecked")
    private static appeng.api.storage.IMEInventory<appeng.api.storage.data.IAEItemStack> destination(
        final java.util.function.Function<appeng.api.storage.data.IAEItemStack, appeng.api.storage.data.IAEItemStack> receive) {
        return (appeng.api.storage.IMEInventory<appeng.api.storage.data.IAEItemStack>) java.lang.reflect.Proxy
            .newProxyInstance(
                DistributedBusFixture.class.getClassLoader(),
                new Class<?>[] { appeng.api.storage.IMEInventory.class },
                (proxy, method, args) -> {
                    if (method.getName()
                        .equals("injectItems")) return receive.apply((appeng.api.storage.data.IAEItemStack) args[0]);
                    if (method.getName()
                        .equals("getStackType")) return appeng.util.item.AEItemStackType.ITEM_STACK_TYPE;
                    throw new AssertionError("Unexpected inventory call: " + method);
                });
    }

    public void nativePoweredInsertSettlesOnlyAcceptedItemsAndHonorsAvailablePower() {
        for (final int available : new int[] { 0, 17, 100 }) {
            for (final int capacity : new int[] { 0, 30, 100 }) {
                final double[] spent = { 0 };
                final appeng.api.networking.energy.IEnergySource energy = (appeng.api.networking.energy.IEnergySource) java.lang.reflect.Proxy
                    .newProxyInstance(
                        DistributedBusFixture.class.getClassLoader(),
                        new Class<?>[] { appeng.api.networking.energy.IEnergySource.class },
                        (proxy, method, args) -> {
                            final double amount = Math.min(available, (Double) args[0]);
                            if (args[1] == appeng.api.config.Actionable.MODULATE) spent[0] += amount;
                            return amount;
                        });
                final CheckedBusDestination target = new CheckedBusDestination(destination(input -> {
                    final long accepted = Math.min(capacity, input.getStackSize());
                    return accepted == input.getStackSize() ? null
                        : input.copy()
                            .setStackSize(input.getStackSize() - accepted);
                }));
                final appeng.api.storage.data.IAEItemStack remaining = appeng.util.Platform.poweredInsert(
                    energy,
                    target,
                    AEItemStack.create(item(0, 100)),
                    new appeng.api.networking.security.MachineSource(bus()));
                final int expected = Math.min(available, capacity);
                assertEquals(expected, spent[0], 0);
                assertEquals(100 - expected, remaining == null ? 0 : remaining.getStackSize());
            }
        }
    }

    public void rawInvalidReceiptIsCaughtBeforePoweredInsertMasksIt() {
        for (final long amount : new long[] { -1, 101, Long.MAX_VALUE }) {
            final CheckedBusDestination target = new CheckedBusDestination(
                destination(
                    input -> input.copy()
                        .setStackSize(amount)));
            assertThrows(
                IllegalStateException.class,
                () -> target
                    .injectItems(AEItemStack.create(item(0, 100)), appeng.api.config.Actionable.MODULATE, null));
        }
        final CheckedBusDestination wrong = new CheckedBusDestination(
            destination(input -> AEItemStack.create(item(1, 1))));
        assertThrows(
            IllegalStateException.class,
            () -> wrong.injectItems(AEItemStack.create(item(0, 100)), appeng.api.config.Actionable.MODULATE, null));
    }

    public void corruptCardInventoryAndProxyDataRemainPreserved() {
        final MTEDistributedMEOutputBus bus = bus();
        final NBTTagCompound tag = new NBTTagCompound();
        final NBTTagCompound node = new NBTTagCompound();
        node.setInteger("player", 42);
        bus.saveNBTData(tag);
        tag.setTag("distributedAE", node);
        final net.minecraft.nbt.NBTTagList inventory = new net.minecraft.nbt.NBTTagList();
        final NBTTagCompound broken = new NBTTagCompound();
        broken.setInteger("IntSlot", 0);
        broken.setString("id", "missing:card");
        broken.setByte("Count", (byte) 1);
        inventory.appendTag(broken);
        tag.setTag("Inventory", inventory);
        bus.loadNBTData(tag);
        assertTrue(
            bus.state()
                .locked());
        final NBTTagCompound saved = new NBTTagCompound();
        bus.saveNBTData(saved);
        assertEquals(inventory, saved.getTag("distributedCardEvidence"));
        assertEquals(node, saved.getTag("distributedAE"));
        assertFalse(bus.canConfigure(null));
    }

    public void fixedIdConflictDoesNotOverwriteExistingMachine() {
        for (final gregtech.api.enums.MetaTileEntityIDs entry : gregtech.api.enums.MetaTileEntityIDs.values())
            assertNotEquals(DistributedBusRegistration.ITEM_BUS_ID, entry.ID);
        final gregtech.api.interfaces.metatileentity.IMetaTileEntity previous = gregtech.api.GregTechAPI.METATILEENTITIES[DistributedBusRegistration.ITEM_BUS_ID];
        final MTEDistributedMEOutputBus occupied = bus();
        try {
            gregtech.api.GregTechAPI.METATILEENTITIES[DistributedBusRegistration.ITEM_BUS_ID] = occupied;
            assertThrows(IllegalStateException.class, DistributedBusRegistration::register);
            assertSame(occupied, gregtech.api.GregTechAPI.METATILEENTITIES[DistributedBusRegistration.ITEM_BUS_ID]);
        } finally {
            gregtech.api.GregTechAPI.METATILEENTITIES[DistributedBusRegistration.ITEM_BUS_ID] = previous;
        }
    }

    public void realMuiPanelHasReadOnlyStockAndServerOnlyViewSync() throws Exception {
        final com.cleanroommc.modularui.value.sync.PanelSyncManager sync = new com.cleanroommc.modularui.value.sync.PanelSyncManager(
            new com.cleanroommc.modularui.value.sync.ModularSyncManager(false),
            true);
        final com.cleanroommc.modularui.screen.ModularPanel panel = bus()
            .buildUI(null, sync, new com.cleanroommc.modularui.screen.UISettings());
        final java.util.ArrayDeque<com.cleanroommc.modularui.api.widget.IWidget> pending = new java.util.ArrayDeque<>();
        pending.add(panel);
        int cardSlots = 0, readOnlyIcons = 0;
        while (!pending.isEmpty()) {
            final com.cleanroommc.modularui.api.widget.IWidget widget = pending.removeFirst();
            if (widget instanceof com.cleanroommc.modularui.widgets.slot.ItemSlot) cardSlots++;
            if (widget.getClass() == com.cleanroommc.modularui.widget.Widget.class) readOnlyIcons++;
            pending.addAll(widget.getChildren());
        }
        assertEquals(37, cardSlots); // One real card slot and 36 player slots; no virtual stock is an ItemSlot.
        assertEquals(32, readOnlyIcons);
        assertTrue(bus().forceUseMui2());
        final com.cleanroommc.modularui.value.sync.SyncHandler<?> view = sync.findSyncHandlerNullable("busView", 0);
        assertNotNull(view);
        assertFalse(view.isAllowC2S());
        assertThrows(
            java.io.IOException.class,
            () -> view.readOnServer(0, new net.minecraft.network.PacketBuffer(io.netty.buffer.Unpooled.buffer())));
    }

    public void capacityCardSlotUsesStableFacadeAndNativePolicies() {
        // A facade redeclaration would remain unmapped while the MTE override is renamed in a release JAR.
        assertThrows(
            NoSuchMethodException.class,
            () -> DistributedOutputHost.class.getDeclaredMethod("isItemValidForSlot", int.class, ItemStack.class));
        final MTEDistributedMEOutputBus bus = bus();
        final PanelSyncManager sync = new PanelSyncManager(new ModularSyncManager(true), true);
        final ItemStack fluidCard = new ItemStack(
            new com.glodblock.github.common.item.ItemBasicFluidStorageCell(
                com.glodblock.github.common.storage.CellType.Cell1kPart,
                0,
                1));
        assertCapacityCardSlot(bus, bus.buildUI(null, sync, new UISettings()), card(), fluidCard, item(0, 1));
    }

    static void assertCapacityCardSlot(final DistributedOutputHost<?> host, final ModularPanel panel,
        final ItemStack valid, final ItemStack wrongType, final ItemStack ordinary) {
        ModularSlot cardSlot = null;
        final java.util.ArrayDeque<IWidget> pending = new java.util.ArrayDeque<>();
        pending.add(panel);
        while (!pending.isEmpty()) {
            final IWidget widget = pending.removeFirst();
            if (widget instanceof ItemSlot && ((ItemSlot) widget).isSynced()) {
                final ModularSlot slot = ((ItemSlot) widget).getSlot();
                if (slot.getItemHandler() == host.machine().inventoryHandler) {
                    assertNull(cardSlot);
                    cardSlot = slot;
                }
            }
            pending.addAll(widget.getChildren());
        }
        assertNotNull(cardSlot);
        assertTrue(host.acceptsCapacityCard(valid));
        assertTrue(
            host.machine()
                .isItemValidForSlot(0, valid));
        assertTrue(cardSlot.isItemValid(valid));
        assertFalse(
            host.machine()
                .isItemValidForSlot(-1, valid));
        assertFalse(
            host.machine()
                .isItemValidForSlot(1, valid));
        for (final ItemStack invalid : new ItemStack[] { null, ordinary, wrongType }) {
            assertFalse(host.acceptsCapacityCard(invalid));
            assertFalse(
                host.machine()
                    .isItemValidForSlot(0, invalid));
            assertFalse(cardSlot.isItemValid(invalid));
        }
        host.state()
            .isolate("TEST_LOCKED");
        assertFalse(host.acceptsCapacityCard(valid));
        assertFalse(
            host.machine()
                .isItemValidForSlot(0, valid));
        assertFalse(cardSlot.isItemValid(valid));
    }

    public void nativeFuzzyOreAndInverterPoliciesUseCopiedCards() {
        // Actual API-created upgrades/configs; no third-party card or simplified filter substitute.
        final ItemStack card = card();
        final ItemBasicStorageCell cell = (ItemBasicStorageCell) card.getItem();
        final Item upgradeMaterial = appeng.api.AEApi.instance()
            .definitions()
            .materials()
            .cardInverter()
            .maybeStack(1)
            .get()
            .getItem();
        if (Item.getIdFromItem(upgradeMaterial) < 0)
            Item.itemRegistry.addObject(31021, "appliedenergistics2:test_material", upgradeMaterial);
        cell.getConfigAEInventory(card)
            .putAEStackInSlot(0, AEItemStack.create(item(0, 1)));
        final ItemStack inverted = card.copy();
        cell.getUpgradesInventory(inverted)
            .setInventorySlotContents(
                0,
                appeng.api.AEApi.instance()
                    .definitions()
                    .materials()
                    .cardInverter()
                    .maybeStack(1)
                    .get());
        assertFalse(
            CellCapacityPolicy.inspect(inverted)
                .accepts(id(0)));
        assertTrue(
            CellCapacityPolicy.inspect(inverted)
                .accepts(id(1)));
        final ItemStack fuzzy = card.copy();
        cell.getUpgradesInventory(fuzzy)
            .setInventorySlotContents(
                0,
                appeng.api.AEApi.instance()
                    .definitions()
                    .materials()
                    .cardFuzzy()
                    .maybeStack(1)
                    .get());
        cell.setFuzzyMode(fuzzy, appeng.api.config.FuzzyMode.IGNORE_ALL);
        assertTrue(
            CellCapacityPolicy.inspect(fuzzy)
                .valid());
        assertTrue(
            CellCapacityPolicy.inspect(fuzzy)
                .accepts(id(0)));
        net.minecraftforge.oredict.OreDictionary.registerOre("distributedTestOre", item(1, 1));
        final ItemStack ore = card.copy();
        cell.getUpgradesInventory(ore)
            .setInventorySlotContents(
                0,
                appeng.api.AEApi.instance()
                    .definitions()
                    .materials()
                    .cardOreFilter()
                    .maybeStack(1)
                    .get());
        cell.setOreFilter(ore, "distributedTestOre");
        final ItemStack before = ore.copy();
        assertTrue(
            CellCapacityPolicy.inspect(ore)
                .accepts(id(1)));
        assertFalse(
            CellCapacityPolicy.inspect(ore)
                .accepts(id(0)));
        assertTrue(ItemStack.areItemStacksEqual(before, ore));
    }
}
