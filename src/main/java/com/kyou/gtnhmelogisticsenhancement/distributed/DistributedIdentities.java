package com.kyou.gtnhmelogisticsenhancement.distributed;

import net.minecraft.item.Item;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.FluidRegistry;

import gregtech.api.util.GTUtility.FluidId;
import gregtech.api.util.GTUtility.ItemId;

/** Native GT identities with defensive NBT copies and registry-name persistence. */
public final class DistributedIdentities {

    public static final PartitionNbt.IdentityCodec<ItemId> ITEMS = new PartitionNbt.IdentityCodec<ItemId>() {

        @Override
        public String type() {
            return "item";
        }

        @Override
        public NBTTagCompound write(final ItemId identity) {
            final Object name = Item.itemRegistry.getNameForObject(identity.item());
            if (name == null || identity.stackSize() != null)
                throw new IllegalArgumentException("Invalid item identity");
            final NBTTagCompound tag = new NBTTagCompound();
            tag.setString("item", name.toString());
            tag.setInteger("meta", identity.metaData());
            if (identity.nbt() != null) tag.setTag(
                "tag",
                identity.nbt()
                    .copy());
            return tag;
        }

        @Override
        public ItemId read(final NBTTagCompound tag) {
            if (!tag.hasKey("item", 8) || !tag.hasKey("meta", 3)
                || tag.func_150296_c()
                    .size() != (tag.hasKey("tag") ? 3 : 2)
                || !Item.itemRegistry.containsKey(tag.getString("item"))
                || (tag.hasKey("tag") && !tag.hasKey("tag", 10)))
                throw new IllegalArgumentException("Invalid item tag");
            final Item item = (Item) Item.itemRegistry.getObject(tag.getString("item"));
            if (item == null) throw new IllegalArgumentException("Missing item");
            return ItemId.create(item, tag.getInteger("meta"), tag.hasKey("tag") ? tag.getCompoundTag("tag") : null);
        }
    };

    public static final PartitionNbt.IdentityCodec<FluidId> FLUIDS = new PartitionNbt.IdentityCodec<FluidId>() {

        @Override
        public String type() {
            return "fluid";
        }

        @Override
        public NBTTagCompound write(final FluidId identity) {
            return (NBTTagCompound) identity.writeToNBT()
                .copy();
        }

        @Override
        public FluidId read(final NBTTagCompound tag) {
            if (!tag.hasKey("FluidName", 8) || tag.hasKey("Amount")
                || tag.func_150296_c()
                    .size() != (tag.hasKey("Tag") ? 2 : 1)
                || FluidRegistry.getFluid(tag.getString("FluidName")) == null
                || (tag.hasKey("Tag") && !tag.hasKey("Tag", 10)))
                throw new IllegalArgumentException("Invalid fluid tag");
            return FluidId.create((NBTTagCompound) tag.copy());
        }
    };

    private DistributedIdentities() {}
}
