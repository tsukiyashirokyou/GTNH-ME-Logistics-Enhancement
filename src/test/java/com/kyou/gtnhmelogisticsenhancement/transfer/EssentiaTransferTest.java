package com.kyou.gtnhmelogisticsenhancement.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IAEStackType;

/** Pinned ThE bytecode/API checks and generic contract tests; no Forge/AE bootstrap or real-world claim. */
public class EssentiaTransferTest {

    private static ClassReader pinned(final String name) throws Exception {
        try (InputStream input = EssentiaTransferTest.class
            .getResourceAsStream("/thaumicenergistics/common/" + name + ".class")) {
            org.junit.Assert.assertNotNull(input);
            return new ClassReader(input);
        }
    }

    @Test
    public void pinnedEssentiaCellUsesTheNativeCellInventoryAndHandlerContracts() throws Exception {
        assertEquals("appeng/me/storage/CellInventory", pinned("inventory/EssentiaCellInventory").getSuperName());
        assertEquals(
            "appeng/me/storage/CellInventoryHandler",
            pinned("inventory/EssentiaCellInventoryHandler").getSuperName());
        assertEquals("appeng/util/item/AEStack", pinned("storage/AEEssentiaStack").getSuperName());
    }

    @Test
    public void pinnedStackPersistsAspectIdentityAndLongAmountAndUsesOneNativeUnit() throws Exception {
        final Set<String> constants = new HashSet<>();
        final java.util.List<Integer> units = new java.util.ArrayList<>();
        pinned("storage/AEEssentiaStack").accept(new ClassVisitor(Opcodes.ASM5) {

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                String[] exceptions) {
                if ("getAmountPerUnit".equals(name)) {
                    return new MethodVisitor(Opcodes.ASM5) {

                        @Override
                        public void visitInsn(int opcode) {
                            units.add(opcode);
                        }
                    };
                }
                if (!"writeToNBT".equals(name)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM5) {

                    @Override
                    public void visitLdcInsn(Object value) {
                        constants.add(String.valueOf(value));
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean itf) {
                        constants.add(method + desc);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        assertTrue(constants.contains("AspectTag"));
        assertTrue(constants.contains("Cnt"));
        assertTrue(constants.contains("setLong(Ljava/lang/String;J)V"));
        assertEquals(Arrays.asList(Opcodes.ICONST_1, Opcodes.IRETURN), units);
    }

    @Test
    public void registeredEssentiaUsesNativeUnitsAndBoundedBatchesWithoutItemOrFluidFlags() {
        final IAEStackType<?> type = GridStorageDestinationTest.type("essentia", 1);
        final IAEStack<?> aspect = GridStorageDestinationTest.stack(type, IAEStack.class, "aer", 1);
        assertFalse(aspect.isItem());
        assertFalse(aspect.isFluid());
        assertEquals(TransferEnergy.MAX_AMOUNT, TransferStorageTypes.batchLimit(type, id -> type));
        assertEquals(0, TransferStorageTypes.batchLimit(GridStorageDestinationTest.type("essentia", 1), id -> type));
        assertEquals(0, TransferStorageTypes.batchLimit(GridStorageDestinationTest.type("unknown", 1), id -> type));
        assertEquals(0, TransferStorageTypes.batchLimit(null, id -> type));
    }

    @Test
    public void allSupportedTypesUseTheNativeContainerBoundaryAndUnregisteredEssentiaIsNotEnabled() {
        for (String id : new String[] { "item", "fluid", "essentia", "unknown" }) {
            final IAEStackType<?> type = GridStorageDestinationTest.type(id, "fluid".equals(id) ? 1000 : 1);
            assertEquals(
                "unknown".equals(id) ? 0 : TransferEnergy.MAX_AMOUNT,
                TransferStorageTypes.batchLimit(type, ignored -> type));
            assertEquals(0, TransferStorageTypes.batchLimit(type, ignored -> null));
        }
        final IAEStackType<?> invalidUnits = GridStorageDestinationTest.type("essentia", 1000);
        assertEquals(0, TransferStorageTypes.batchLimit(invalidUnits, ignored -> invalidUnits));
    }

    @Test
    public void essentiaJournalRetainsAspectQuantityAndUncertainEvidenceWithoutRefunding() {
        final IAEStackType<?> type = GridStorageDestinationTest.type("essentia", 1);
        final IAEStack<?> aspect = GridStorageDestinationTest.stack(type, IAEStack.class, "aer", 1);
        final TransferTransaction<IAEStack<?>> transaction = new TransferTransaction<>(() -> {});
        final long held = (long) Integer.MAX_VALUE + 23;
        transaction.quarantine(aspect, TransferTransaction.Phase.INSERTING, held + 30, held, 30);
        final UUID target = UUID.randomUUID();
        final NBTTagCompound record = RecoveryEvidence.snapshot(transaction, 63, target, null);
        assertEquals("essentia", record.getString("type"));
        assertEquals(
            "aer",
            record.getCompoundTag("payload")
                .getString("AspectTag"));
        assertEquals(
            1,
            record.getCompoundTag("payload")
                .getLong("Cnt"));
        assertEquals(held, record.getLong("held"));
        assertEquals(30, record.getLong("delivered"));
        assertEquals(target.toString(), record.getString("targetId"));
        final NBTTagCompound root = new NBTTagCompound();
        RecoveryEvidence.write(root, record);
        assertEquals(record, RecoveryEvidence.read(root));
        assertEquals(TransferTransaction.Phase.UNCERTAIN, transaction.getPhase());
    }

    @Test
    public void invalidSourceAmountsAndLongSumOverflowAreRejectedBeforeExtraction() {
        final IAEStackType<?> type = GridStorageDestinationTest.type("essentia", 1);
        for (java.util.List<?> contents : Arrays.asList(
            Arrays.asList(GridStorageDestinationTest.stack(type, IAEStack.class, "aer", -1)),
            Arrays.asList(
                GridStorageDestinationTest.stack(type, IAEStack.class, "aer", Long.MAX_VALUE),
                GridStorageDestinationTest.stack(type, IAEStack.class, "ignis", 1)))) {
            try {
                SourceCellAccess.validateSnapshot(type, contents);
                throw new AssertionError("Invalid native quantities accepted");
            } catch (IllegalStateException | ArithmeticException expected) {
                // Inventory evidence is not an ordinary clampable configuration input.
            }
        }
    }
}
