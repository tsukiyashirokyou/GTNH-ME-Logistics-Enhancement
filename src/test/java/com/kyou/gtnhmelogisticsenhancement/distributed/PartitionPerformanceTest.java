package com.kyou.gtnhmelogisticsenhancement.distributed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Locale;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

/** Informational JVM samples of the production store/NBT; no world, AE network or timing assertions. */
public class PartitionPerformanceTest {

    @Test
    public void samplesLargeIdentityStoresWithoutTimingThresholds() throws Exception {
        System.out.printf(
            Locale.ROOT,
            "SYNTHETIC_ENV java=%s vm=%s os=%s arch=%s processors=%d%n",
            System.getProperty("java.version"),
            System.getProperty("java.vm.name"),
            System.getProperty("os.name"),
            System.getProperty("os.arch"),
            Runtime.getRuntime()
                .availableProcessors());
        for (final int count : new int[] { 100, 1000, 5000, 10000 }) {
            final String[] keys = new String[count];
            for (int i = 0; i < count; i++) keys[i] = "K" + i;
            final long buildStart = System.nanoTime();
            final PartitionStore<String> store = new PartitionStore<>(1, 1600);
            final PartitionStore<String>.Transaction input = store.transaction(true);
            for (final String key : keys) assertTrue(input.offer(key, 1));
            input.commit();
            final long buildNanos = System.nanoTime() - buildStart;
            final long snapshotStart = System.nanoTime();
            final List<String> snapshot = store.outputSnapshot();
            final long snapshotNanos = System.nanoTime() - snapshotStart;
            assertEquals(count, snapshot.size());
            final long lookupStart = System.nanoTime();
            long found = 0;
            for (int i = 0; i < 100000; i++) found += store.quantity(keys[i % count]);
            final long lookupNanos = System.nanoTime() - lookupStart;
            assertEquals(100000, found);
            final int offset = Math.max(0, (store.overflowIdentityCount() - 1) / 16 * 16);
            final long pageStart = System.nanoTime();
            assertFalse(
                store.emergencyPage(offset, 16)
                    .isEmpty());
            final long pageNanos = System.nanoTime() - pageStart;
            final long writeStart = System.nanoTime();
            final NBTTagCompound saved = PartitionNbt.write(store, PartitionNbtTest.CODEC);
            final long writeNanos = System.nanoTime() - writeStart;
            final long compressStart = System.nanoTime();
            final byte[] compressed = CompressedStreamTools.compress(saved);
            final long compressNanos = System.nanoTime() - compressStart;
            final long readStart = System.nanoTime();
            final PartitionNbt.Loaded<String> restored = PartitionNbt.read(saved, PartitionNbtTest.CODEC);
            final long readNanos = System.nanoTime() - readStart;
            assertFalse(restored.isolated());
            assertEquals(
                count,
                restored.store()
                    .identityCount());
            assertEquals(
                store.totalQuantity(),
                restored.store()
                    .totalQuantity());
            System.out.printf(
                Locale.ROOT,
                "SYNTHETIC_SAMPLE K=%d build_ns=%d snapshot_ns=%d lookup100k_ns=%d last_page_ns=%d write_ns=%d compress_ns=%d read_ns=%d gzip_bytes=%d%n",
                count,
                buildNanos,
                snapshotNanos,
                lookupNanos,
                pageNanos,
                writeNanos,
                compressNanos,
                readNanos,
                compressed.length);
        }
    }
}
