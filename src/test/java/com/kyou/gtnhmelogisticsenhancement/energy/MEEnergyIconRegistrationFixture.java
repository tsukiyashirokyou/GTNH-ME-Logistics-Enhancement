package com.kyou.gtnhmelogisticsenhancement.energy;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.util.IIcon;
import net.minecraft.util.ResourceLocation;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.Textures;
import gregtech.api.interfaces.IIconContainer;
import gregtech.api.interfaces.ITexture;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.api.util.client.ResourceUtils;

/** Exercises the real MTE callback and GT icon queue without an OpenGL context. */
public class MEEnergyIconRegistrationFixture extends MEEnergyHatchFixture {

    private static final String DOMAIN = "gtnhmelogisticsenhancement";

    private static List<Runnable> badges() throws Exception {
        final List<Runnable> result = new ArrayList<>();
        for (final Runnable task : GregTechAPI.sGTBlockIconload) {
            if (!(task instanceof IIconContainer)) continue;
            try {
                final Field field = task.getClass()
                    .getDeclaredField("mIconName");
                field.setAccessible(true);
                if (((String) field.get(task)).startsWith(DOMAIN + ":me_eu_")) result.add(task);
            } catch (NoSuchFieldException ignored) { /* Other GT icon container families are not our badges. */ }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static void verify(final String type) throws Exception {
        assertNotNull(Textures.BlockIcons.MACHINE_CASINGS);
        assertTrue("No tooltip or face rendering has initialized the ME badges", badges().isEmpty());
        final boolean energy = type.contains("MEEnergy");
        final boolean multi = type.endsWith("Multi");
        final MEEnergySpec.Role role = energy ? MEEnergySpec.Role.ENERGY : MEEnergySpec.Role.DYNAMO;
        final MEEnergySpec spec = multi ? MEEnergySpec.multi(role, 5, 4) : MEEnergySpec.standard(role, 5);
        final MTEHatch hatch = (MTEHatch) Class.forName("com.kyou.gtnhmelogisticsenhancement.energy." + type)
            .getConstructor(String.class, MEEnergySpec.class, String[].class, ITexture[][][].class)
            .newInstance("test.icons", spec, new String[0], new ITexture[0][][]);
        assertTrue(badges().isEmpty());
        final Set<String> registered = new HashSet<>();
        final IIconRegister iconRegister = name -> {
            assertNotNull(
                name,
                MEEnergyIconRegistrationFixture.class
                    .getResource("/assets/" + name.replace(":", "/textures/blocks/") + ".png"));
            registered.add(name);
            return (IIcon) Proxy.newProxyInstance(
                IIcon.class.getClassLoader(),
                new Class<?>[] { IIcon.class },
                (proxy, method, args) -> method.getName()
                    .equals("getIconName") ? name : null);
        };
        hatch.registerIcons(iconRegister);
        final List<Runnable> queued = badges();
        assertEquals("All badges and paint overlays must be queued during the MTE icon callback", 6, queued.size());
        final Field cacheField = ResourceUtils.class.getDeclaredField("EXISTS_CACHE");
        cacheField.setAccessible(true);
        final Map<ResourceLocation, Boolean> optionalResources = (Map<ResourceLocation, Boolean>) cacheField.get(null);
        // Optional _OVERLAY files do not exist. Avoid booting Minecraft's resource manager.
        for (final String name : new String[] { "badge", "badge_active", "badge_high", "badge_high_active",
            "group_frame", "group_stripe" }) {
            optionalResources
                .put(new ResourceLocation(DOMAIN, "textures/blocks/me_eu_" + name + "_OVERLAY.png"), false);
        }
        GregTechAPI.setBlockIconRegister(iconRegister);
        for (int reload = 0; reload < 2; reload++) {
            registered.clear();
            hatch.registerIcons(iconRegister);
            assertEquals(6, badges().size());
            for (final Runnable task : queued) {
                task.run();
                assertNotNull("Stitched badge must have a real icon", ((IIconContainer) task).getIcon());
                assertTrue(
                    ((IIconContainer) task).getIcon()
                        .getIconName()
                        .startsWith(DOMAIN + ":me_eu_"));
            }
            assertEquals(6, registered.size());
        }
        for (final MEEnergyCatalog.Entry entry : MEEnergyCatalog.entries()) {
            final ITexture base = Textures.BlockIcons.MACHINE_CASINGS[entry.spec()
                .tier()][0];
            for (final boolean active : new boolean[] { false, true }) {
                final ITexture[] layers = MEEnergyPresentation.textures(base, entry.spec(), active);
                assertEquals(4, layers.length);
                assertSame(base, layers[0]);
                for (final ITexture layer : layers) assertNotNull(layer);
            }
        }
    }
}
