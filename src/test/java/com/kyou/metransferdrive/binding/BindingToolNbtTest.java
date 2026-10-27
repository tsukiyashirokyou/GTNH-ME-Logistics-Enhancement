package com.kyou.metransferdrive.binding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

public class BindingToolNbtTest {

    @Test
    public void toolSourceRoundTripKeepsTheDeviceUuidAndPositionWithoutOverwritingOtherData() {
        final UUID id = UUID.randomUUID();
        final NBTTagCompound root = new NBTTagCompound();
        root.setString("unrelated", "preserve");
        BindingToolNbt.writeSource(root, reference(id, 12));
        final BindingToolLogic.SourceReference restored = BindingToolNbt.readSource((NBTTagCompound) root.copy());
        assertEquals(id, restored.deviceId);
        assertEquals(-1, restored.position.dimension);
        assertEquals(12, restored.position.x);
        assertEquals(64, restored.position.y);
        assertEquals(-7, restored.position.z);
        assertEquals("preserve", root.getString("unrelated"));
    }

    @Test
    public void reselectionReplacesTheTemporarySourceRatherThanAccumulatingAList() {
        final NBTTagCompound root = new NBTTagCompound();
        BindingToolNbt.writeSource(root, reference(UUID.randomUUID(), 1));
        final UUID newIdentity = UUID.randomUUID();
        BindingToolNbt.writeSource(root, reference(newIdentity, 2));
        assertEquals(newIdentity, BindingToolNbt.readSource(root).deviceId);
        assertEquals(2, BindingToolNbt.readSource(root).position.x);
    }

    @Test
    public void deviceIdentitySurvivesWorldNbtReloadWhileFreshPlacementsHaveDifferentIdentities() {
        final UUID placed = UUID.randomUUID();
        final NBTTagCompound worldData = new NBTTagCompound();
        BindingToolNbt.writeDeviceIdentity(worldData, placed);
        final UUID constructorIdentity = UUID.randomUUID();
        assertEquals(placed, BindingToolNbt.readDeviceIdentity(worldData, constructorIdentity));
        assertEquals(constructorIdentity, BindingToolNbt.readDeviceIdentity(new NBTTagCompound(), constructorIdentity));
        assertNotEquals(placed, constructorIdentity);
        final NBTTagCompound toolData = new NBTTagCompound();
        BindingToolNbt.writeSource(toolData, reference(placed, 1));
        assertEquals(
            BindingToolNbt.readDeviceIdentity(worldData, constructorIdentity),
            BindingToolNbt.readSource(toolData).deviceId);
    }

    @Test
    public void invalidSavedDeviceIdentityUsesTheFreshIdentityAndDoesNotRecreateAnOldOne() {
        final NBTTagCompound data = new NBTTagCompound();
        data.setString("bindingDeviceId", "corrupt");
        final UUID fresh = UUID.randomUUID();
        assertEquals(fresh, BindingToolNbt.readDeviceIdentity(data, fresh));
    }

    @Test
    public void missingSourceAndAeWirelessOrMemoryCardDataDoNotCreateASelection() {
        assertNull(BindingToolNbt.readSource(null));
        final NBTTagCompound root = new NBTTagCompound();
        root.setTag("Simple", new NBTTagCompound());
        root.setTag("Data", new NBTTagCompound());
        assertNull(BindingToolNbt.readSource(root));
    }

    @Test(expected = IllegalArgumentException.class)
    public void unsupportedSourceSchemaIsRejected() {
        final NBTTagCompound root = saved();
        root.getCompoundTag(BindingToolNbt.SOURCE_KEY)
            .setInteger("version", 99);
        BindingToolNbt.readSource(root);
    }

    @Test(expected = IllegalArgumentException.class)
    public void missingUuidCannotFallBackToCoordinateOnlyBinding() {
        final NBTTagCompound root = saved();
        root.getCompoundTag(BindingToolNbt.SOURCE_KEY)
            .removeTag("deviceId");
        BindingToolNbt.readSource(root);
    }

    @Test(expected = IllegalArgumentException.class)
    public void wrongCoordinateTagTypeIsRejected() {
        final NBTTagCompound root = saved();
        root.getCompoundTag(BindingToolNbt.SOURCE_KEY)
            .setString("x", "1");
        BindingToolNbt.readSource(root);
    }

    @Test(expected = IllegalArgumentException.class)
    public void malformedRootTagIsRejected() {
        final NBTTagCompound root = new NBTTagCompound();
        root.setString(BindingToolNbt.SOURCE_KEY, "not a source");
        BindingToolNbt.readSource(root);
    }

    @Test(expected = IllegalArgumentException.class)
    public void abbreviatedUuidCannotBecomeAnUnexpectedCanonicalIdentity() {
        final NBTTagCompound root = saved();
        root.getCompoundTag(BindingToolNbt.SOURCE_KEY)
            .setString("deviceId", "1-1-1-1-1");
        BindingToolNbt.readSource(root);
    }

    @Test(expected = IllegalArgumentException.class)
    public void outOfWorldReferenceIsRejected() {
        final NBTTagCompound root = saved();
        root.getCompoundTag(BindingToolNbt.SOURCE_KEY)
            .setInteger("y", 256);
        BindingToolNbt.readSource(root);
    }

    private static NBTTagCompound saved() {
        final NBTTagCompound root = new NBTTagCompound();
        BindingToolNbt.writeSource(root, reference(UUID.randomUUID(), 1));
        return root;
    }

    private static BindingToolLogic.SourceReference reference(final UUID id, final int x) {
        return new BindingToolLogic.SourceReference(new BindingToolLogic.Position(-1, x, 64, -7), id);
    }
}
