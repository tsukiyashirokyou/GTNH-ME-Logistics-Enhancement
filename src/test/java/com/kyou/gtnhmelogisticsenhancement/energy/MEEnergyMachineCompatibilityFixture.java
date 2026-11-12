package com.kyou.gtnhmelogisticsenhancement.energy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.Collection;

import net.minecraft.tileentity.TileEntity;

import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.kyou.gtnhmelogisticsenhancement.energy.MEEnergySpec.Family;
import com.kyou.gtnhmelogisticsenhancement.energy.MEEnergySpec.Role;

import gregtech.api.enums.HatchElement;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.common.tileentities.machines.multi.MTELargeCombustionEngine;
import gtPlusPlus.xmod.gregtech.common.tileentities.machines.multi.storage.MTEPowerSubStation;
import kekztech.common.tileentities.MTELapotronicSuperCapacitor;

/** Real pinned controller methods. Terrain/structure completion is never claimed by these isolated tests. */
public class MEEnergyMachineCompatibilityFixture extends MEEnergyAENetworkFixture {

    public static void registries() throws Exception {
        MEEnergyAENetworkFixture.registries();
        // Static structure definition needs a non-null casing, but no terrain is certified here.
        kekztech.common.Blocks.lscLapotronicEnergyUnit = net.minecraft.init.Blocks.stone;
    }

    static Field memberField(final Class<?> type, final String name) throws NoSuchFieldException {
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            try {
                final Field result = cursor.getDeclaredField(name);
                result.setAccessible(true);
                return result;
            } catch (NoSuchFieldException absent) { /* Look up the actual inherited controller field. */ }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    static Object call(final Object target, final String name, final Class<?>[] signature, final Object... args)
        throws Exception {
        for (Class<?> cursor = target.getClass(); cursor != null; cursor = cursor.getSuperclass()) {
            try {
                final Method method = cursor.getDeclaredMethod(name, signature);
                method.setAccessible(true);
                try {
                    return method.invoke(target, args);
                } catch (InvocationTargetException failure) {
                    final Throwable cause = failure.getCause();
                    if (cause instanceof Exception) throw (Exception) cause;
                    if (cause instanceof Error) throw (Error) cause;
                    throw new AssertionError(cause);
                }
            } catch (NoSuchMethodException absent) { /* Continue through native parents. */ }
        }
        throw new NoSuchMethodException(
            target.getClass()
                .getName() + "."
                + name);
    }

    static MetaTileEntity attachController(final MetaTileEntity machine) {
        machine.setBaseMetaTileEntity(new ControlledBase());
        return machine;
    }

    static IMEEnergyEndpoint placed(final MEEnergySpec spec) {
        final IMEEnergyEndpoint host = hatch(
            spec.role(),
            spec.tier(),
            spec.family() == Family.STANDARD ? 0 : spec.amperage());
        attach(host);
        return host;
    }

    static int size(final Object controller, final String field) throws Exception {
        return ((Collection<?>) memberField(controller.getClass(), field).get(controller)).size();
    }

    static boolean addLsc(final MTELapotronicSuperCapacitor machine, final IMEEnergyEndpoint host) throws Exception {
        return (Boolean) call(
            machine,
            "addBottomHatches",
            new Class<?>[] { IGregTechTileEntity.class, int.class },
            host.machine()
                .getBaseMetaTileEntity(),
            0);
    }

    public void lscClassifiesEverySpecWithoutLaserIdentity() throws Exception {
        for (final MEEnergyCatalog.Entry entry : MEEnergyCatalog.entries()) {
            final IMEEnergyEndpoint host = placed(entry.spec());
            final MTELapotronicSuperCapacitor lsc = (MTELapotronicSuperCapacitor) attachController(
                new MTELapotronicSuperCapacitor("test.lsc"));
            assertTrue(
                entry.spec()
                    .key(),
                addLsc(lsc, host));
            final boolean energy = entry.spec()
                .role() == Role.ENERGY;
            final String field = entry.spec()
                .family() == Family.STANDARD ? energy ? "mEnergyHatches" : "mDynamoHatches"
                    : energy ? "mEnergyHatchesTT" : "mDynamoHatchesTT";
            assertEquals(1, size(lsc, field));
            assertEquals(0, size(lsc, "mEnergyTunnelsTT"));
            assertEquals(0, size(lsc, "mDynamoTunnelsTT"));
            final long power = energy ? host.machine()
                .maxEUInput()
                * host.machine()
                    .maxAmperesIn()
                : host.machine()
                    .maxEUOutput()
                    * host.machine()
                        .maxAmperesOut();
            assertEquals(power, memberField(lsc.getClass(), energy ? "mMaxEUIn" : "mMaxEUOut").getLong(lsc));
        }
    }

    public void lscRunningTickUsesNativeBalancesAnd125PercentInputOffline() throws Exception {
        for (final MEEnergySpec spec : new MEEnergySpec[] { MEEnergySpec.standard(Role.ENERGY, 5),
            MEEnergySpec.multi(Role.ENERGY, 5, 4), MEEnergySpec.multi(Role.ENERGY, 5, 64),
            MEEnergySpec.multi(Role.ENERGY, 13, 16777216) }) {
            final IMEEnergyEndpoint input = placed(spec);
            final IMEEnergyEndpoint output = placed(
                spec.family() == Family.STANDARD ? MEEnergySpec.standard(Role.DYNAMO, spec.tier())
                    : MEEnergySpec.multi(Role.DYNAMO, spec.tier(), spec.amperage()));
            final MTELapotronicSuperCapacitor lsc = (MTELapotronicSuperCapacitor) attachController(
                new MTELapotronicSuperCapacitor("test.lsc.tick"));
            addLsc(lsc, input);
            addLsc(lsc, output);
            final long drawn = input.machine()
                .maxEUInput()
                * input.machine()
                    .maxAmperesIn();
            final long pushed = output.spec()
                .power(), stored = spec.capacity();
            memberField(lsc.getClass(), "capacity").set(
                lsc,
                BigInteger.valueOf(stored)
                    .multiply(BigInteger.valueOf(4)));
            memberField(lsc.getClass(), "stored").set(lsc, BigInteger.valueOf(stored));
            input.machine()
                .setEUVar(spec.capacity());
            assertFalse(input.isActive());
            assertFalse(output.isActive());
            assertTrue(lsc.onRunningTick(null));
            assertEquals(spec.capacity() - drawn, input.readEU());
            assertEquals(pushed, output.readEU());
            assertEquals(stored + drawn - pushed, lsc.getEUVar());
            assertEquals(
                0,
                output.energyState()
                    .lossAccount()
                    .credit()); // Local controller writes are not ME transfers.
        }
    }

    public void lscDoesNotOverfillHatchesAndHonorsItsOwnRemainingCapacity() throws Exception {
        final MTELapotronicSuperCapacitor lsc = (MTELapotronicSuperCapacitor) attachController(
            new MTELapotronicSuperCapacitor("test.lsc.space"));
        final IMEEnergyEndpoint input = placed(MEEnergySpec.multi(Role.ENERGY, 5, 4)),
            output = placed(MEEnergySpec.multi(Role.DYNAMO, 5, 4));
        addLsc(lsc, input);
        addLsc(lsc, output);
        memberField(lsc.getClass(), "capacity").set(lsc, BigInteger.valueOf(1000));
        memberField(lsc.getClass(), "stored").set(lsc, BigInteger.valueOf(993));
        input.machine()
            .setEUVar(100);
        output.machine()
            .setEUVar(
                output.spec()
                    .capacity() - 1);
        assertTrue(lsc.onRunningTick(null));
        assertEquals(93, input.readEU());
        assertEquals(1000, lsc.getEUVar());
        assertEquals(
            output.spec()
                .capacity() - 1,
            output.readEU()); // Native LSC skips a full-sized output packet.
    }

    public void pssMasterListsClassifyEverySpecOnce() throws Exception {
        for (final MEEnergyCatalog.Entry entry : MEEnergyCatalog.entries()) {
            final IMEEnergyEndpoint host = placed(entry.spec());
            final MTEPowerSubStation pss = (MTEPowerSubStation) attachController(new MTEPowerSubStation("test.pss"));
            assertTrue(
                pss.addPowerSubStationList(
                    host.machine()
                        .getBaseMetaTileEntity(),
                    0));
            assertEquals(
                1,
                size(
                    pss,
                    entry.spec()
                        .role() == Role.ENERGY ? "mAllEnergyHatches" : "mAllDynamoHatches"));
            assertEquals(
                0,
                size(
                    pss,
                    entry.spec()
                        .role() == Role.ENERGY ? "mAllDynamoHatches" : "mAllEnergyHatches"));
        }
    }

    public void pssActualInputOutputAndSpaceChecksPreserveStockOffline() throws Exception {
        for (final int amps : new int[] { 0, 4, 16, 64, 256 }) {
            final MTEPowerSubStation pss = (MTEPowerSubStation) attachController(
                new MTEPowerSubStation("test.pss.tick"));
            final IMEEnergyEndpoint input = hatch(Role.ENERGY, 5, amps), output = hatch(Role.DYNAMO, 5, amps);
            attach(input);
            attach(output);
            assertTrue(
                pss.addPowerSubStationList(
                    input.machine()
                        .getBaseMetaTileEntity(),
                    0));
            assertTrue(
                pss.addPowerSubStationList(
                    output.machine()
                        .getBaseMetaTileEntity(),
                    0));
            memberField(pss.getClass(), "mBatteryCapacity").setLong(pss, Long.MAX_VALUE / 2);
            pss.setEUVar(100000000);
            final long inputPower = input.machine()
                .maxEUInput()
                * input.machine()
                    .maxAmperesIn(),
                outputPower = output.spec()
                    .power();
            input.machine()
                .setEUVar(
                    input.spec()
                        .capacity());
            assertTrue(pss.onRunningTick(null));
            assertEquals(
                input.spec()
                    .capacity() - inputPower,
                input.readEU());
            assertEquals(outputPower, output.readEU());
            assertEquals(100000000 + inputPower - outputPower, pss.getEUVar());
            output.machine()
                .setEUVar(
                    output.spec()
                        .capacity() - 1);
            final long before = pss.getEUVar();
            assertEquals(0L, call(pss, "addEnergyToHatch", new Class<?>[] { MetaTileEntity.class }, output.machine()));
            assertEquals(before, pss.getEUVar());
            memberField(pss.getClass(), "mBatteryCapacity").setLong(pss, before + inputPower - 1);
            final long inputBefore = input.readEU();
            assertEquals(
                0L,
                call(pss, "drawEnergyFromHatch", new Class<?>[] { MetaTileEntity.class }, input.machine()));
            assertEquals(inputBefore, input.readEU());
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void realDynamoStructureElementPreservesFourAmpAdmissionLimit() throws Exception {
        for (final int amps : new int[] { 0, 4, 16, 64, 256 }) {
            final IMEEnergyEndpoint host = hatch(Role.DYNAMO, 5, amps);
            attach(host);
            final MTELargeCombustionEngine engine = (MTELargeCombustionEngine) attachController(
                new MTELargeCombustionEngine("test.engine"));
            final IStructureElement element = HatchElement.Dynamo.newAny(1, 3);
            final TrackingWorld world = new TrackingWorld() {

                @Override
                public TileEntity getTileEntity(final int x, final int y, final int z) {
                    return (TileEntity) host.machine()
                        .getBaseMetaTileEntity();
                }
            };
            ((TileEntity) host.machine()
                .getBaseMetaTileEntity()).setWorldObj(world);
            assertEquals(amps == 0 || amps == 4, element.check(engine, world, 0, 0, 0));
            assertEquals(amps == 0 || amps == 4 ? 1 : 0, size(engine, "mDynamoHatches"));
        }
    }
}
