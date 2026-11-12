package com.kyou.gtnhmelogisticsenhancement.energy;

import static org.junit.Assert.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.ShapelessRecipes;
import net.minecraft.nbt.NBTTagCompound;

import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.value.sync.ModularSyncManager;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.ItemList;
import gregtech.api.enums.MetaTileEntityIDs;
import gregtech.api.interfaces.IItemContainer;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import tectech.thing.CustomItemList;

/** Registry-only fixture. Native ingredients come from a source-derived independent upstream manifest. */
public class MEEnergyDeliveryFixture extends MEEnergyMachineCompatibilityFixture {

    private static boolean nativeMaterials;
    private static boolean registered;

    public static void registries() throws Exception {
        MEEnergyMachineCompatibilityFixture.registries();
        // Native registration constructors require the real GT load-phase gate (never post-init).
        GregTechAPI.sPreloadStarted = true;
        GregTechAPI.sPostloadStarted = false;
        gregtech.GTMod.proxy = new gregtech.common.GTProxy();
        gregtech.GTMod.GT = new gregtech.GTMod();
    }

    private static void nativeMaterials() throws Exception {
        if (nativeMaterials) return;
        try (BufferedReader input = new BufferedReader(
            new InputStreamReader(
                MEEnergyDeliveryFixture.class.getResourceAsStream("/energy-native-materials.tsv"),
                StandardCharsets.UTF_8))) {
            String line;
            int count = 0;
            while ((line = input.readLine()) != null) {
                if (line.startsWith("#") || line.isEmpty()) continue;
                final String[] row = line.split("\t");
                final int id = MetaTileEntityIDs.valueOf(row[2]).ID;
                assertTrue(id < 32552 || id > 32713);
                final Class<?> type = Class.forName(
                    (row[0].equals("GT") ? "gregtech.api.metatileentity.implementations."
                        : "tectech.thing.metaTileEntity.hatch.") + row[3]);
                final MetaTileEntity nativeHatch;
                final int tier = Integer.parseInt(row[4]), amps = Integer.parseInt(row[5]);
                if (row[0].equals("GT"))
                    nativeHatch = (MetaTileEntity) type.getConstructor(int.class, String.class, String.class, int.class)
                        .newInstance(id, "test.native." + row[1], "", tier);
                else nativeHatch = (MetaTileEntity) type
                    .getConstructor(int.class, String.class, String.class, int.class, int.class)
                    .newInstance(id, "test.native." + row[1], "", tier, amps);
                final IItemContainer item = (IItemContainer) (row[0].equals("GT") ? ItemList.class
                    : CustomItemList.class).getField(row[1])
                        .get(null);
                item.set(nativeHatch.getStackForm(1));
                count++;
            }
            assertEquals(168, count);
        }
        nativeMaterials = true;
    }

    private static void registration() throws Exception {
        nativeMaterials();
        if (!registered) {
            MEEnergyRegistration.register();
            registered = true;
        }
    }

    public void registrationPreflightsEveryIdAndDetectsLateOverwrite() throws Exception {
        if (!registered) {
            final IMetaTileEntity occupied = placed(MEEnergySpec.standard(MEEnergySpec.Role.ENERGY, 5)).machine();
            for (final int conflict : new int[] { 32552, 32640, 32713 }) {
                GregTechAPI.METATILEENTITIES[conflict] = occupied;
                try {
                    final IllegalStateException failure = assertThrows(
                        IllegalStateException.class,
                        MEEnergyRegistration::register);
                    assertTrue(
                        failure.getMessage()
                            .contains(Integer.toString(conflict)));
                    for (int id = 32552; id <= 32713; id++)
                        assertEquals(id == conflict ? occupied : null, GregTechAPI.METATILEENTITIES[id]);
                } finally {
                    GregTechAPI.METATILEENTITIES[conflict] = null;
                }
            }
        }
        final IMetaTileEntity oldItem = GregTechAPI.METATILEENTITIES[32550],
            oldFluid = GregTechAPI.METATILEENTITIES[32551];
        registration();
        MEEnergyRegistration.verify();
        assertSame(oldItem, GregTechAPI.METATILEENTITIES[32550]);
        assertSame(oldFluid, GregTechAPI.METATILEENTITIES[32551]);
        assertThrows(IllegalStateException.class, MEEnergyRegistration::register);
        final IMetaTileEntity saved = GregTechAPI.METATILEENTITIES[32713];
        try {
            GregTechAPI.METATILEENTITIES[32713] = oldItem;
            assertThrows(IllegalStateException.class, MEEnergyRegistration::verify);
        } finally {
            GregTechAPI.METATILEENTITIES[32713] = saved;
        }
    }

    public void allRegisteredSpecsCloneAndSurviveNativeWorldSaveAndFreshDrops() throws Exception {
        registration();
        final Set<Integer> ids = new HashSet<>();
        for (final MEEnergyCatalog.Entry entry : MEEnergyCatalog.entries()) {
            final MEEnergySpec spec = entry.spec();
            final ItemStack stack = MEEnergyRegistration.stack(spec);
            assertTrue(ids.add(stack.getItemDamage()));
            assertEquals(entry.candidateMteId(), stack.getItemDamage());
            stack.setTagCompound(new NBTTagCompound());
            stack.getTagCompound()
                .setLong("mStoredEnergy", 10);
            assertFalse(
                MEEnergyRegistration.stack(spec)
                    .hasTagCompound());
            final BaseMetaTileEntity base = new BaseMetaTileEntity();
            base.setInitialValuesAsNBT(new NBTTagCompound(), (short) entry.candidateMteId());
            final IMEEnergyEndpoint host = (IMEEnergyEndpoint) base.getMetaTileEntity();
            assertEquals(spec, host.spec());
            host.machine()
                .onFirstTick(base);
            host.machine()
                .setEUVar(spec.capacity() / 3);
            if (spec.role() == MEEnergySpec.Role.DYNAMO) host.energyState()
                .lossAccount()
                .record(1, 2);
            final NBTTagCompound world = new NBTTagCompound();
            base.writeToNBT(world);
            final BaseMetaTileEntity restored = new BaseMetaTileEntity();
            restored.readFromNBT(world);
            final IMEEnergyEndpoint reloaded = (IMEEnergyEndpoint) restored.getMetaTileEntity();
            assertEquals(spec, reloaded.spec());
            assertEquals(spec.capacity() / 3, reloaded.readEU());
            if (spec.role() == MEEnergySpec.Role.DYNAMO) assertEquals(
                18,
                reloaded.energyState()
                    .lossAccount()
                    .credit());
            final ItemStack drop = base.getDrops()
                .get(0);
            final BaseMetaTileEntity replaced = new BaseMetaTileEntity();
            replaced.setInitialValuesAsNBT(
                drop.hasTagCompound() ? drop.getTagCompound() : new NBTTagCompound(),
                (short) entry.candidateMteId());
            assertEquals(0, ((IMEEnergyEndpoint) replaced.getMetaTileEntity()).readEU());
        }
        assertEquals(162, ids.size());
    }

    private static InventoryCrafting table() {
        return new InventoryCrafting(new Container() {

            @Override
            public boolean canInteractWith(final EntityPlayer player) {
                return true;
            }
        }, 3, 3);
    }

    public void all162RecipesMatchPermutationsAndIndependentNativeTypes() throws Exception {
        registration();
        final ItemStack p2p = appeng.api.AEApi.instance()
            .definitions()
            .parts()
            .p2PTunnelME()
            .maybeStack(1)
            .get();
        int two = 0, three = 0;
        for (final MEEnergyCatalog.Entry entry : MEEnergyCatalog.entries()) {
            final MEEnergySpec spec = entry.spec();
            final ShapelessRecipes recipe = MEEnergyRecipes.recipe(spec, MEEnergyRegistration.stack(spec), p2p);
            final gregtech.api.metatileentity.implementations.MTEHatch nativeBase = (gregtech.api.metatileentity.implementations.MTEHatch) GregTechAPI.METATILEENTITIES[((ItemStack) recipe.recipeItems
                .get(0)).getItemDamage()];
            assertEquals(spec.tier(), nativeBase.getTierForStructure());
            assertFalse(nativeBase instanceof tectech.thing.metaTileEntity.hatch.MTEHatchEnergyTunnel);
            assertFalse(nativeBase instanceof tectech.thing.metaTileEntity.hatch.MTEHatchDynamoTunnel);
            assertEquals(
                spec.role() == MEEnergySpec.Role.ENERGY,
                nativeBase instanceof gregtech.api.metatileentity.implementations.MTEHatchEnergy
                    || nativeBase instanceof tectech.thing.metaTileEntity.hatch.MTEHatchEnergyMulti);
            final long actualAmps = spec.role() == MEEnergySpec.Role.ENERGY
                ? nativeBase instanceof tectech.thing.metaTileEntity.hatch.MTEHatchEnergyMulti
                    ? ((tectech.thing.metaTileEntity.hatch.MTEHatchEnergyMulti) nativeBase).getAmperes()
                    : 2
                : nativeBase.maxAmperesOut();
            assertEquals(spec.nativeBaseAmperage(), actualAmps);
            if (recipe.recipeItems.size() == 2) two++;
            else {
                three++;
                final gregtech.api.metatileentity.implementations.MTEHatch laser = (gregtech.api.metatileentity.implementations.MTEHatch) GregTechAPI.METATILEENTITIES[((ItemStack) recipe.recipeItems
                    .get(2)).getItemDamage()];
                assertEquals(spec.tier(), laser.getTierForStructure());
                assertEquals(
                    spec.role() == MEEnergySpec.Role.ENERGY,
                    laser instanceof tectech.thing.metaTileEntity.hatch.MTEHatchEnergyTunnel);
                assertEquals(
                    spec.amperage(),
                    spec.role() == MEEnergySpec.Role.ENERGY
                        ? ((tectech.thing.metaTileEntity.hatch.MTEHatchEnergyMulti) laser).getAmperes()
                        : laser.maxAmperesOut());
            }
            for (int a = 0; a < recipe.recipeItems.size(); a++) for (int b = 0; b < recipe.recipeItems.size(); b++) {
                if (a == b) continue;
                final InventoryCrafting inventory = table();
                inventory.setInventorySlotContents(0, ((ItemStack) recipe.recipeItems.get(a)).copy());
                inventory.setInventorySlotContents(4, ((ItemStack) recipe.recipeItems.get(b)).copy());
                if (recipe.recipeItems.size() == 3)
                    inventory.setInventorySlotContents(8, ((ItemStack) recipe.recipeItems.get(3 - a - b)).copy());
                assertTrue(spec.key(), recipe.matches(inventory, null));
                assertFalse(
                    recipe.getCraftingResult(inventory)
                        .hasTagCompound());
            }
            final InventoryCrafting inventory = table();
            for (int i = 0; i < recipe.recipeItems.size(); i++)
                inventory.setInventorySlotContents(i, ((ItemStack) recipe.recipeItems.get(i)).copy());
            inventory.setInventorySlotContents(0, null);
            assertFalse(recipe.matches(inventory, null));
            for (int i = 0; i < recipe.recipeItems.size(); i++) {
                for (int j = 0; j < recipe.recipeItems.size(); j++)
                    inventory.setInventorySlotContents(j, ((ItemStack) recipe.recipeItems.get(j)).copy());
                final ItemStack wrong = inventory.getStackInSlot(i);
                wrong.setItemDamage(wrong.getItemDamage() + 1);
                assertFalse("Wrong native variant: " + spec.key(), recipe.matches(inventory, null));
            }
            for (int i = 0; i < recipe.recipeItems.size(); i++) {
                final ItemStack bad = ((ItemStack) recipe.recipeItems.get(i)).copy();
                bad.setTagCompound(new NBTTagCompound());
                bad.getTagCompound()
                    .setLong("mStoredEnergy", 123);
                for (int j = 0; j < recipe.recipeItems.size(); j++)
                    inventory.setInventorySlotContents(j, ((ItemStack) recipe.recipeItems.get(j)).copy());
                inventory.setInventorySlotContents(i, bad);
                assertFalse(recipe.matches(inventory, null));
            }
        }
        assertEquals(78, two);
        assertEquals(84, three);
    }

    public void recipesRegisterOnceWithRealUntunedMeP2p() throws Exception {
        registration();
        final int before = CraftingManager.getInstance()
            .getRecipeList()
            .size();
        MEEnergyRecipes.register();
        assertEquals(
            before + 162,
            CraftingManager.getInstance()
                .getRecipeList()
                .size());
        assertThrows(IllegalStateException.class, MEEnergyRecipes::register);
        assertEquals(
            before + 162,
            CraftingManager.getInstance()
                .getRecipeList()
                .size());
    }

    public void localizedNamesOverlaysAndReadOnlyPanelsCoverEverySpec() throws Exception {
        registration();
        final List<Properties> languages = new ArrayList<>();
        for (final String locale : Arrays.asList("en_US", "zh_CN")) {
            final Properties props = new Properties();
            try (InputStreamReader reader = new InputStreamReader(
                MEEnergyDeliveryFixture.class
                    .getResourceAsStream("/assets/gtnhmelogisticsenhancement/lang/" + locale + ".lang"),
                StandardCharsets.UTF_8)) {
                props.load(reader);
            }
            languages.add(props);
        }
        for (final MEEnergyCatalog.Entry entry : MEEnergyCatalog.entries()) {
            final IMEEnergyEndpoint host = (IMEEnergyEndpoint) placed(entry.spec()).machine();
            for (final Properties props : languages) assertNotNull(
                props.getProperty("gt.blockmachines." + MEEnergyRegistration.name(entry.spec()) + ".name"));
            final com.cleanroommc.modularui.screen.ModularPanel panel = MEEnergyGui
                .build(host, new PanelSyncManager(new ModularSyncManager(false), true), new UISettings());
            final java.util.ArrayDeque<com.cleanroommc.modularui.api.widget.IWidget> queue = new java.util.ArrayDeque<>();
            queue.add(panel);
            int children = 0;
            while (!queue.isEmpty()) {
                final com.cleanroommc.modularui.api.widget.IWidget widget = queue.remove();
                assertFalse(widget instanceof com.cleanroommc.modularui.widgets.slot.ItemSlot);
                assertFalse(widget instanceof com.cleanroommc.modularui.widgets.ButtonWidget);
                assertFalse(widget instanceof com.cleanroommc.modularui.widgets.textfield.TextFieldWidget);
                children++;
                queue.addAll(widget.getChildren());
            }
            assertTrue(children >= 10);
            assertEquals(
                10,
                host.machine()
                    .getDescription().length);
        }
        for (final String role : Arrays.asList("energy", "dynamo")) {
            final java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(
                MEEnergyDeliveryFixture.class
                    .getResourceAsStream("/assets/gtnhmelogisticsenhancement/textures/blocks/me_eu_" + role + ".png"));
            assertEquals(16, image.getWidth());
            assertEquals(16, image.getHeight());
            assertEquals(0, image.getRGB(0, 0));
        }
    }

    public void aeEventsUpdateNativeActivityWithoutPollingStock() throws Exception {
        final Frame frame = new Frame();
        final IMEEnergyEndpoint host = hatch(MEEnergySpec.Role.DYNAMO, 5, 4);
        final FakeNode node = frame.add(host, 0);
        frame.cache.onUpdateTick();
        assertTrue(
            host.machine()
                .getBaseMetaTileEntity()
                .isActive());
        final long reads = frame.cache.transfers()
            .reads();
        node.channel = false;
        host.gridChanged();
        frame.cache.onUpdateTick();
        assertFalse(
            host.machine()
                .getBaseMetaTileEntity()
                .isActive());
        assertEquals(
            reads,
            frame.cache.transfers()
                .reads());
    }
}
