package com.kyou.gtnhmelogisticsenhancement.distributed;

import java.util.Arrays;

import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.ShapelessRecipes;
import net.minecraft.world.World;

/** NEI sees an ordinary shapeless recipe. Matching additionally rejects every nonempty ingredient payload. */
public final class SafeDistributedBusRecipe extends ShapelessRecipes {

    public SafeDistributedBusRecipe(final ItemStack result, final ItemStack bus, final ItemStack distribution) {
        super(result.copy(), Arrays.asList(bus.copy(), distribution.copy()));
    }

    @Override
    public boolean matches(final InventoryCrafting inventory, final World world) {
        if (!super.matches(inventory, world)) return false;
        for (int slot = 0; slot < inventory.getSizeInventory(); slot++) {
            final ItemStack stack = inventory.getStackInSlot(slot);
            if (stack != null && (stack.stackSize <= 0 || stack.hasTagCompound() && !stack.getTagCompound()
                .hasNoTags())) return false;
        }
        // Only factory-fresh/empty-payload ME buses are proven empty here. Even harmless unknown tags are rejected.
        // This covers Inventory, provider cache, cachedItems/cachedStack, cells and future unknown payloads alike.
        return true;
    }
}
