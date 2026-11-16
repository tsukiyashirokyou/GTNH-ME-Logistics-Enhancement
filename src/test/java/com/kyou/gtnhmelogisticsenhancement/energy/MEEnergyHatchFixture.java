package com.kyou.gtnhmelogisticsenhancement.energy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.Collections;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagString;
import net.minecraftforge.common.util.ForgeDirection;

import com.kyou.gtnhmelogisticsenhancement.distributed.DistributedBusFixture;
import com.kyou.gtnhmelogisticsenhancement.energy.MEEnergySpec.Family;
import com.kyou.gtnhmelogisticsenhancement.energy.MEEnergySpec.Role;

import cpw.mods.fml.common.registry.GameRegistry;
import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.api.metatileentity.implementations.MTEHatchDynamo;
import gregtech.api.metatileentity.implementations.MTEHatchEnergy;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.util.ExoticEnergyInputHelper;
import gregtech.common.GTDummyWorld;
import tectech.mechanics.pipe.IConnectsToEnergyTunnel;
import tectech.thing.metaTileEntity.hatch.MTEHatchDynamoMulti;
import tectech.thing.metaTileEntity.hatch.MTEHatchDynamoTunnel;
import tectech.thing.metaTileEntity.hatch.MTEHatchEnergyMulti;
import tectech.thing.metaTileEntity.hatch.MTEHatchEnergyTunnel;

/** Registry-only bootstrap and actual GT APIs. No fake EU inventory, source, drain, or NBT codec. */
public class MEEnergyHatchFixture {

    private static final short TEST_ID = 31999;

    public static void registries() throws Exception {
        DistributedBusFixture.registries();
        GameRegistry.registerTileEntity(BaseMetaTileEntity.class, "me_energy_native_base_test");
        GameRegistry.registerTileEntity(DirtyBase.class, "me_energy_dirty_base_test");
        GregTechAPI.sBlockMachines = Blocks.stone; // Item carrier only; never a placed machine block.
    }

    private static IMEEnergyEndpoint hatch(final MEEnergySpec spec) {
        final String name = "test.me." + spec.key();
        if (spec.family() == Family.STANDARD) {
            return spec.role() == Role.ENERGY
                ? new MTEHatchMEEnergyStandard(name, spec, new String[0], new ITexture[0][][])
                : new MTEHatchMEDynamoStandard(name, spec, new String[0], new ITexture[0][][]);
        }
        return spec.role() == Role.ENERGY ? new MTEHatchMEEnergyMulti(name, spec, new String[0], new ITexture[0][][])
            : new MTEHatchMEDynamoMulti(name, spec, new String[0], new ITexture[0][][]);
    }

    private static BaseMetaTileEntity attach(final IMEEnergyEndpoint endpoint) {
        final BaseMetaTileEntity base = new BaseMetaTileEntity();
        endpoint.machine()
            .setBaseMetaTileEntity(base);
        endpoint.machine()
            .onFirstTick(base);
        return base;
    }

    private static Controller controller() {
        final Controller controller = new Controller();
        controller.setBaseMetaTileEntity(new BaseMetaTileEntity());
        return controller;
    }

    private static void credit(final IMEEnergyEndpoint endpoint, final int credit) {
        final MEEnergyLossAccount account = endpoint.energyState()
            .lossAccount();
        final int net = (account.credit() - credit + 19) % 19;
        account.record(net, account.grossCost(net));
        assertEquals(credit, account.credit());
    }

    public void allSpecsHaveNativeIdentityCapabilityAndIndependentClones() {
        for (final MEEnergyCatalog.Entry entry : MEEnergyCatalog.entries()) {
            final MEEnergySpec spec = entry.spec();
            final IMEEnergyEndpoint first = hatch(spec);
            final MTEHatch nativeHatch = (MTEHatch) first.machine();
            final Class<?> parent = spec.family() == Family.STANDARD
                ? spec.role() == Role.ENERGY ? MTEHatchEnergy.class : MTEHatchDynamo.class
                : spec.role() == Role.ENERGY ? MTEHatchEnergyMulti.class : MTEHatchDynamoMulti.class;
            assertSame(
                parent,
                nativeHatch.getClass()
                    .getSuperclass());
            assertFalse(nativeHatch instanceof MTEHatchEnergyTunnel);
            assertFalse(nativeHatch instanceof MTEHatchDynamoTunnel);
            assertFalse(nativeHatch instanceof IConnectsToEnergyTunnel);
            assertEquals(MTEHatch.ConnectionType.CABLE, nativeHatch.getConnectionType());
            assertEquals(spec.capacity(), nativeHatch.maxEUStore());
            assertEquals(spec.nativeReserve(), nativeHatch.getMinimumStoredEU());
            assertEquals(0, nativeHatch.getRealInventory().length);
            assertEquals(0, nativeHatch.getCapacity());
            assertFalse(nativeHatch.onRightclick(null, null));
            if (spec.role() == Role.ENERGY) {
                assertEquals(spec.voltage(), nativeHatch.maxEUInput());
                assertEquals(spec.amperage(), nativeHatch.maxWorkingAmperesIn());
                assertEquals(spec.family() == Family.MULTI ? spec.amperage() * 5L / 4 : 2, nativeHatch.maxAmperesIn());
            } else {
                assertEquals(spec.voltage(), nativeHatch.maxEUOutput());
                assertEquals(spec.amperage(), nativeHatch.maxAmperesOut());
                credit(first, 11);
            }
            final IMEEnergyEndpoint clone = (IMEEnergyEndpoint) nativeHatch.newMetaEntity(null);
            assertSame(
                nativeHatch.getClass(),
                clone.machine()
                    .getClass());
            assertEquals(spec, clone.spec());
            assertNotSame(first.energyState(), clone.energyState());
            attach(clone);
            assertEquals(0, clone.readEU());
            if (spec.role() == Role.DYNAMO) {
                assertNotSame(
                    first.energyState()
                        .lossAccount(),
                    clone.energyState()
                        .lossAccount());
                assertEquals(
                    0,
                    clone.energyState()
                        .lossAccount()
                        .credit());
            }
        }
        assertThrows(
            IllegalArgumentException.class,
            () -> new MTEHatchMEEnergyStandard(
                "bad",
                MEEnergySpec.multi(Role.ENERGY, 5, 4),
                new String[0],
                new ITexture[0][][]));
        assertThrows(
            IllegalArgumentException.class,
            () -> new MTEHatchMEDynamoMulti(
                "bad",
                MEEnergySpec.multi(Role.ENERGY, 5, 4),
                new String[0],
                new ITexture[0][][]));
    }

    public void sixPhysicalFacesAreClosedWithoutHidingElectricalCapability() {
        for (final MEEnergyCatalog.Entry entry : MEEnergyCatalog.entries()) {
            final IMEEnergyEndpoint endpoint = hatch(entry.spec());
            final MTEHatch machine = (MTEHatch) endpoint.machine();
            final BaseMetaTileEntity base = attach(endpoint);
            machine.setEUVar(
                entry.spec()
                    .capacity());
            assertTrue(machine.isElectric());
            assertFalse(machine.isEnetInput());
            assertFalse(machine.isEnetOutput());
            for (final ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
                assertFalse(machine.isInputFacing(side));
                assertFalse(machine.isOutputFacing(side));
                assertFalse(base.inputEnergyFrom(side, false));
                assertFalse(base.outputsEnergyTo(side, false));
                assertEquals(
                    0,
                    base.injectEnergyUnits(
                        side,
                        entry.spec()
                            .voltage(),
                        1));
                assertFalse(
                    base.drainEnergyUnits(
                        side,
                        entry.spec()
                            .voltage(),
                        1));
            }
            assertEquals(
                entry.spec()
                    .capacity(),
                endpoint.readEU());
            // Native UNKNOWN bypass exists and is explicitly outside our physical cable contract.
            assertTrue(base.inputEnergyFrom(ForgeDirection.UNKNOWN));
            assertTrue(base.outputsEnergyTo(ForgeDirection.UNKNOWN));
            if (entry.spec()
                .role() == Role.ENERGY)
                assertEquals(
                    entry.spec()
                        .voltage(),
                    base.getInputVoltage());
            else {
                assertEquals(0, base.getOutputVoltage());
                assertEquals(
                    entry.spec()
                        .voltage(),
                    machine.maxEUOutput());
                assertEquals(
                    entry.spec()
                        .amperage(),
                    base.getOutputAmperage());
            }
        }
    }

    public void multiAmperageCannotBeChangedByToolsNbtOrParentFields() throws Exception {
        final MTEHatchMEEnergyMulti input = (MTEHatchMEEnergyMulti) hatch(
            MEEnergySpec.multi(Role.ENERGY, 13, 16777216));
        final MTEHatchMEDynamoMulti output = (MTEHatchMEDynamoMulti) hatch(
            MEEnergySpec.multi(Role.DYNAMO, 13, 16777216));
        assertEquals(1, input.getHatchType());
        assertThrows(IllegalArgumentException.class, () -> input.setAmperes(4));
        assertThrows(IllegalArgumentException.class, () -> output.setAmperes(4));
        final Field amperes = MTEHatchEnergyMulti.class.getDeclaredField("Amperes");
        amperes.setAccessible(true);
        amperes.setInt(input, -1);
        output.Amperes = -1; // Native public field cannot be sealed; our capabilities must ignore it.
        assertEquals(16777216, input.getAmperes());
        assertEquals(16777216, output.getAmperes());
        assertEquals(20971520, input.maxAmperesIn());
        assertEquals(16777216, input.maxWorkingAmperesIn());
        assertEquals(16777216, output.maxAmperesOut());
        assertEquals(
            input.spec()
                .capacity(),
            input.maxEUStore());
        assertEquals(
            output.spec()
                .nativeReserve(),
            output.getMinimumStoredEU());
        final NBTTagCompound forged = new NBTTagCompound();
        forged.setInteger("Amperes", Integer.MAX_VALUE);
        input.loadNBTData(forged);
        output.loadNBTData(forged);
        assertEquals(16777216, amperes.getInt(input));
        assertEquals(16777216, output.Amperes);
        assertEquals(input.spec(), ((IMEEnergyEndpoint) input.newMetaEntity(null)).spec());
        assertEquals(output.spec(), ((IMEEnergyEndpoint) output.newMetaEntity(null)).spec());
    }

    public void realGtAddersAcceptOnlyTheirNativeHatchKinds() {
        final Controller machine = controller();
        final BaseMetaTileEntity standard = attach(hatch(MEEnergySpec.standard(Role.ENERGY, 5)));
        final BaseMetaTileEntity multi = attach(hatch(MEEnergySpec.multi(Role.ENERGY, 5, 4)));
        assertTrue(machine.addEnergyInputToMachineList(standard, 0));
        assertFalse(machine.addEnergyInputToMachineList(multi, 0));
        assertFalse(machine.addMultiAmpEnergyInputToMachineList(standard, 0));
        assertTrue(machine.addMultiAmpEnergyInputToMachineList(multi, 0));
        assertTrue(machine.addExoticEnergyInputToMachineList(multi, 0));
        assertEquals(1, ((MTEHatchEnergyMulti) multi.getMetaTileEntity()).getHatchType());
        assertTrue(machine.addDynamoToMachineList(attach(hatch(MEEnergySpec.standard(Role.DYNAMO, 5))), 0));
        final BaseMetaTileEntity four = attach(hatch(MEEnergySpec.multi(Role.DYNAMO, 5, 4)));
        final BaseMetaTileEntity sixteen = attach(hatch(MEEnergySpec.multi(Role.DYNAMO, 5, 16)));
        assertTrue(machine.addDynamoToMachineList(four, 0));
        assertFalse(machine.addDynamoToMachineList(sixteen, 0));
        assertTrue(machine.addExoticDynamoToMachineList(sixteen, 0));
        assertFalse(machine.addLaserSourceToMachineList(sixteen, 0));
    }

    public void nativeConsumersDrainOfflineStockAndNonPacketAmounts() {
        final IMEEnergyEndpoint ordinary = hatch(MEEnergySpec.standard(Role.ENERGY, 5));
        final BaseMetaTileEntity base = attach(ordinary);
        final Controller controller = controller();
        assertTrue(controller.addEnergyInputToMachineList(base, 0));
        ordinary.machine()
            .setEUVar(1000);
        ordinary.machine()
            .onUnload();
        assertFalse(ordinary.isEndpointValid());
        assertTrue(controller.drainEnergyInput(333));
        assertEquals(667, ordinary.readEU());
        assertFalse(controller.drainEnergyInput(668));
        assertEquals(0, ordinary.readEU()); // Preserve the real GT partial-drain-on-failure behavior.
        final IMEEnergyEndpoint multi = hatch(MEEnergySpec.multi(Role.ENERGY, 5, 256));
        attach(multi);
        multi.machine()
            .setEUVar(1001);
        multi.machine()
            .onRemoval();
        assertTrue(ExoticEnergyInputHelper.drainEnergy(7, Collections.singletonList((MTEHatch) multi.machine())));
        assertEquals(994, multi.readEU());
    }

    public void nativeGeneratorsWriteTheSameDynamoStock() {
        final IMEEnergyEndpoint ordinary = hatch(MEEnergySpec.standard(Role.DYNAMO, 5));
        final BaseMetaTileEntity base = attach(ordinary);
        final Controller controller = controller();
        assertTrue(controller.addDynamoToMachineList(base, 0));
        assertTrue(controller.addEnergyOutput(77));
        assertEquals(77, ordinary.readEU());
        ordinary.machine()
            .onUnload();
        assertTrue(base.increaseStoredEnergyUnits(11, false));
        assertEquals(88, ordinary.readEU());
        final IMEEnergyEndpoint multi = hatch(MEEnergySpec.multi(Role.DYNAMO, 8, 256));
        final BaseMetaTileEntity multiBase = attach(multi);
        assertTrue(multiBase.increaseStoredEnergyUnits(123, false));
        multi.machine()
            .setEUVar(multi.readEU() + 456); // LSC/PSS-style direct controller write.
        assertEquals(579, multi.readEU());
    }

    public void nativeStockBridgeRetainsOverCapacityAndMarksDirty() {
        final IMEEnergyEndpoint endpoint = hatch(MEEnergySpec.standard(Role.DYNAMO, 5));
        final DirtyBase base = new DirtyBase();
        endpoint.machine()
            .setBaseMetaTileEntity(base);
        endpoint.machine()
            .onFirstTick(base);
        endpoint.writeEU(
            endpoint.spec()
                .capacity() + 123);
        assertEquals(1, base.dirties);
        assertEquals(
            endpoint.spec()
                .capacity() + 123,
            endpoint.readEU());
        assertEquals(
            endpoint.spec()
                .capacity(),
            base.getStoredEU());
        endpoint.writeEU(endpoint.readEU() - 7);
        assertEquals(2, base.dirties);
        assertThrows(IllegalArgumentException.class, () -> endpoint.writeEU(-1));
        endpoint.machine()
            .onRemoval();
        assertThrows(IllegalStateException.class, () -> endpoint.writeEU(0));
        assertEquals(
            endpoint.spec()
                .capacity() + 116,
            endpoint.readEU());
    }

    public void worldNbtRoundTripsEverySpecAndDoesNotDuplicateEu() {
        final IMetaTileEntity previous = GregTechAPI.METATILEENTITIES[TEST_ID];
        try {
            for (final MEEnergyCatalog.Entry entry : MEEnergyCatalog.entries()) {
                GregTechAPI.METATILEENTITIES[TEST_ID] = hatch(entry.spec()).machine();
                final BaseMetaTileEntity base = new BaseMetaTileEntity();
                base.setInitialValuesAsNBT(null, TEST_ID);
                final IMEEnergyEndpoint endpoint = (IMEEnergyEndpoint) base.getMetaTileEntity();
                endpoint.machine()
                    .setEUVar(
                        entry.spec()
                            .capacity() + 17);
                if (entry.spec()
                    .role() == Role.DYNAMO) credit(endpoint, 18);
                final NBTTagCompound saved = new NBTTagCompound();
                base.writeToNBT(saved);
                assertEquals(
                    entry.spec()
                        .capacity() + 17,
                    saved.getLong("mStoredEnergy"));
                assertFalse(
                    saved.getCompoundTag(MEEnergyNbt.DATA)
                        .hasKey("mStoredEnergy"));
                final BaseMetaTileEntity reloaded = new BaseMetaTileEntity();
                reloaded.readFromNBT(saved);
                final IMEEnergyEndpoint restored = (IMEEnergyEndpoint) reloaded.getMetaTileEntity();
                assertEquals(entry.spec(), restored.spec());
                assertEquals(
                    entry.spec()
                        .capacity() + 17,
                    restored.readEU());
                restored.machine()
                    .loadNBTData(saved);
                assertEquals(
                    entry.spec()
                        .capacity() + 17,
                    restored.readEU());
                assertTrue(
                    restored.energyState()
                        .diagnostic()
                        .isEmpty());
                if (entry.spec()
                    .role() == Role.DYNAMO) {
                    assertEquals(
                        18,
                        restored.energyState()
                            .lossAccount()
                            .credit());
                    assertEquals(
                        0,
                        restored.energyState()
                            .rateBudget()
                            .tokens(10000));
                    assertFalse(
                        restored.energyState()
                            .rateBudget()
                            .isActive());
                }
            }
        } finally {
            GregTechAPI.METATILEENTITIES[TEST_ID] = previous;
        }
    }

    public void everyRoundingCreditSurvivesNativeWorldReload() {
        for (int credit = 0; credit < 19; credit++) {
            final IMEEnergyEndpoint source = hatch(MEEnergySpec.multi(Role.DYNAMO, 5, 4));
            attach(source);
            credit(source, credit);
            source.machine()
                .setEUVar(1000);
            source.energyState()
                .rateBudget()
                .setActive(true, 10);
            assertTrue(
                source.energyState()
                    .rateBudget()
                    .tokens(20) > 0);
            final NBTTagCompound tag = new NBTTagCompound();
            source.machine()
                .saveNBTData(tag);
            source.machine()
                .loadNBTData(tag);
            assertEquals(1000, source.readEU());
            assertEquals(
                credit,
                source.energyState()
                    .lossAccount()
                    .credit());
            assertEquals(
                0,
                source.energyState()
                    .rateBudget()
                    .tokens(10000));
            assertTrue(
                source.energyState()
                    .isStopped());
        }
    }

    public void unloadAndRemovalStopWorkWithoutErasingWorldStock() {
        final IMEEnergyEndpoint endpoint = hatch(MEEnergySpec.multi(Role.DYNAMO, 8, 256));
        final BaseMetaTileEntity base = attach(endpoint);
        endpoint.machine()
            .setEUVar(98765);
        credit(endpoint, 13);
        final long revision = endpoint.energyState()
            .revision();
        base.onUnload();
        assertFalse(endpoint.isEndpointValid());
        assertTrue(
            endpoint.energyState()
                .revision() > revision);
        final long stopped = endpoint.energyState()
            .revision();
        endpoint.machine()
            .onRemoval();
        endpoint.machine()
            .onBlockDestroyed();
        assertEquals(
            stopped,
            endpoint.energyState()
                .revision());
        assertEquals(98765, endpoint.readEU());
        assertEquals(
            13,
            endpoint.energyState()
                .lossAccount()
                .credit());
        final NBTTagCompound tag = new NBTTagCompound();
        endpoint.machine()
            .saveNBTData(tag);
        endpoint.machine()
            .loadNBTData(tag);
        base.isDead = false; // BaseTileEntity's real tick clears this after its onUnload marks it dead.
        endpoint.machine()
            .onFirstTick(base);
        assertTrue(endpoint.isEndpointValid());
        assertEquals(98765, endpoint.readEU());
        assertEquals(
            13,
            endpoint.energyState()
                .lossAccount()
                .credit());
    }

    public void nativeDropsAndReplacementNeverCarryEuOrCredit() {
        final IMEEnergyEndpoint endpoint = hatch(MEEnergySpec.multi(Role.DYNAMO, 5, 64));
        final BaseMetaTileEntity base = attach(endpoint);
        endpoint.machine()
            .setEUVar(5678);
        credit(endpoint, 17);
        final ItemStack drop = base.getDrops()
            .get(0);
        final NBTTagCompound tag = drop.hasTagCompound() ? drop.getTagCompound() : new NBTTagCompound();
        assertFalse(tag.hasKey("mStoredEnergy"));
        assertFalse(tag.hasKey(MEEnergyNbt.DATA));
        final IMetaTileEntity previous = GregTechAPI.METATILEENTITIES[TEST_ID];
        try {
            GregTechAPI.METATILEENTITIES[TEST_ID] = endpoint.machine();
            final BaseMetaTileEntity replacement = new BaseMetaTileEntity();
            replacement.setInitialValuesAsNBT(tag, TEST_ID); // Same native placement initialization as ItemMachines.
            final IMEEnergyEndpoint fresh = (IMEEnergyEndpoint) replacement.getMetaTileEntity();
            assertEquals(0, fresh.readEU());
            assertEquals(
                0,
                fresh.energyState()
                    .lossAccount()
                    .credit());
            assertEquals(
                0,
                fresh.energyState()
                    .rateBudget()
                    .tokens(10000));
        } finally {
            GregTechAPI.METATILEENTITIES[TEST_ID] = previous;
        }
        final NBTTagCompound reused = new NBTTagCompound();
        endpoint.machine()
            .saveNBTData(reused);
        reused.setLong("mStoredEnergy", 5678);
        reused.setByte("mColor", (byte) 7);
        endpoint.machine()
            .setItemNBT(reused);
        assertFalse(reused.hasKey(MEEnergyNbt.DATA));
        assertFalse(reused.hasKey("mStoredEnergy"));
        assertEquals(7, reused.getByte("mColor"));
    }

    public void malformedMetadataStaysIsolatedAcrossSaves() {
        final IMEEnergyEndpoint endpoint = hatch(MEEnergySpec.standard(Role.DYNAMO, 5));
        attach(endpoint);
        endpoint.machine()
            .setEUVar(1000);
        final NBTTagCompound valid = new NBTTagCompound();
        endpoint.machine()
            .saveNBTData(valid);
        for (int scenario = 0; scenario < 9; scenario++) {
            final NBTTagCompound broken = (NBTTagCompound) valid.copy();
            final NBTTagCompound data = broken.getCompoundTag(MEEnergyNbt.DATA);
            switch (scenario) {
                case 0:
                    broken.setTag(MEEnergyNbt.DATA, new NBTTagString("bad"));
                    break;
                case 1:
                    data.setInteger("schema", 2);
                    break;
                case 2:
                    data.setLong("schema", 1);
                    break;
                case 3:
                    data.setString("spec", "other");
                    break;
                case 4:
                    data.setInteger("credit", -1);
                    break;
                case 5:
                    data.setInteger("credit", 19);
                    break;
                case 6:
                    data.setLong("credit", 1);
                    break;
                case 7:
                    data.removeTag("credit");
                    break;
                case 8:
                    data.removeTag("spec");
                    break;
                default:
                    throw new AssertionError();
            }
            endpoint.machine()
                .loadNBTData(broken);
            endpoint.energyState()
                .start();
            assertFalse(endpoint.isEndpointValid());
            assertFalse(
                endpoint.energyState()
                    .diagnostic()
                    .isEmpty());
            assertThrows(IllegalStateException.class, () -> endpoint.writeEU(0));
            assertEquals(1000, endpoint.readEU());
            final NBTBase expected = broken.getTag(MEEnergyNbt.DATA)
                .copy();
            if (broken.hasKey(MEEnergyNbt.DATA, 10)) broken.getCompoundTag(MEEnergyNbt.DATA)
                .setInteger("schema", 99); // No alias to retained evidence.
            final NBTTagCompound saved = new NBTTagCompound();
            endpoint.machine()
                .saveNBTData(saved);
            assertEquals(expected, saved.getTag(MEEnergyNbt.DATA));
            endpoint.machine()
                .loadNBTData(saved);
            assertFalse(
                endpoint.energyState()
                    .diagnostic()
                    .isEmpty());
        }
        endpoint.machine()
            .loadNBTData(valid);
        endpoint.energyState()
            .start();
        assertTrue(endpoint.isEndpointValid());
        assertEquals(1000, endpoint.readEU());
        final IMEEnergyEndpoint energy = hatch(MEEnergySpec.standard(Role.ENERGY, 5));
        final NBTTagCompound wrongRole = new NBTTagCompound();
        energy.machine()
            .saveNBTData(wrongRole);
        wrongRole.getCompoundTag(MEEnergyNbt.DATA)
            .setInteger("credit", 0);
        energy.machine()
            .loadNBTData(wrongRole);
        assertEquals(
            "UNEXPECTED_ME_ENERGY_CREDIT",
            energy.energyState()
                .diagnostic());
    }

    public void businessPaintAndReplacementInvalidateRuntimeSnapshots() {
        final IMEEnergyEndpoint endpoint = hatch(MEEnergySpec.standard(Role.ENERGY, 5));
        final BaseMetaTileEntity base = attach(endpoint);
        base.setWorldObj(new GTDummyWorld()); // Native paint event queue requires a non-null World key.
        assertEquals(0, endpoint.businessColor());
        final long previous = endpoint.energyState()
            .revision();
        for (byte color = 0; color < 16; color++) {
            base.setColorization(color);
            assertEquals(color + 1, endpoint.businessColor());
        }
        assertTrue(
            endpoint.energyState()
                .revision() > previous);
        base.setWorldObj(null); // GTDummyWorld has no chunks; stock writes below use native null-world markDirty.
        endpoint.machine()
            .setEUVar(23);
        final IMEEnergyEndpoint replacement = hatch(endpoint.spec());
        replacement.machine()
            .setBaseMetaTileEntity(base);
        assertFalse(endpoint.isEndpointValid());
        assertThrows(IllegalStateException.class, endpoint::readEU);
        assertThrows(IllegalStateException.class, () -> endpoint.writeEU(0));
    }

    @SuppressWarnings("unchecked")
    private static void loadInspectionLanguage(final String locale) throws Exception {
        final java.util.Properties language = new java.util.Properties();
        try (java.io.InputStreamReader reader = new java.io.InputStreamReader(
            MEEnergyHatchFixture.class
                .getResourceAsStream("/assets/gtnhmelogisticsenhancement/lang/" + locale + ".lang"),
            java.nio.charset.StandardCharsets.UTF_8)) {
            language.load(reader);
        }
        final Class<?> translations = net.minecraft.util.StringTranslate.class;
        final Field singleton = translations.getDeclaredField("instance");
        singleton.setAccessible(true);
        final Field dictionary = translations.getDeclaredField("languageList");
        dictionary.setAccessible(true);
        final java.util.Map<String, String> entries = (java.util.Map<String, String>) dictionary
            .get(singleton.get(null));
        for (final String key : language.stringPropertyNames()) entries.put(key, language.getProperty(key));
    }

    public void wailaSnapshotsReadNativeOfflineStockAndAllColorGroups() throws Exception {
        loadInspectionLanguage("en_US");
        final mcp.mobius.waila.api.IWailaConfigHandler config = (mcp.mobius.waila.api.IWailaConfigHandler) java.lang.reflect.Proxy
            .newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] { mcp.mobius.waila.api.IWailaConfigHandler.class },
                (proxy, method, args) -> method.getReturnType() == boolean.class ? false : null);
        for (final MEEnergyCatalog.Entry entry : MEEnergyCatalog.entries()) {
            final IMEEnergyEndpoint server = hatch(entry.spec()), client = hatch(entry.spec());
            final BaseMetaTileEntity base = attach(server), clientBase = attach(client);
            final long stored = entry.spec()
                .capacity() + 37;
            server.machine()
                .setEUVar(stored);
            for (byte paint = -1; paint < 16; paint++) {
                base.setWorldObj(new GTDummyWorld());
                base.setColorization(paint);
                base.setWorldObj(null);
                final NBTTagCompound packet = new NBTTagCompound();
                base.getWailaNBTData(null, base, packet, null, 0, 0, 0);
                final NBTTagCompound snapshot = packet.getCompoundTag(MEEnergyWaila.DATA_KEY);
                assertEquals(stored, snapshot.getLong("stored"));
                assertEquals(
                    entry.spec()
                        .capacity(),
                    snapshot.getLong("capacity"));
                assertEquals(paint + 1, snapshot.getInteger("group"));
                assertTrue(packet.hasKey("isPowered"));
                assertFalse(packet.getBoolean("isActive"));
                final mcp.mobius.waila.api.IWailaDataAccessor accessor = (mcp.mobius.waila.api.IWailaDataAccessor) java.lang.reflect.Proxy
                    .newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] { mcp.mobius.waila.api.IWailaDataAccessor.class },
                        (proxy, method, args) -> method.getName()
                            .equals("getNBTData") ? packet : null);
                final java.util.List<String> tooltip = new java.util.ArrayList<>();
                clientBase.getWailaBody(null, tooltip, accessor, config);
                assertTrue(
                    tooltip.toString(),
                    tooltip.contains(
                        MEEnergyPresentation.text(
                            "waila.buffer",
                            String.format(java.util.Locale.ROOT, "%,d", stored),
                            String.format(
                                java.util.Locale.ROOT,
                                "%,d",
                                entry.spec()
                                    .capacity()))));
                assertTrue(
                    tooltip
                        .contains(MEEnergyPresentation.text("waila.group", MEEnergyPresentation.colorName(paint + 1))));
                assertEquals("The client has no synchronized native stock", 0, client.readEU());
                assertEquals("Inspection must not transfer or erase EU", stored, server.readEU());
            }
        }
        final NBTTagCompound packet = new NBTTagCompound(), snapshot = new NBTTagCompound();
        final java.util.List<String> tooltip = new java.util.ArrayList<>();
        MEEnergyWaila.body(packet, tooltip);
        assertTrue(tooltip.isEmpty());
        packet.setTag(MEEnergyWaila.DATA_KEY, snapshot);
        MEEnergyWaila.body(packet, tooltip);
        assertTrue(tooltip.isEmpty());
        snapshot.setLong("stored", Long.MAX_VALUE);
        snapshot.setLong("capacity", Long.MAX_VALUE);
        snapshot.setInteger("group", 0);
        MEEnergyWaila.body(packet, tooltip);
        assertTrue(
            tooltip.get(0),
            tooltip.get(0)
                .contains("9,223,372,036,854,775,807"));
        loadInspectionLanguage("zh_CN");
        tooltip.clear();
        snapshot.setInteger("group", 2);
        MEEnergyWaila.body(packet, tooltip);
        assertTrue(
            tooltip.get(0)
                .startsWith("储存电量："));
        assertEquals("颜色分组：红色", tooltip.get(1));
        loadInspectionLanguage("en_US");
    }

    public void paintRemainsVisibleOnEveryFaceWithControllerCasing() throws Exception {
        final ITexture casing = gregtech.api.enums.Textures.BlockIcons.MACHINE_CASINGS[5][0];
        gregtech.api.enums.Textures.BlockIcons.casingTexturePages[1][127] = casing;
        final java.util.Set<String> palette = new java.util.HashSet<>();
        for (final MEEnergyCatalog.Entry entry : MEEnergyCatalog.entries()) {
            final IMEEnergyEndpoint host = hatch(entry.spec());
            final BaseMetaTileEntity base = attach(host);
            final MTEHatch hatch = (MTEHatch) host.machine();
            for (final boolean formed : new boolean[] { false, true }) {
                hatch.updateTexture(formed ? (1 << 7) + 127 : 0);
                for (final ForgeDirection face : ForgeDirection.VALID_DIRECTIONS) {
                    final boolean front = face == ForgeDirection.NORTH;
                    for (final boolean active : new boolean[] { false, true }) {
                        final ITexture[] neutral = hatch
                            .getTexture(base, face, ForgeDirection.NORTH, -1, active, false);
                        assertEquals(front ? 4 : 1, neutral.length);
                        if (formed) assertSame(casing, neutral[0]);
                        for (int paint = 0; paint < 16; paint++) {
                            final ITexture[] layers = hatch
                                .getTexture(base, face, ForgeDirection.NORTH, paint, active, false);
                            assertEquals(neutral.length + 2, layers.length);
                            if (formed) assertSame(casing, layers[0]);
                            if (front) assertSame(MEEnergyPresentation.port(entry.spec()), layers[2]);
                            for (final ITexture texture : layers) assertNotNull(texture);
                            final short[] tint = ((gregtech.common.render.GTRenderedTexture) layers[layers.length - 1])
                                .getRGBA();
                            assertTrue(
                                java.util.Arrays.equals(
                                    gregtech.api.enums.Dyes.get(paint)
                                        .getRGBA(),
                                    tint));
                            palette.add(java.util.Arrays.toString(tint));
                            final ITexture[] again = hatch
                                .getTexture(base, face, ForgeDirection.NORTH, paint, active, false);
                            assertSame(layers[layers.length - 1], again[again.length - 1]);
                        }
                    }
                }
            }
        }
        assertEquals(16, palette.size());
    }

    public static class DirtyBase extends BaseMetaTileEntity {

        int dirties;

        @Override
        public void markDirty() {
            this.dirties++;
            super.markDirty();
        }
    }

    private static class Controller extends MTEMultiBlockBase {

        Controller() {
            super("test.me_energy_controller");
        }

        @Override
        public MetaTileEntity newMetaEntity(final IGregTechTileEntity base) {
            return new Controller();
        }

        @Override
        public ITexture[] getTexture(final IGregTechTileEntity base, final ForgeDirection side,
            final ForgeDirection facing, final int color, final boolean active, final boolean redstone) {
            return new ITexture[0];
        }

        @Override
        public ItemStack getMachineCraftingIcon() {
            return null;
        }

        @Override
        public String[] getDescription() {
            return new String[0];
        }
    }
}
