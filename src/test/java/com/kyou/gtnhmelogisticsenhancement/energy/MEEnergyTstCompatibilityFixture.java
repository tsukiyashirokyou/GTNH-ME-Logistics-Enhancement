package com.kyou.gtnhmelogisticsenhancement.energy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.item.ItemStack;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.kyou.gtnhmelogisticsenhancement.energy.MEEnergySpec.Role;

import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import tectech.thing.metaTileEntity.hatch.MTEHatchDynamoMulti;
import tectech.thing.metaTileEntity.hatch.MTEHatchDynamoTunnel;
import tectech.thing.metaTileEntity.hatch.MTEHatchEnergyTunnel;

/** Original TST dev bytecode; only terrain scanning is overridden when testing post-structure restrictions. */
public class MEEnergyTstCompatibilityFixture extends MEEnergyMachineCompatibilityFixture {

    private static final String PREFIX = "com.Nxer.TwistSpaceTechnology.common.machine.";
    private static int harnessId;
    private static final Map<Object, IMEEnergyEndpoint> TERRAIN_DYNAMOS = new IdentityHashMap<>();

    public static void registries() throws Exception {
        MEEnergyMachineCompatibilityFixture.registries();
        // Original generator constructors read this real GT casing's texture index.
        gregtech.GTMod.proxy = new gregtech.common.GTProxy();
        gregtech.api.GregTechAPI.sBlockCasings1 = new gregtech.common.blocks.BlockCasings1();
        gregtech.api.GregTechAPI.sBlockCasings2 = new gregtech.common.blocks.BlockCasings2();
    }

    public static void end() {
        TERRAIN_DYNAMOS.clear();
        MEEnergyAENetworkFixture.end();
    }

    private static MetaTileEntity machine(final String type) throws Exception {
        return attachController(
            (MetaTileEntity) Class.forName(PREFIX + type)
                .getConstructor(String.class)
                .newInstance("test.tst.energy"));
    }

    /** Preserve every real checkMachine instruction; replace only checkPiece's terrain scan. */
    private static MetaTileEntity terrainHarness(final String type) throws Exception {
        final String parent = (PREFIX + type).replace('.', '/');
        final String name = "com/kyou/gtnhmelogisticsenhancement/energy/TstTerrainHarness" + harnessId++;
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, parent, null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitLdcInsn("test.tst.terrain");
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, parent, "<init>", "(Ljava/lang/String;)V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(2, 1);
        method.visitEnd();
        method = writer.visitMethod(
            Opcodes.ACC_PUBLIC,
            "getStructureDefinition",
            "()Lcom/gtnewhorizon/structurelib/structure/IStructureDefinition;",
            null,
            null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "com/kyou/gtnhmelogisticsenhancement/energy/MEEnergyTstCompatibilityFixture",
            "terrainDefinition",
            "(Ljava/lang/Object;)Lcom/gtnewhorizon/structurelib/structure/IStructureDefinition;",
            false);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
        writer.visitEnd();
        return attachController(
            (MetaTileEntity) new HarnessLoader().define(name.replace('/', '.'), writer.toByteArray())
                .getConstructor()
                .newInstance());
    }

    /** Final native checkPiece/StructureChecker still run; this boundary supplies no terrain elements. */
    public static IStructureDefinition<?> terrainDefinition(final Object machine) {
        return (IStructureDefinition<?>) Proxy.newProxyInstance(
            IStructureDefinition.class.getClassLoader(),
            new Class<?>[] { IStructureDefinition.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("iterate")) {
                    final String type = machine.getClass()
                        .getSuperclass()
                        .getSimpleName();
                    if (type.equals("TST_ManufacturingCenter"))
                        memberField(machine.getClass(), "casingCount").setInt(machine, 100);
                    if (type.equals("TST_IndustrialMagnetarSeparator"))
                        memberField(machine.getClass(), "mCasing").setInt(machine, 100);
                    if (type.equals("TST_IndistinctTentacle")) {
                        memberField(machine.getClass(), "glassTier").setInt(machine, 5);
                        memberField(machine.getClass(), "tierComponentCasing").setInt(machine, 0);
                    }
                    if (type.equals("GT_TileEntity_MagneticDrivePressureFormer"))
                        memberField(machine.getClass(), "glassTier").setInt(machine, 5);
                    if (type.equals("TST_UniversalGenerator") || type.equals("TST_SteamBasicGenerator")) {
                        memberField(machine.getClass(), "mCasing").setInt(machine, 100);
                        if (size(machine, "mDynamoHatches") == 0 && TERRAIN_DYNAMOS.containsKey(machine))
                            add((MetaTileEntity) machine, "addDynamoToMachineList", TERRAIN_DYNAMOS.get(machine));
                    }
                    return null;
                }
                throw new AssertionError("Unexpected terrain API: " + method);
            });
    }

    private static final class HarnessLoader extends ClassLoader {

        HarnessLoader() {
            super(MEEnergyTstCompatibilityFixture.class.getClassLoader());
        }

        Class<?> define(final String name, final byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static boolean add(final MetaTileEntity machine, final String adder, final IMEEnergyEndpoint host)
        throws Exception {
        return (Boolean) call(
            machine,
            adder,
            new Class<?>[] { IGregTechTileEntity.class, int.class },
            host.machine()
                .getBaseMetaTileEntity(),
            0);
    }

    private static List<Object> check(final MetaTileEntity machine) throws Exception {
        final List<Object> errors = new ArrayList<>();
        call(
            machine,
            "checkMachine",
            new Class<?>[] { IGregTechTileEntity.class, ItemStack.class, List.class },
            machine.getBaseMetaTileEntity(),
            null,
            errors);
        return errors;
    }

    private static boolean addEnergy(final MetaTileEntity machine, final IMEEnergyEndpoint host) throws Exception {
        return add(
            machine,
            host.spec()
                .family() == MEEnergySpec.Family.STANDARD ? "addEnergyInputToMachineList"
                    : "addExoticEnergyInputToMachineList",
            host);
    }

    public void acalRunsOriginalPostStructureAmpAndMixingChecks() throws Exception {
        for (final int amps : new int[] { 0, 4, 16, 64, 256 }) {
            final MetaTileEntity acal = terrainHarness("TST_AdvCircuitAssemblyLine");
            final IMEEnergyEndpoint input = placed(
                amps == 0 ? MEEnergySpec.standard(Role.ENERGY, 5) : MEEnergySpec.multi(Role.ENERGY, 5, amps));
            assertTrue(add(acal, "addToMachineList", input));
            final List<Object> errors = check(acal);
            assertEquals("ACAL " + amps, amps == 256, !errors.isEmpty());
        }
        final MetaTileEntity mixed = terrainHarness("TST_AdvCircuitAssemblyLine");
        add(mixed, "addToMachineList", placed(MEEnergySpec.standard(Role.ENERGY, 5)));
        add(mixed, "addToMachineList", placed(MEEnergySpec.multi(Role.ENERGY, 5, 4)));
        assertFalse(check(mixed).isEmpty());
        final MetaTileEntity multiple = terrainHarness("TST_AdvCircuitAssemblyLine");
        add(multiple, "addToMachineList", placed(MEEnergySpec.multi(Role.ENERGY, 5, 4)));
        add(multiple, "addToMachineList", placed(MEEnergySpec.multi(Role.ENERGY, 5, 4)));
        assertFalse(check(multiple).isEmpty());
    }

    public void generatorBaseClassifiesEveryDynamoSpecAndWritesRealCapacity() throws Exception {
        for (final MEEnergyCatalog.Entry entry : MEEnergyCatalog.entries()) {
            if (entry.spec()
                .role() != Role.DYNAMO) continue;
            final MetaTileEntity generator = machine("GeneratorMultis.GTCM_LightningSpire");
            final IMEEnergyEndpoint output = placed(entry.spec());
            assertTrue(add(generator, "addDynamoToMachineList", output));
            assertEquals(
                1,
                size(
                    generator,
                    entry.spec()
                        .family() == MEEnergySpec.Family.STANDARD ? "mDynamoHatches" : "mExoticDynamoHatches"));
            output.machine()
                .setEUVar(
                    output.spec()
                        .capacity() - 17);
            assertTrue((Boolean) call(generator, "addEnergyOutput", new Class<?>[] { long.class }, 17L));
            assertEquals(
                output.spec()
                    .capacity(),
                output.readEU());
            assertFalse((Boolean) call(generator, "addEnergyOutput", new Class<?>[] { long.class }, 1L));
            assertEquals(
                0,
                output.energyState()
                    .lossAccount()
                    .credit());
            assertEquals(
                0,
                output.machine()
                    .getBaseMetaTileEntity()
                    .getOutputVoltage());
        }
    }

    public void generatorNativeFillCannotBypassMeGrossBudget() throws Exception {
        final MetaTileEntity generator = machine("GeneratorMultis.GTCM_LightningSpire");
        final Frame frame = new Frame();
        final IMEEnergyEndpoint source = hatch(Role.DYNAMO, 5, 64), target = hatch(Role.ENERGY, 5, 64);
        frame.add(source, 0);
        frame.add(target, 0);
        frame.cache.onUpdateTick();
        assertTrue(add(generator, "addDynamoToMachineList", source));
        assertTrue(
            (Boolean) call(
                generator,
                "addEnergyOutput",
                new Class<?>[] { long.class },
                source.spec()
                    .capacity()));
        assertEquals(
            0,
            frame.cache.transfers()
                .commit(
                    frame.cache,
                    frame.cache.epoch(5),
                    frame.cache.first(Role.DYNAMO, 5, 0),
                    frame.cache.first(Role.ENERGY, 5, 0),
                    19,
                    MEEnergyTickClock.now()));
        advance(1);
        final long net = frame.cache.transfers()
            .commit(
                frame.cache,
                frame.cache.epoch(5),
                frame.cache.first(Role.DYNAMO, 5, 0),
                frame.cache.first(Role.ENERGY, 5, 0),
                Long.MAX_VALUE,
                MEEnergyTickClock.now());
        assertTrue(
            net > 0 && net <= source.spec()
                .power());
    }

    public void cleanRoomActuallyDrainsEnergyAndFillsOrdinaryAndExoticDynamos() throws Exception {
        for (final int amps : new int[] { 0, 4, 16, 64, 256 }) {
            final MetaTileEntity room = machine("TST_CleanRoom");
            final IMEEnergyEndpoint input = placed(
                amps == 0 ? MEEnergySpec.standard(Role.ENERGY, 5) : MEEnergySpec.multi(Role.ENERGY, 5, amps));
            final IMEEnergyEndpoint output = placed(
                amps == 0 ? MEEnergySpec.standard(Role.DYNAMO, 5) : MEEnergySpec.multi(Role.DYNAMO, 5, amps));
            assertTrue(add(room, "addEnergyHatchOrExoticEnergyHatchToMachineList", input));
            assertTrue(add(room, amps <= 4 ? "addDynamoToMachineList" : "addExoticDynamoToMachineList", output));
            input.machine()
                .setEUVar(
                    input.spec()
                        .capacity());
            final long power = output.spec()
                .power(),
                cost = power + Math.max(
                    power / 24576,
                    output.spec()
                        .amperage());
            assertTrue((Boolean) call(room, "onRunningTick", new Class<?>[] { ItemStack.class }, (Object) null));
            assertEquals(power, output.readEU());
            assertEquals(
                input.spec()
                    .capacity() - cost,
                input.readEU());
            output.machine()
                .setEUVar(
                    output.spec()
                        .capacity());
            final long before = input.readEU();
            call(room, "onRunningTick", new Class<?>[] { ItemStack.class }, (Object) null);
            assertEquals(before, input.readEU());
        }
    }

    private static Object structureError(final String name) throws Exception {
        return Class.forName("com.Nxer.TwistSpaceTechnology.common.misc.StructureErrorDefs.SimpleStructureErrors")
            .getField(name)
            .get(null);
    }

    public void ordinaryAndMultiConsumersUseInheritedNativeDrainOffline() throws Exception {
        for (final int amps : new int[] { 0, 4, 16, 64, 256 }) {
            final MetaTileEntity consumer = machine("TST_AdvCircuitAssemblyLine");
            final IMEEnergyEndpoint input = placed(
                amps == 0 ? MEEnergySpec.standard(Role.ENERGY, 5) : MEEnergySpec.multi(Role.ENERGY, 5, amps));
            assertTrue(add(consumer, "addEnergyHatchOrExoticEnergyHatchToMachineList", input));
            input.machine()
                .setEUVar(
                    input.spec()
                        .capacity());
            assertTrue((Boolean) call(consumer, "drainEnergyInput", new Class<?>[] { long.class }, 100L));
            assertEquals(
                input.spec()
                    .capacity() - 100,
                input.readEU());
            assertFalse(input.isActive());
        }
    }

    public void manufacturingAndMagnetarStillRejectEveryLowAmpExoticInput() throws Exception {
        for (final String type : new String[] { "TST_ManufacturingCenter", "TST_IndustrialMagnetarSeparator" }) {
            for (final int amps : new int[] { 0, 4, 16, 64 }) {
                final MetaTileEntity consumer = terrainHarness(type);
                final IMEEnergyEndpoint input = placed(
                    amps == 0 ? MEEnergySpec.standard(Role.ENERGY, 5) : MEEnergySpec.multi(Role.ENERGY, 5, amps));
                assertTrue(addEnergy(consumer, input));
                assertEquals(
                    type + " " + amps,
                    amps != 0,
                    check(consumer).contains(structureError("multi_Amp_hatch_incompatible")));
            }
        }
    }

    public void meteorMinerOriginalEnergyCheckRejectsOnlyMixedOrEmptyLists() throws Exception {
        for (final int mode : new int[] { 0, 1, 2, 3 }) {
            final MetaTileEntity miner = machine("TST_LaserMeteorMiner");
            if (mode == 0 || mode == 2) assertTrue(addEnergy(miner, placed(MEEnergySpec.standard(Role.ENERGY, 5))));
            if (mode == 1 || mode == 2) assertTrue(addEnergy(miner, placed(MEEnergySpec.multi(Role.ENERGY, 5, 4))));
            final List<Object> errors = new ArrayList<>();
            call(miner, "checkEnergyHatches", new Class<?>[] { List.class }, errors);
            assertEquals(mode >= 2, !errors.isEmpty());
        }
    }

    public void realGlassChecksDistinguishMeMultiFromNativeLaser() throws Exception {
        for (final String type : new String[] { "TST_IndistinctTentacle",
            "GT_TileEntity_MagneticDrivePressureFormer" }) {
            final MetaTileEntity consumer = terrainHarness(type);
            assertTrue(
                add(
                    consumer,
                    "addEnergyHatchOrExoticEnergyHatchToMachineList",
                    placed(MEEnergySpec.multi(Role.ENERGY, 5, 256))));
            assertTrue(type, check(consumer).isEmpty());
            final MetaTileEntity laserCase = terrainHarness(type);
            final MTEHatchEnergyTunnel laser = new MTEHatchEnergyTunnel(
                "test.native.laser",
                5,
                256,
                new String[0],
                new ITexture[0][][]);
            attachController(laser);
            assertTrue(
                (Boolean) call(
                    laserCase,
                    "addExoticEnergyInputToMachineList",
                    new Class<?>[] { IGregTechTileEntity.class, int.class },
                    laser.getBaseMetaTileEntity(),
                    0));
            assertFalse(type, check(laserCase).isEmpty());
        }
    }

    public void universalGeneratorRejectsIvByItsOwnTierLimit() throws Exception {
        for (final int amps : new int[] { 0, 4 }) {
            final MetaTileEntity universal = terrainHarness("GeneratorMultis.TST_UniversalGenerator");
            final IMEEnergyEndpoint output = placed(
                amps == 0 ? MEEnergySpec.standard(Role.DYNAMO, 5) : MEEnergySpec.multi(Role.DYNAMO, 5, amps));
            TERRAIN_DYNAMOS.put(universal, output);
            assertTrue(check(universal).contains(structureError("hatch_tier_incompatible")));
            assertFalse(
                (Boolean) call(universal, "setDynamoTier", new Class<?>[] { int.class, boolean.class }, 3, false));
            assertEquals(amps == 0 ? 1 : 4, ((Long) call(universal, "getDynamoAmperage", new Class<?>[0])).longValue());
        }
    }

    public void steamAndUniversalKeepStandardDynamoAdmission() throws Exception {
        for (final String type : new String[] { "GeneratorMultis.TST_SteamBasicGenerator",
            "GeneratorMultis.TST_UniversalGenerator" }) for (final int amps : new int[] { 0, 4, 16, 64 }) {
                final MetaTileEntity generator = machine(type);
                final IMEEnergyEndpoint output = placed(
                    amps == 0 ? MEEnergySpec.standard(Role.DYNAMO, 5) : MEEnergySpec.multi(Role.DYNAMO, 5, amps));
                assertEquals(type + " " + amps, amps <= 4, add(generator, "addDynamoToMachineList", output));
            }
    }

    public void legacyGeneratorActuallyWritesOfflineMeDynamos() throws Exception {
        for (final int amps : new int[] { 0, 4 }) {
            final MetaTileEntity generator = machine("GeneratorMultis.TST_SteamBasicGenerator");
            final IMEEnergyEndpoint output = placed(
                amps == 0 ? MEEnergySpec.standard(Role.DYNAMO, 5) : MEEnergySpec.multi(Role.DYNAMO, 5, amps));
            assertTrue(add(generator, "addDynamoToMachineList", output));
            assertTrue(
                (Boolean) call(
                    generator,
                    "addEnergyOutput",
                    new Class<?>[] { long.class },
                    output.spec()
                        .power()));
            assertEquals(
                output.spec()
                    .power(),
                output.readEU());
            assertFalse(output.isActive());
        }
    }

    public void legacyGeneratorIntOverflowReproducesWithBothNativeAndMeHatches() throws Exception {
        final long requested = MEEnergySpec.multi(Role.DYNAMO, 13, 4)
            .power();
        assertEquals(2147483648L, requested);
        for (final boolean me : new boolean[] { false, true }) {
            final MetaTileEntity generator = machine("GeneratorMultis.TST_SteamBasicGenerator");
            final MetaTileEntity[] outputs = new MetaTileEntity[2];
            for (int i = 0; i < outputs.length; i++) {
                outputs[i] = me ? placed(MEEnergySpec.multi(Role.DYNAMO, 13, 4)).machine()
                    : attachController(
                        new MTEHatchDynamoMulti("test.native.legacy", 13, 4, new String[0], new ITexture[0][][]));
                assertTrue(
                    (Boolean) call(
                        generator,
                        "addDynamoToMachineList",
                        new Class<?>[] { IGregTechTileEntity.class, int.class },
                        outputs[i].getBaseMetaTileEntity(),
                        0));
            }
            // Original upstream int injected wraps after hatch 1; hatch 2 repeats the requested EU.
            assertFalse((Boolean) call(generator, "addEnergyOutput", new Class<?>[] { long.class }, requested));
            assertEquals(requested, outputs[0].getEUVar());
            assertEquals(requested, outputs[1].getEUVar());
        }
    }

    public void megaEggKeepsNonLaserOutputAndBigBroBytecodeUsesNativeStock() throws Exception {
        // BigBro class verification requires EMT solar classes absent from this isolated profile.
        // Inspect its unmodified official bytecode; full machine execution belongs to the game checklist.
        assertTrue(calls("TST_BigBroArray", "fillAllDynamos", "setEUVar"));
        assertTrue(calls("TST_BigBroArray", "fillAllDynamos", "maxEUStore"));
        assertFalse(calls("TST_BigBroArray", "fillAllDynamos", "getOutputVoltage"));
        for (final int amps : new int[] { 0, 4, 64, 256 }) {
            final MetaTileEntity egg = machine("GeneratorMultis.GT_TileEntity_MegaEggGenerator");
            final IMEEnergyEndpoint output = placed(
                amps == 0 ? MEEnergySpec.standard(Role.DYNAMO, 5) : MEEnergySpec.multi(Role.DYNAMO, 5, amps));
            assertTrue(add(egg, "addDynamoToMachineList", output));
            assertTrue((Boolean) call(egg, "checkLaser", new Class<?>[0]));
        }
        final MetaTileEntity egg = machine("GeneratorMultis.GT_TileEntity_MegaEggGenerator");
        final MTEHatchDynamoTunnel laser = new MTEHatchDynamoTunnel(
            "test.native.laser.out",
            5,
            256,
            new String[0],
            new ITexture[0][][]);
        attachController(laser);
        call(
            egg,
            "addDynamoToMachineList",
            new Class<?>[] { IGregTechTileEntity.class, int.class },
            laser.getBaseMetaTileEntity(),
            0);
        assertFalse((Boolean) call(egg, "checkLaser", new Class<?>[0]));
    }

    private static ClassNode bytecode(final String type) throws Exception {
        try (InputStream input = MEEnergyTstCompatibilityFixture.class.getClassLoader()
            .getResourceAsStream((type.startsWith("com.") ? type : PREFIX + type).replace('.', '/') + ".class")) {
            assertTrue("Missing official TST class " + type, input != null);
            final ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }

    private static boolean calls(final String type, final String method, final String callee) throws Exception {
        for (final MethodNode m : bytecode(type).methods)
            if (m.name.equals(method)) for (AbstractInsnNode instruction = m.instructions.getFirst(); instruction
                != null; instruction = instruction.getNext())
                if (instruction instanceof MethodInsnNode && ((MethodInsnNode) instruction).name.equals(callee))
                    return true;
        return false;
    }

    public void pinnedBytecodeConfirmsDistinctGeneratorAndWirelessPaths() throws Exception {
        assertTrue(
            calls(
                "com.Nxer.TwistSpaceTechnology.common.modularizedMachine.ModularizedMachineLogic.MultiExecutionCoreMachineBase",
                "onRunningTick",
                "drainEnergyInput"));
        assertTrue(calls("multiMachineClasses.TST_GeneratorBase", "addEnergyOutputMultipleDynamos", "setEUVar"));
        assertTrue(
            calls(
                "multiMachineClasses.GTCM_MultiMachineBase",
                "addEnergyOutputMultipleDynamos",
                "increaseStoredEnergyUnits"));
        assertTrue(calls("TST_CleanRoom", "onRunningTick", "maxEUOutput"));
        assertFalse(calls("TST_CleanRoom", "onRunningTick", "getOutputVoltage"));
        assertTrue(
            calls(
                "multiMachineClasses.WirelessEnergyMultiMachineBase",
                "wirelessModeProcessOnce",
                "addEUToGlobalEnergyMap"));
        assertEquals(
            "com/Nxer/TwistSpaceTechnology/common/machine/multiMachineClasses/GTCM_MultiMachineBase",
            bytecode("GeneratorMultis.TST_SteamBasicGenerator").superName);
        assertEquals(
            "com/Nxer/TwistSpaceTechnology/common/machine/multiMachineClasses/GTCM_MultiMachineBase",
            bytecode("GeneratorMultis.TST_UniversalGenerator").superName);
    }
}
