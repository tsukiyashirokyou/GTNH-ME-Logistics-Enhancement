package com.kyou.gtnhmelogisticsenhancement.energy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.ShapelessRecipes;
import net.minecraft.world.World;

import appeng.api.AEApi;
import cpw.mods.fml.common.registry.GameRegistry;

/** Fresh-output workbench recipes. No upstream recipe replacement, ME upgrades or EU P2P. */
public final class MEEnergyRecipes {

    private static boolean registered;

    private MEEnergyRecipes() {}

    public static void register() {
        if (registered) throw new IllegalStateException("ME EU recipes already registered");
        final ItemStack p2p = AEApi.instance()
            .definitions()
            .parts()
            .p2PTunnelME()
            .maybeStack(1)
            .orNull();
        if (p2p == null) throw new IllegalStateException("Required untuned ME P2P tunnel is disabled");
        // Resolve all materials before adding any recipe; a missing native tier must fail visibly.
        final List<ShapelessRecipes> recipes = new ArrayList<>(162);
        for (final MEEnergyCatalog.Entry entry : MEEnergyCatalog.entries()) {
            final MEEnergySpec spec = entry.spec();
            recipes.add(recipe(spec, MEEnergyRegistration.stack(spec), p2p));
        }
        for (final ShapelessRecipes recipe : recipes) GameRegistry.addRecipe(recipe);
        registered = true;
    }

    static ShapelessRecipes recipe(final MEEnergySpec spec, final ItemStack output, final ItemStack p2p) {
        final List<ItemStack> ingredients = new ArrayList<>(
            Arrays.asList(MEEnergyNativeMaterials.base(spec), p2p.copy()));
        if (spec.requiresLaserIngredient()) ingredients.add(MEEnergyNativeMaterials.laser(spec));
        return new FreshRecipe(output, ingredients);
    }

    /** NEI-visible ordinary shapeless recipe. Output never inherits ingredient NBT. */
    static final class FreshRecipe extends ShapelessRecipes {

        FreshRecipe(final ItemStack output, final List<ItemStack> ingredients) {
            super(fresh(output), ingredients);
        }

        private static ItemStack fresh(final ItemStack output) {
            final ItemStack result = output.copy();
            result.stackSize = 1;
            result.setTagCompound(null);
            return result;
        }

        @Override
        public boolean matches(final InventoryCrafting inventory, final World world) {
            if (!super.matches(inventory, world)) return false;
            for (int slot = 0; slot < inventory.getSizeInventory(); slot++) {
                final ItemStack input = inventory.getStackInSlot(slot);
                // Reject unknown payload rather than silently destroying charged/owned native hatches.
                if (input != null && (input.stackSize <= 0 || input.hasTagCompound() && !input.getTagCompound()
                    .hasNoTags())) return false;
            }
            return true;
        }
    }
}
