package com.kyou.gtnhmelogisticsenhancement.distributed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;

import net.minecraft.init.Bootstrap;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import cpw.mods.fml.common.Loader;
import gregtech.api.interfaces.IOutputBus;
import gregtech.api.interfaces.IOutputHatch;
import gregtech.api.util.FluidEjectionHelper;
import gregtech.api.util.GTUtility.FluidId;
import gregtech.api.util.GTUtility.ItemId;
import gregtech.api.util.ItemEjectionHelper;

/** Real pinned GT helpers/interfaces/identities, with a minimal registry fixture, not a running GT controller. */
public class NativeOutputFixture {

    private static final Item ITEM = new Item();
    private static final Fluid FLUID = new Fluid("distributed_test_fluid");

    public static void registries() throws Exception {
        Loader.injectData("7", "10", "99", "99", "1.7.10", "9.05", new File("."), Collections.emptyList());
        Bootstrap.func_151354_b();
        Item.itemRegistry.addObject(31000, "gtnhmelogisticsenhancement:distributed_test", ITEM);
        FluidRegistry.registerFluid(FLUID);
    }

    public void nativeItemRecipeCheckAcceptsWholeOverageWithoutChangingStock() {
        final PartitionItemOutput bus = new PartitionItemOutput();
        this.items(bus, 0, 350);
        final ItemEjectionHelper check = new ItemEjectionHelper(Collections.singletonList(bus), true, true);
        assertEquals(8, check.ejectItems(Collections.singletonList(item(0, 1000)), 8));
        assertEquals(
            350,
            bus.store()
                .quantity(itemId(0)));
    }

    public void nativeItemActualParallelAndDuplicateIdentityCommitWholeBatch() {
        final PartitionItemOutput bus = new PartitionItemOutput();
        this.items(bus, 0, 350);
        final ItemEjectionHelper output = new ItemEjectionHelper(Collections.singletonList(bus), true);
        assertEquals(8, output.ejectItems(Arrays.asList(item(0, 400), item(0, 600)), 8));
        output.commit();
        assertEquals(
            8350,
            bus.store()
                .quantity(itemId(0)));
        assertEquals(
            1,
            bus.store()
                .entries()
                .size());
        final ItemEjectionHelper next = new ItemEjectionHelper(Collections.singletonList(bus), true, true);
        assertEquals(0, next.ejectItems(Collections.singletonList(item(0, 1)), 1));
    }

    public void nativeFluidRecipeCheckAndActualLargeChunksAgree() {
        final PartitionFluidOutput hatch = new PartitionFluidOutput();
        final FluidEjectionHelper check = new FluidEjectionHelper(Collections.singletonList(hatch), true, true);
        final FluidStack fluid = fluid(0, Integer.MAX_VALUE);
        assertEquals(3, check.ejectFluids(Arrays.asList(fluid, fluid.copy()), 3));
        assertTrue(
            hatch.store()
                .entries()
                .isEmpty());
        final FluidEjectionHelper output = new FluidEjectionHelper(Collections.singletonList(hatch), true);
        assertEquals(3, output.ejectFluids(Arrays.asList(fluid, fluid.copy()), 3));
        output.commit();
        assertEquals(
            Integer.MAX_VALUE * 6L,
            hatch.store()
                .quantity(FluidId.create(fluid)));
    }

    public void allFourVoidModeFlagPairsUseNativeHelperProtectionAndKeepOtherProducts() {
        // Real helper flags match the pinned four modes; client texture enum/bootstrap is outside this fixture.
        for (final boolean[] mode : new boolean[][] { { true, true }, { false, true }, { true, false },
            { false, false } }) {
            final PartitionItemOutput bus = new PartitionItemOutput();
            this.items(bus, 0, 1350);
            final ItemEjectionHelper check = new ItemEjectionHelper(Collections.singletonList(bus), mode[0], true);
            assertEquals(
                mode[0] ? 0 : 1,
                check.ejectItems(Arrays.asList(item(0, 1000), item(1, 200), item(2, 300)), 1));
            assertEquals(
                0,
                bus.store()
                    .quantity(itemId(1)));
            final ItemEjectionHelper actual = new ItemEjectionHelper(Collections.singletonList(bus), mode[0]);
            assertEquals(
                mode[0] ? 0 : 1,
                actual.ejectItems(Arrays.asList(item(0, 1000), item(1, 200), item(2, 300)), 1));
            actual.commit();
            assertEquals(
                1350,
                bus.store()
                    .quantity(itemId(0)));
            assertEquals(
                false,
                bus.store()
                    .hasEmergencyStock());
            assertEquals(
                200,
                bus.store()
                    .quantity(itemId(1)));
            assertEquals(
                300,
                bus.store()
                    .quantity(itemId(2)));

            final PartitionFluidOutput hatch = new PartitionFluidOutput();
            this.fluids(hatch, 0, 128000);
            final FluidEjectionHelper fluidCheck = new FluidEjectionHelper(
                Collections.singletonList(hatch),
                mode[1],
                true);
            assertEquals(
                mode[1] ? 0 : 1,
                fluidCheck.ejectFluids(Arrays.asList(fluid(0, 1000), fluid(1, 200), fluid(2, 300)), 1));
            assertEquals(
                0,
                hatch.store()
                    .quantity(FluidId.create(fluid(1, 1))));
            final FluidEjectionHelper fluidActual = new FluidEjectionHelper(Collections.singletonList(hatch), mode[1]);
            assertEquals(
                mode[1] ? 0 : 1,
                fluidActual.ejectFluids(Arrays.asList(fluid(0, 1000), fluid(1, 200), fluid(2, 300)), 1));
            fluidActual.commit();
            assertEquals(
                128000,
                hatch.store()
                    .quantity(FluidId.create(fluid(0, 1))));
            assertEquals(
                false,
                hatch.store()
                    .hasEmergencyStock());
            assertEquals(
                200,
                hatch.store()
                    .quantity(FluidId.create(fluid(1, 1))));
            assertEquals(
                300,
                hatch.store()
                    .quantity(FluidId.create(fluid(2, 1))));
        }
    }

    public void helpersTryOtherOutputsAfterOnePartitionRejects() {
        final PartitionItemOutput first = new PartitionItemOutput(new PartitionStore<>(1, 400));
        final PartitionItemOutput second = new PartitionItemOutput();
        this.items(first, 0, 1350);
        final ItemEjectionHelper helper = new ItemEjectionHelper(Arrays.<IOutputBus>asList(first, second), true);
        assertEquals(1, helper.ejectItems(Arrays.asList(item(0, 1000), item(1, 200)), 1));
        helper.commit();
        assertEquals(
            1350,
            first.store()
                .quantity(itemId(0)));
        assertEquals(
            1000,
            second.store()
                .quantity(itemId(0)));
        assertEquals(
            200,
            second.store()
                .quantity(itemId(1)));
    }

    public void actualInterfacesPassFlagsAndPreventSimulationCommitAndRepeatedCommit() {
        final PartitionItemOutput bus = new PartitionItemOutput();
        final PartitionItemOutput.Transaction simulation = bus.createTransaction();
        simulation.setRecipeCheck(true);
        simulation.setProtectOutput(true);
        assertTrue(simulation.isRecipeCheck());
        assertTrue(simulation.isProtectOutput());
        assertTrue(simulation.storePartial(itemId(0), item(0, 1000), 0, 0));
        simulation.complete(itemId(0));
        assertThrows(IllegalStateException.class, simulation::commit);
        assertEquals(
            0,
            bus.store()
                .quantity(itemId(0)));
        final PartitionItemOutput.Transaction actual = bus.createTransaction();
        actual.storePartial(itemId(0), item(0, 1000), 0, 0);
        actual.complete(itemId(0));
        actual.commit();
        assertThrows(IllegalStateException.class, actual::commit);
        assertEquals(
            1000,
            bus.store()
                .quantity(itemId(0)));
    }

    public void nativeIdentitiesAndNbtKeepItemMetaAndBothKindsOfTagsSeparate() {
        final PartitionItemOutput bus = new PartitionItemOutput();
        final ItemStack first = item(0, 100);
        first.setTagCompound(tag(1));
        final ItemStack second = item(0, 200);
        second.setTagCompound(tag(2));
        bus.storePartial(first);
        bus.storePartial(second);
        this.items(bus, 1, 300);
        assertEquals(
            3,
            bus.store()
                .entries()
                .size());
        final NBTTagCompound saved = PartitionNbt.write(bus.store(), DistributedIdentities.ITEMS);
        assertEquals(
            saved,
            PartitionNbt.write(PartitionNbt.read(saved, DistributedIdentities.ITEMS), DistributedIdentities.ITEMS));

        final PartitionFluidOutput hatch = new PartitionFluidOutput();
        this.fluids(hatch, 0, 100);
        this.fluids(hatch, 1, 200);
        assertEquals(
            2,
            hatch.store()
                .entries()
                .size());
        final NBTTagCompound fluidSaved = PartitionNbt.write(hatch.store(), DistributedIdentities.FLUIDS);
        assertEquals(
            fluidSaved,
            PartitionNbt
                .write(PartitionNbt.read(fluidSaved, DistributedIdentities.FLUIDS), DistributedIdentities.FLUIDS));
    }

    public void callerNbtMutationDoesNotChangeStoredIdentityOrMergePartitions() {
        final PartitionFluidOutput hatch = new PartitionFluidOutput();
        final FluidStack stack = fluid(1, 100);
        final FluidId original = FluidId.create(stack);
        assertTrue(hatch.storePartial(stack));
        stack.tag.setInteger("identity", 2);
        assertEquals(
            100,
            hatch.store()
                .quantity(original));
        assertEquals(
            0,
            hatch.store()
                .quantity(FluidId.create(stack)));
    }

    public void rejectedAndSimulatedDirectCallsKeepRealStockAndPreserveRemainders() {
        final IOutputBus bus = new PartitionItemOutput();
        final ItemStack simulated = item(0, 1000);
        assertTrue(bus.storePartial(simulated, true));
        assertEquals(0, simulated.stackSize);
        assertTrue(
            ((PartitionItemOutput) bus).store()
                .entries()
                .isEmpty());
        assertTrue(bus.storePartial(item(0, 1000)));
        final ItemStack denied = item(0, 30);
        assertFalse(bus.storePartial(denied));
        assertEquals(30, denied.stackSize);
        final IOutputHatch hatch = new PartitionFluidOutput();
        final FluidStack simulatedFluid = fluid(0, 128000);
        assertTrue(hatch.storePartial(simulatedFluid, true));
        assertEquals(0, simulatedFluid.amount);
        assertTrue(
            ((PartitionFluidOutput) hatch).store()
                .entries()
                .isEmpty());
    }

    public void independentPrechecksDoNotAuthorizeLaterFullActualTransactions() {
        final PartitionItemOutput bus = new PartitionItemOutput(new PartitionStore<>(1, 400));
        for (int meta = 0; meta < 2; meta++) {
            final ItemEjectionHelper check = new ItemEjectionHelper(Collections.singletonList(bus), true, true);
            assertEquals(1, check.ejectItems(Collections.singletonList(item(meta, 1000)), 1));
        }
        final ItemEjectionHelper first = new ItemEjectionHelper(Collections.singletonList(bus), true);
        assertEquals(1, first.ejectItems(Collections.singletonList(item(0, 1000)), 1));
        first.commit();
        final ItemEjectionHelper second = new ItemEjectionHelper(Collections.singletonList(bus), true);
        assertEquals(0, second.ejectItems(Collections.singletonList(item(1, 1000)), 1));
        second.commit();
        assertEquals(
            1000,
            bus.store()
                .quantity(itemId(0)));
        assertEquals(
            0,
            bus.store()
                .quantity(itemId(1)));
        assertEquals(
            1,
            bus.store()
                .normalEntries()
                .size());
        assertEquals(
            0,
            bus.store()
                .overflowIdentityCount());
        final ItemEjectionHelper future = new ItemEjectionHelper(Collections.singletonList(bus), true, true);
        assertEquals(0, future.ejectItems(Collections.singletonList(item(2, 1)), 1));
        assertFalse(bus.storePartial(item(1, 10)));
        // A recipe precheck does not reserve capacity for a later, independently created output transaction.
    }

    public void nativeHelpersRespectOneAndSixteenPartitionsAndNeverCreateTheNextIdentity() {
        for (final int n : new int[] { 1, 4, 16 }) {
            final PartitionItemOutput bus = new PartitionItemOutput(new PartitionStore<>(n, 1600));
            final java.util.List<ItemStack> outputs = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) outputs.add(item(i, 1000));
            final ItemEjectionHelper actual = new ItemEjectionHelper(Collections.singletonList(bus), true);
            assertEquals(1, actual.ejectItems(outputs, 1));
            actual.commit();
            final ItemEjectionHelper extra = new ItemEjectionHelper(Collections.singletonList(bus), true, true);
            assertEquals(0, extra.ejectItems(Collections.singletonList(item(n, 1000)), 1));
            assertEquals(
                n,
                bus.store()
                    .entries()
                    .size());
        }
    }

    public void sharedSameIdentityAndFluidPrechecksCannotReserveFutureOutput() {
        final PartitionItemOutput bus = new PartitionItemOutput(new PartitionStore<>(1, 400));
        this.items(bus, 0, 350);
        for (int machine = 0; machine < 2; machine++) {
            final ItemEjectionHelper check = new ItemEjectionHelper(Collections.singletonList(bus), true, true);
            assertEquals(1, check.ejectItems(Collections.singletonList(item(0, 1000)), 1));
        }
        for (int machine = 0; machine < 2; machine++) {
            final ItemEjectionHelper output = new ItemEjectionHelper(Collections.singletonList(bus), true);
            assertEquals(machine == 0 ? 1 : 0, output.ejectItems(Collections.singletonList(item(0, 1000)), 1));
            output.commit();
        }
        assertEquals(
            1350,
            bus.store()
                .quantity(itemId(0)));
        assertEquals(
            1,
            bus.store()
                .identityCount());
        final PartitionFluidOutput hatch = new PartitionFluidOutput(new PartitionStore<>(1, 400));
        this.fluids(hatch, 0, 350);
        for (int machine = 0; machine < 2; machine++) {
            final FluidEjectionHelper check = new FluidEjectionHelper(Collections.singletonList(hatch), true, true);
            assertEquals(1, check.ejectFluids(Collections.singletonList(fluid(0, 1000)), 1));
        }
        for (int machine = 0; machine < 2; machine++) {
            final FluidEjectionHelper output = new FluidEjectionHelper(Collections.singletonList(hatch), true);
            assertEquals(machine == 0 ? 1 : 0, output.ejectFluids(Collections.singletonList(fluid(0, 1000)), 1));
            output.commit();
        }
        assertEquals(
            1350,
            hatch.store()
                .quantity(FluidId.create(fluid(0, 1))));
        assertEquals(
            1,
            hatch.store()
                .identityCount());
    }

    public void failedGtCommitKeepsEvidenceAndCompleteNormalizesQuantityBearingIds() {
        final PartitionItemOutput bus = new PartitionItemOutput();
        final PartitionItemOutput.Transaction first = bus.createTransaction();
        final PartitionItemOutput.Transaction second = bus.createTransaction();
        first.storePartial(itemId(0), item(0, 1000), 0, 0);
        second.storePartial(itemId(1), item(1, 2000), 0, 0);
        first.commit();
        bus.store()
            .configure(1, 400);
        assertThrows(PartitionStore.CommitConflictException.class, second::commit);
        assertEquals(
            Long.valueOf(2000),
            second.pending()
                .get(itemId(1)));
        assertEquals(
            0,
            bus.store()
                .quantity(itemId(1)));
        bus.store()
            .configure(4, 1600);
        final PartitionItemOutput.Transaction completed = bus.createTransaction();
        completed.storePartial(itemId(2), item(2, 1000), 0, 0);
        completed.complete(ItemId.createWithStackSize(item(2, 50)));
        assertThrows(IllegalStateException.class, () -> completed.storePartial(itemId(2), item(2, 10), 0, 0));
        completed.commit();
        assertEquals(
            1000,
            bus.store()
                .quantity(itemId(2)));
    }

    public void invalidNativeIdentityNbtCannotTurnIntoDefaultRegistryStock() {
        final PartitionItemOutput bus = new PartitionItemOutput();
        this.items(bus, 0, 1000);
        final NBTTagCompound itemData = PartitionNbt.write(bus.store(), DistributedIdentities.ITEMS);
        itemData.getTagList("entries", 10)
            .getCompoundTagAt(0)
            .getCompoundTag("identity")
            .setString("item", "gtnhmelogisticsenhancement:missing_identity");
        final PartitionNbt.Loaded<ItemId> itemResult = PartitionNbt.read(itemData, DistributedIdentities.ITEMS);
        assertTrue(itemResult.isolated());
        assertEquals(itemData, itemResult.evidence());
        final PartitionFluidOutput hatch = new PartitionFluidOutput();
        this.fluids(hatch, 0, 1000);
        final NBTTagCompound fluidData = PartitionNbt.write(hatch.store(), DistributedIdentities.FLUIDS);
        fluidData.getTagList("entries", 10)
            .getCompoundTagAt(0)
            .getCompoundTag("identity")
            .setInteger("Amount", 1);
        assertTrue(
            PartitionNbt.read(fluidData, DistributedIdentities.FLUIDS)
                .isolated());
        final PartitionFluidOutput large = new PartitionFluidOutput();
        final FluidEjectionHelper output = new FluidEjectionHelper(Collections.singletonList(large), true);
        output.ejectFluids(Collections.singletonList(fluid(0, Integer.MAX_VALUE)), 3);
        output.commit();
        final NBTTagCompound saved = PartitionNbt.write(large.store(), DistributedIdentities.FLUIDS);
        assertEquals(
            Integer.MAX_VALUE * 3L,
            PartitionNbt.read(saved, DistributedIdentities.FLUIDS)
                .store()
                .quantity(FluidId.create(fluid(0, 1))));
    }

    public void protectedActualTransactionsRespectCurrentAdmissionAndPreserveRemainders() {
        final PartitionItemOutput bus = new PartitionItemOutput(new PartitionStore<>(1, 400));
        this.items(bus, 0, 1000);
        final PartitionItemOutput.Transaction simulated = bus.createTransaction();
        simulated.setRecipeCheck(true);
        simulated.setProtectOutput(true);
        assertFalse(simulated.hasAvailableSpace());
        assertFalse(simulated.storePartial(itemId(1), item(1, 1), 0, 0));
        assertThrows(IllegalStateException.class, simulated::commit);
        assertFalse(bus.storePartial(item(1, 1), true));
        assertFalse(bus.storePartial(item(1, 1), false));
        final PartitionItemOutput.Transaction actual = bus.createTransaction();
        actual.setProtectOutput(true);
        assertFalse(actual.hasAvailableSpace());
        final ItemStack remainder = item(1, 1);
        assertFalse(actual.storePartial(itemId(1), remainder, 0, 0));
        assertEquals(1, remainder.stackSize);
        actual.commit();
        final ItemEjectionHelper helper = new ItemEjectionHelper(Collections.singletonList(bus), true);
        assertEquals(0, helper.ejectItems(Collections.singletonList(item(1, 1)), 1));
        helper.commit();
        assertEquals(
            1000,
            bus.store()
                .quantity(itemId(0)));
        assertEquals(
            0,
            bus.store()
                .quantity(itemId(1)));
        assertEquals(
            1,
            bus.store()
                .identityCount());
        assertFalse(
            bus.store()
                .hasEmergencyStock());
    }

    public void historicalEmergencyItemsRemainVisibleAndBlockNativeNewTypes() {
        final PartitionItemOutput bus = new PartitionItemOutput(new PartitionStore<>(16, 1600));
        final java.util.List<ItemStack> outputs = new java.util.ArrayList<>();
        final PartitionStore<ItemId>.Transaction historical = bus.store()
            .transaction(true);
        for (int i = 0; i < 64; i++) {
            outputs.add(item(i, 1));
            assertTrue(historical.offer(itemId(i), 1));
        }
        historical.commit();
        assertEquals(
            64,
            bus.store()
                .identityCount());
        assertEquals(
            16,
            bus.store()
                .normalEntries()
                .size());
        assertEquals(
            48,
            bus.store()
                .overflowIdentityCount());
        final ItemEjectionHelper check = new ItemEjectionHelper(Collections.singletonList(bus), true, true);
        assertEquals(0, check.ejectItems(outputs, 1));
        final ItemEjectionHelper actual = new ItemEjectionHelper(Collections.singletonList(bus), true);
        assertEquals(0, actual.ejectItems(Collections.singletonList(item(64, 1)), 1));
        actual.commit();
        assertEquals(
            64,
            bus.store()
                .identityCount());
        assertEquals(
            0,
            bus.store()
                .quantity(itemId(64)));
        for (final PartitionStore.Entry<ItemId> entry : bus.store()
            .entries()) {
            if (entry.slot < 0) {
                final ItemEjectionHelper future = new ItemEjectionHelper(Collections.singletonList(bus), true, true);
                assertEquals(0, future.ejectItems(Collections.singletonList(entry.identity.getItemStack(1)), 1));
            }
        }
    }

    public void historicalEmergencyFluidAmountsSurviveLongNbtRoundTrip() {
        final PartitionFluidOutput hatch = new PartitionFluidOutput(new PartitionStore<>(1, 400));
        this.fluids(hatch, 0, 1000);
        final FluidId identity = FluidId.create(fluid(1, 1));
        final long expected = Integer.MAX_VALUE * 6L;
        EmergencyPartitionTest.emergency(hatch.store(), identity, expected);
        assertEquals(
            expected,
            hatch.store()
                .quantity(identity));
        assertEquals(
            1,
            hatch.store()
                .overflowIdentityCount());
        final NBTTagCompound saved = PartitionNbt.write(hatch.store(), DistributedIdentities.FLUIDS);
        final PartitionStore<FluidId> restored = PartitionNbt.read(saved, DistributedIdentities.FLUIDS)
            .store();
        assertEquals(expected, restored.quantity(identity));
        assertEquals(saved, PartitionNbt.write(restored, DistributedIdentities.FLUIDS));
        final FluidEjectionHelper actual = new FluidEjectionHelper(Collections.singletonList(hatch), true);
        assertEquals(0, actual.ejectFluids(Collections.singletonList(fluid(1, 1)), 1));
        actual.commit();
        assertEquals(
            expected,
            hatch.store()
                .quantity(identity));
    }

    public void protectedCommitPreservesAlreadyStagedOutputAfterPolicyChange() {
        final PartitionItemOutput bus = new PartitionItemOutput();
        final java.util.List<ItemStack> outputs = Arrays.asList(item(0, 1000), item(1, 2000));
        final ItemEjectionHelper check = new ItemEjectionHelper(Collections.singletonList(bus), true, true);
        assertEquals(1, check.ejectItems(outputs, 1));
        final ItemEjectionHelper actual = new ItemEjectionHelper(Collections.singletonList(bus), true);
        assertEquals(1, actual.ejectItems(outputs, 1));
        bus.store()
            .configure(1, 0);
        bus.store()
            .setAdmissionFilter(id -> false);
        actual.commit();
        assertEquals(
            3000,
            bus.store()
                .totalQuantity()
                .longValueExact());
        assertTrue(
            bus.store()
                .hasEmergencyStock());
        final ItemEjectionHelper futureCheck = new ItemEjectionHelper(Collections.singletonList(bus), true, true);
        assertEquals(0, futureCheck.ejectItems(outputs, 1));
        final ItemEjectionHelper futureActual = new ItemEjectionHelper(Collections.singletonList(bus), true);
        assertEquals(0, futureActual.ejectItems(outputs, 1));
        futureActual.commit();
        assertFalse(bus.storePartial(item(2, 10)));
        assertEquals(
            3000,
            bus.store()
                .totalQuantity()
                .longValueExact());
        assertEquals(
            2,
            bus.store()
                .identityCount());
    }

    public void nativeTwoHelpersCanStageBeforeEitherCommitAndRebaseCompleteDeltas() {
        final PartitionItemOutput bus = new PartitionItemOutput(new PartitionStore<>(1, 400));
        final ItemEjectionHelper first = new ItemEjectionHelper(Collections.singletonList(bus), true);
        final ItemEjectionHelper second = new ItemEjectionHelper(Collections.singletonList(bus), true);
        assertEquals(1, first.ejectItems(Collections.singletonList(item(0, 1000)), 1));
        assertEquals(1, second.ejectItems(Collections.singletonList(item(1, 1000)), 1));
        first.commit();
        second.commit();
        assertEquals(
            1000,
            bus.store()
                .quantity(itemId(0)));
        assertEquals(
            1000,
            bus.store()
                .quantity(itemId(1)));
        assertEquals(
            1,
            bus.store()
                .overflowIdentityCount());
        assertThrows(IllegalStateException.class, second::commit);
        assertEquals(
            2000,
            bus.store()
                .totalQuantity()
                .longValueExact());
    }

    public void historicalEmergencyTypesDrainAndPromoteUsingTheCommonOutputView() {
        final PartitionItemOutput bus = new PartitionItemOutput(new PartitionStore<>(1, 400));
        EmergencyPartitionTest.emergency(bus.store(), itemId(0), 1000);
        EmergencyPartitionTest.emergency(bus.store(), itemId(1), 10);
        final ItemId normal = bus.store()
            .normalEntries()
            .get(0).identity;
        bus.store()
            .remove(
                normal,
                bus.store()
                    .quantity(normal));
        assertEquals(
            1,
            bus.store()
                .identityCount());
        assertEquals(
            0,
            bus.store()
                .overflowIdentityCount());
        final ItemId remaining = bus.store()
            .nextOutputBatch(1)
            .get(0).identity;
        assertEquals(
            1,
            bus.store()
                .nextOutputBatch(1)
                .size());
        bus.store()
            .remove(
                remaining,
                bus.store()
                    .quantity(remaining));
        assertEquals(
            0,
            bus.store()
                .identityCount());
        final ItemEjectionHelper check = new ItemEjectionHelper(Collections.singletonList(bus), true, true);
        assertEquals(1, check.ejectItems(Collections.singletonList(item(2, 1000)), 1));
    }

    public void actualPhaseFlagsCannotBeChangedAfterAnyInventoryDecision() {
        final PartitionItemOutput bus = new PartitionItemOutput(new PartitionStore<>(1, 400));
        this.items(bus, 0, 1000);
        final PartitionItemOutput.Transaction strict = bus.createTransaction();
        strict.setRecipeCheck(true);
        strict.setProtectOutput(true);
        assertFalse(strict.hasAvailableSpace());
        assertThrows(IllegalStateException.class, () -> strict.setRecipeCheck(false));
        assertFalse(strict.storePartial(itemId(1), item(1, 10), 0, 0));
        final PartitionItemOutput.Transaction actual = bus.createTransaction();
        actual.setProtectOutput(true);
        assertFalse(actual.hasAvailableSpace());
        assertThrows(IllegalStateException.class, () -> actual.setProtectOutput(false));
        assertFalse(actual.storePartial(itemId(1), item(1, 10), 0, 0));
        actual.complete(itemId(1));
        actual.commit();
        assertEquals(
            0,
            bus.store()
                .quantity(itemId(1)));
        final PartitionItemOutput empty = new PartitionItemOutput();
        final PartitionItemOutput.Transaction admitted = empty.createTransaction();
        admitted.setProtectOutput(true);
        assertTrue(admitted.storePartial(itemId(1), item(1, 10), 0, 0));
        assertThrows(IllegalStateException.class, () -> admitted.setRecipeCheck(true));
        admitted.commit();
        assertEquals(
            10,
            empty.store()
                .quantity(itemId(1)));
    }

    public void protectedCommitOverflowPreservesStockAndNonMaterializedFailureEvidence() {
        final PartitionItemOutput bus = new PartitionItemOutput(new PartitionStore<>(1, 400));
        final PartitionItemOutput.Transaction actual = bus.createTransaction();
        actual.setProtectOutput(true);
        assertTrue(actual.storePartial(itemId(0), item(0, 1), 0, 0));
        PartitionStoreTest.add(bus.store(), itemId(0), Long.MAX_VALUE);
        assertThrows(ArithmeticException.class, actual::commit);
        assertEquals(
            Long.MAX_VALUE,
            bus.store()
                .quantity(itemId(0)));
        assertEquals(
            Long.valueOf(1),
            actual.pending()
                .get(itemId(0)));
        assertEquals(
            Long.valueOf(1),
            bus.store()
                .failure().pending.get(itemId(0)));
        assertEquals(
            "ARITHMETIC",
            bus.store()
                .failure().reason);
        final NBTTagCompound saved = PartitionNbt.write(bus.store(), DistributedIdentities.ITEMS);
        final PartitionStore<ItemId> restored = PartitionNbt.read(saved, DistributedIdentities.ITEMS)
            .store();
        assertEquals(Long.MAX_VALUE, restored.quantity(itemId(0)));
        assertThrows(IllegalStateException.class, restored::transaction);
        assertEquals(saved, PartitionNbt.write(restored, DistributedIdentities.ITEMS));
    }

    public void duplicateOutputEndpointReferenceDoesNotReplayCommittedStock() {
        final PartitionItemOutput bus = new PartitionItemOutput(new PartitionStore<>(1, 400));
        final ItemEjectionHelper actual = new ItemEjectionHelper(Arrays.<IOutputBus>asList(bus, bus), true);
        assertEquals(1, actual.ejectItems(Arrays.asList(item(0, 1000), item(1, 1000)), 1));
        actual.commit();
        assertEquals(
            2000,
            bus.store()
                .totalQuantity()
                .longValueExact());
        assertEquals(
            2,
            bus.store()
                .identityCount());
    }

    public void mixedNonAwareOutputKeepsItsNativeInterfaceContractAndCapacity() {
        final OrdinaryOutputFixture ordinary = new OrdinaryOutputFixture();
        final PartitionItemOutput bus = new PartitionItemOutput(new PartitionStore<>(2, 800));
        this.items(bus, 0, 1000);
        final ItemEjectionHelper mixed = new ItemEjectionHelper(Arrays.asList(ordinary, bus), true);
        assertEquals(1, mixed.ejectItems(Collections.singletonList(item(1, 1000)), 1));
        mixed.commit();
        assertEquals(64, ordinary.stock);
        assertEquals(1, ordinary.commits);
        assertEquals(
            936,
            bus.store()
                .quantity(itemId(1)));
        assertEquals(
            1000,
            bus.store()
                .quantity(itemId(0)));
        final OrdinaryOutputFixture alone = new OrdinaryOutputFixture();
        final ItemEjectionHelper baseline = new ItemEjectionHelper(Collections.singletonList(alone), true);
        assertEquals(0, baseline.ejectItems(Collections.singletonList(item(1, 1000)), 1));
        baseline.commit();
        assertEquals(64, alone.stock);
        assertEquals(1, alone.commits);
    }

    /** Real GT interface fixture, not a claim that a full native MTE or ME proxy has been bootstrapped. */
    private static final class OrdinaryOutputFixture implements IOutputBus {

        private int stock;
        private int commits;

        @Override
        public boolean isFiltered() {
            return false;
        }

        @Override
        public boolean isFilteredToItem(final ItemId id) {
            return false;
        }

        @Override
        public gregtech.api.enums.OutputBusType getBusType() {
            return gregtech.api.enums.OutputBusType.StandardUnfiltered;
        }

        @Override
        public boolean storePartial(final ItemStack stack, final boolean simulate) {
            final int accepted = Math.min(64 - this.stock, stack.stackSize);
            if (!simulate) this.stock += accepted;
            stack.stackSize -= accepted;
            return stack.stackSize == 0;
        }

        @Override
        public gregtech.api.interfaces.IOutputBusTransaction createTransaction() {
            return new gregtech.api.interfaces.IOutputBusTransaction() {

                private int staged = OrdinaryOutputFixture.this.stock;

                @Override
                public IOutputBus getBus() {
                    return OrdinaryOutputFixture.this;
                }

                @Override
                public boolean hasAvailableSpace() {
                    return this.staged < 64;
                }

                @Override
                public boolean storePartial(final ItemId id, final ItemStack stack, final long total, final long each) {
                    final int accepted = Math.min(64 - this.staged, stack.stackSize);
                    this.staged += accepted;
                    stack.stackSize -= accepted;
                    return accepted > 0;
                }

                @Override
                public void complete(final ItemId id) {}

                @Override
                public void commit() {
                    OrdinaryOutputFixture.this.stock = this.staged;
                    OrdinaryOutputFixture.this.commits++;
                }
            };
        }
    }

    private void items(final PartitionItemOutput bus, final int meta, final int amount) {
        assertTrue(bus.storePartial(item(meta, amount)));
    }

    private void fluids(final PartitionFluidOutput hatch, final int identity, final int amount) {
        assertTrue(hatch.storePartial(fluid(identity, amount)));
    }

    private static ItemStack item(final int meta, final int amount) {
        return new ItemStack(ITEM, amount, meta);
    }

    private static ItemId itemId(final int meta) {
        return ItemId.create(ITEM, meta, null);
    }

    private static NBTTagCompound tag(final int value) {
        final NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("identity", value);
        return tag;
    }

    private static FluidStack fluid(final int identity, final int amount) {
        return new FluidStack(FLUID, amount, tag(identity));
    }
}
