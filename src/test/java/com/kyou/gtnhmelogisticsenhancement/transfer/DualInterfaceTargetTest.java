package com.kyou.gtnhmelogisticsenhancement.transfer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import net.minecraft.tileentity.TileEntity;

import org.junit.Test;

import com.glodblock.github.common.tile.TileFluidInterface;

import appeng.tile.misc.TileInterface;
import appeng.tile.networking.TileCableBus;

/** Native pinned tile identities without constructors, mod startup, grids or a Minecraft world. */
public class DualInterfaceTargetTest {

    @Test
    public void acceptsOnlyTheNativeDualInterfaceBlock() throws Exception {
        final TileFluidInterface dual = allocate(TileFluidInterface.class);
        assertSame(dual, DualInterfaceTarget.resolve(dual));
        assertNull(DualInterfaceTarget.resolve(allocate(TileInterface.class)));
        assertNull(DualInterfaceTarget.resolve(allocate(TileEntity.class)));
        assertNull(DualInterfaceTarget.resolve(allocate(TileCableBus.class)));
        assertNull(DualInterfaceTarget.resolve(allocate(DualInterfaceSubclass.class)));
        assertNull(DualInterfaceTarget.resolve(null));
    }

    @Test
    public void invalidatedNativeDualInterfaceCannotRemainABindingTarget() throws Exception {
        final TileFluidInterface dual = allocate(TileFluidInterface.class);
        final Field invalid = TileEntity.class.getDeclaredField("tileEntityInvalid");
        invalid.setAccessible(true);
        invalid.setBoolean(dual, true);
        assertNull(DualInterfaceTarget.resolve(dual));
        invalid.setBoolean(dual, false);
        assertSame(dual, DualInterfaceTarget.resolve(dual));
    }

    @Test
    public void targetCheckLoadsAndRejectsOrdinaryInterfacesWithoutTheOptionalMod() throws Exception {
        final String helperName = DualInterfaceTarget.class.getName();
        final byte[] bytecode;
        try (InputStream input = DualInterfaceTargetTest.class
            .getResourceAsStream("/" + helperName.replace('.', '/') + ".class")) {
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            final byte[] buffer = new byte[4096];
            int size;
            while ((size = input.read(buffer)) != -1) {
                output.write(buffer, 0, size);
            }
            bytecode = output.toByteArray();
        }
        // A dotted name string is intentional; JVM type/descriptors must not link optional implementation classes.
        assertFalse(new String(bytecode, StandardCharsets.ISO_8859_1).contains("com/glodblock/"));
        final ClassLoader absentModLoader = new ClassLoader(DualInterfaceTargetTest.class.getClassLoader()) {

            @Override
            protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("com.glodblock.")) {
                    throw new ClassNotFoundException("AE2 Fluid Crafting intentionally absent: " + name);
                }
                if (!helperName.equals(name)) {
                    return super.loadClass(name, resolve);
                }
                Class<?> helper = findLoadedClass(name);
                if (helper == null) {
                    helper = defineClass(name, bytecode, 0, bytecode.length);
                }
                if (resolve) {
                    resolveClass(helper);
                }
                return helper;
            }
        };
        final Class<?> helper = Class.forName(helperName, true, absentModLoader);
        assertNotSame(DualInterfaceTarget.class, helper);
        final Method resolve = helper.getMethod("resolve", TileEntity.class);
        assertNull(resolve.invoke(null, new Object[] { null }));
        assertNull(resolve.invoke(null, allocate(TileInterface.class)));
        assertNull(resolve.invoke(null, allocate(TileCableBus.class)));
    }

    private static <T> T allocate(final Class<T> type) throws Exception {
        final Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        final Field singleton = unsafeClass.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return type.cast(
            unsafeClass.getMethod("allocateInstance", Class.class)
                .invoke(singleton.get(null), type));
    }

    public static class DualInterfaceSubclass extends TileFluidInterface {
    }
}
