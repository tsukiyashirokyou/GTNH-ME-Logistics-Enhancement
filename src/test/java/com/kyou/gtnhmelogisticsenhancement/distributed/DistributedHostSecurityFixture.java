package com.kyou.gtnhmelogisticsenhancement.distributed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import appeng.api.config.SecurityPermissions;
import appeng.api.networking.security.ISecurityGrid;
import appeng.me.GridAccessException;
import appeng.me.helpers.AENetworkProxy;
import appeng.me.helpers.IGridProxyable;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;

/** Real host methods with explicit GT/AE stubs; the fixture never creates or ticks a world. */
public class DistributedHostSecurityFixture {

    private static final UUID OWNER = UUID.fromString("42ec9101-a562-4c60-a450-48ad3cd558d0");
    private static final UUID OTHER = UUID.fromString("19f54421-7898-4d60-91cf-df234c77fc16");

    public static void registries() throws Exception {
        DistributedBusFixture.registries();
    }

    public void inactiveHostsAllowOnlyTheirOwnerWithoutQueryingSecurity() throws Exception {
        for (final boolean fluid : new boolean[] { false, true }) {
            final Subject subject = new Subject(fluid);
            // Native SecurityCache allows BUILD when its provider is unavailable, even with an existing Grid.
            subject.proxy.permitted = true;
            assertFalse(subject.host.canConfigure(player(OTHER)));
            assertTrue(subject.host.canConfigure(player(OWNER)));
            assertEquals(0, subject.proxy.securityQueries);
        }
    }

    public void activeHostsRequireNativeBuildPermission() throws Exception {
        for (final boolean fluid : new boolean[] { false, true }) {
            final Subject subject = new Subject(fluid);
            subject.proxy.active = true;
            subject.proxy.permitted = true;
            assertTrue(subject.host.canConfigure(player(OTHER)));
            subject.proxy.permitted = false;
            assertFalse(subject.host.canConfigure(player(OTHER)));
            assertFalse(subject.host.canConfigure(player(OWNER)));
            assertEquals(3, subject.proxy.securityQueries);
        }
    }

    public void gridFailureRetainsOwnerOnlyFallback() throws Exception {
        for (final boolean fluid : new boolean[] { false, true }) {
            final Subject subject = new Subject(fluid);
            subject.proxy.active = true;
            subject.proxy.offline = true;
            assertFalse(subject.host.canConfigure(player(OTHER)));
            assertTrue(subject.host.canConfigure(player(OWNER)));
            assertEquals(2, subject.proxy.securityQueries);
        }
    }

    public void hostValidityAndGtAccessRemainRequired() throws Exception {
        for (final boolean fluid : new boolean[] { false, true }) {
            final Subject subject = new Subject(fluid);
            final EntityPlayer owner = player(OWNER);
            assertFalse(subject.host.canConfigure(null));
            subject.dead = true;
            assertFalse(subject.host.canConfigure(owner));
            subject.dead = false;
            subject.gtAccess = false;
            assertFalse(subject.host.canConfigure(owner));
            subject.gtAccess = true;
            owner.posX = 10;
            assertFalse(subject.host.canConfigure(owner));
            owner.posX = 0.5;
            subject.attached = null;
            assertFalse(subject.host.canConfigure(owner));
            subject.attached = subject.host.machine();
            subject.host.machine()
                .onRemoval();
            assertFalse(subject.host.canConfigure(owner));
            assertEquals(0, subject.proxy.securityQueries);
        }
    }

    public void malformedBusNodeIsIsolatedAndPreserved() throws Exception {
        for (final boolean list : new boolean[] { false, true }) {
            final MTEDistributedMEOutputBus bus = bus();
            final RecordingProxy proxy = new RecordingProxy(bus);
            final Field proxyField = MTEDistributedMEOutputBus.class.getDeclaredField("proxy");
            proxyField.setAccessible(true);
            proxyField.set(bus, proxy);
            final NBTTagCompound input = new NBTTagCompound();
            if (list) input.setTag("distributedAE", new NBTTagList());
            else input.setString("distributedAE", "invalid node payload");
            bus.loadNBTData(input);
            assertTrue(
                bus.state()
                    .locked());
            assertEquals(
                "INVALID_AE_NODE",
                bus.state()
                    .fault());
            assertFalse(proxy.loaded.hasKey("distributedAE"));
            final NBTTagCompound saved = new NBTTagCompound();
            bus.saveNBTData(saved);
            // The raw field must win even if a live proxy emits an ordinary node compound.
            assertEquals(input.getTag("distributedAE"), saved.getTag("distributedAE"));
            assertFalse(saved.hasKey("distributedNodeEvidence"));
            final NBTTagCompound dropped = new NBTTagCompound();
            bus.setItemNBT(dropped);
            assertEquals(input.getTag("distributedAE"), dropped.getTag("distributedAE"));
            final MTEDistributedMEOutputBus restored = bus();
            restored.loadNBTData(dropped);
            assertTrue(
                restored.state()
                    .locked());
            final NBTTagCompound resaved = new NBTTagCompound();
            restored.saveNBTData(resaved);
            assertEquals(input.getTag("distributedAE"), resaved.getTag("distributedAE"));
        }
    }

    public void validBusNodeRetainsItsNormalRoundTrip() {
        final MTEDistributedMEOutputBus bus = bus();
        final NBTTagCompound input = new NBTTagCompound();
        final NBTTagCompound node = new NBTTagCompound();
        node.setInteger("p", 42);
        node.setLong("k", 123);
        node.setLong("g", 456);
        input.setTag("distributedAE", node);
        bus.loadNBTData(input);
        assertFalse(
            bus.state()
                .locked());
        final NBTTagCompound saved = new NBTTagCompound();
        bus.saveNBTData(saved);
        assertEquals(node, saved.getTag("distributedAE"));
        final NBTTagCompound dropped = new NBTTagCompound();
        bus.setItemNBT(dropped);
        assertEquals(node, dropped.getTag("distributedAE"));
    }

    private static MTEDistributedMEOutputBus bus() {
        return new MTEDistributedMEOutputBus("test.host.security", 4, new String[0], new ITexture[0][][]);
    }

    private static EntityPlayer player(final UUID identity) throws Exception {
        // Test-only allocation skips EntityPlayer's world/server constructor. canConfigure reads only UUID,
        // worldObj and numeric position; these fixtures do not model player inventories or gameplay.
        final Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        final Field singleton = unsafeClass.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        final EntityPlayer player = (EntityPlayer) unsafeClass.getMethod("allocateInstance", Class.class)
            .invoke(singleton.get(null), EntityPlayerMP.class);
        final Field uuid = Entity.class.getDeclaredField("entityUniqueID");
        uuid.setAccessible(true);
        uuid.set(player, identity);
        player.posX = player.posY = player.posZ = 0.5;
        return player;
    }

    private static final class Subject {

        private final DistributedOutputHost<?> host;
        private final PermissionProxy proxy;
        private MetaTileEntity attached;
        private boolean dead;
        private boolean gtAccess = true;

        private Subject(final boolean fluid) {
            if (fluid) {
                final PermissionHatch hatch = new PermissionHatch();
                this.host = hatch;
                this.proxy = hatch.testProxy = new PermissionProxy(hatch);
            } else {
                final PermissionBus bus = new PermissionBus();
                this.host = bus;
                this.proxy = bus.testProxy = new PermissionProxy(bus);
            }
            final IGregTechTileEntity base = (IGregTechTileEntity) Proxy.newProxyInstance(
                DistributedHostSecurityFixture.class.getClassLoader(),
                new Class<?>[] { IGregTechTileEntity.class },
                (instance, method, arguments) -> {
                    final String name = method.getName();
                    if ("getYCoord".equals(name)) return (short) 0;
                    if ("getXCoord".equals(name) || "getZCoord".equals(name)) return 0;
                    switch (name) {
                        case "setMetaTileEntity":
                            this.attached = (MetaTileEntity) arguments[0];
                            return null;
                        case "getMetaTileEntity":
                            return this.attached;
                        case "isDead":
                            return this.dead;
                        case "isUseableByPlayer":
                            return this.gtAccess;
                        case "isClientSide":
                            return false;
                        case "getWorld":
                            return null;
                        case "getOwnerUuid":
                            return OWNER;
                        default:
                            throw new AssertionError("Unexpected GT call: " + method.getName());
                    }
                });
            this.host.machine()
                .setBaseMetaTileEntity(base);
        }
    }

    private static final class PermissionBus extends MTEDistributedMEOutputBus {

        private PermissionProxy testProxy;

        private PermissionBus() {
            super("test.bus.security", 4, new String[0], new ITexture[0][][]);
        }

        @Override
        public AENetworkProxy getProxy() {
            return this.testProxy;
        }
    }

    private static final class PermissionHatch extends MTEDistributedMEOutputHatch {

        private PermissionProxy testProxy;

        private PermissionHatch() {
            super("test.hatch.security", 4, new String[0], new ITexture[0][][]);
        }

        @Override
        public AENetworkProxy getProxy() {
            return this.testProxy;
        }
    }

    private static final class PermissionProxy extends AENetworkProxy {

        private boolean active;
        private boolean permitted;
        private boolean offline;
        private int securityQueries;

        private PermissionProxy(final IGridProxyable host) {
            super(host, "permissionFixture", null, true);
        }

        @Override
        public boolean isActive() {
            return this.active;
        }

        @Override
        public ISecurityGrid getSecurity() throws GridAccessException {
            this.securityQueries++;
            if (this.offline) throw new GridAccessException();
            return (ISecurityGrid) Proxy.newProxyInstance(
                DistributedHostSecurityFixture.class.getClassLoader(),
                new Class<?>[] { ISecurityGrid.class },
                (instance, method, arguments) -> {
                    if (!"hasPermission".equals(method.getName()))
                        throw new AssertionError("Unexpected security call: " + method.getName());
                    assertEquals(SecurityPermissions.BUILD, arguments[1]);
                    return this.permitted;
                });
        }
    }

    private static final class RecordingProxy extends AENetworkProxy {

        private NBTTagCompound loaded;

        private RecordingProxy(final IGridProxyable host) {
            super(host, "distributedAE", null, true);
        }

        @Override
        public void readFromNBT(final NBTTagCompound tag) {
            this.loaded = (NBTTagCompound) tag.copy();
        }

        @Override
        public void writeToNBT(final NBTTagCompound tag) {
            tag.setTag("distributedAE", new NBTTagCompound());
        }
    }
}
