package com.github.jnthnclt.os.lab.core.stress;

import com.github.jnthnclt.os.lab.base.BolBuffer;
import com.github.jnthnclt.os.lab.base.UIO;
import com.github.jnthnclt.os.lab.collections.bah.LRUConcurrentBAHLinkedHash;
import com.github.jnthnclt.os.lab.core.LABEnvironment;
import com.github.jnthnclt.os.lab.core.LABHeapPressure;
import com.github.jnthnclt.os.lab.core.LABStats;
import com.github.jnthnclt.os.lab.api.Keys.KeyStream;
import com.github.jnthnclt.os.lab.core.api.MemoryRawEntryFormat;
import com.github.jnthnclt.os.lab.api.ScanKeys;
import com.github.jnthnclt.os.lab.api.ValueIndex;
import com.github.jnthnclt.os.lab.core.api.ValueIndexConfig;
import com.github.jnthnclt.os.lab.core.api.rawhide.LABFixedWidthKeyFixedWidthValueRawhide;
import com.github.jnthnclt.os.lab.core.api.rawhide.LABFixedWidthKeyRawhide;
import com.github.jnthnclt.os.lab.core.api.rawhide.LABRawhide;
import com.github.jnthnclt.os.lab.core.guts.LABHashIndexType;
import com.github.jnthnclt.os.lab.core.guts.Leaps;
import com.github.jnthnclt.os.lab.core.guts.StripingBolBufferLocks;
import com.google.common.io.Files;
import java.io.File;
import java.text.NumberFormat;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * @author jonathan.colt
 */
public class LABPointStress {

    @DataProvider(name = "rawHideTypes")
    public static Object[][] indexTypes() {
        return new Object[][] {
            { "8x8fixedWidthRawhide" },
            { "8fixedWidthRawhide" },
            { LABRawhide.NAME}
        };
    }

    @Test(dataProvider = "rawHideTypes")
    public void stressWritesTest(String rawhideName) throws Exception {

        LABHashIndexType indexType = LABHashIndexType.linearProbe;
        double hashIndexLoadFactor = 2d;
        File root = Files.createTempDir();
        System.out.println(root.getAbsolutePath());
        AtomicLong globalHeapCostInBytes = new AtomicLong();
        LABStats stats = new LABStats(globalHeapCostInBytes);
        ValueIndex index = createIndex(root,
            indexType,
            hashIndexLoadFactor,
            stats,
            globalHeapCostInBytes,
            rawhideName);

        long totalCardinality = 1_000_000;
        int writeCount = 100_000;

        write(index, 0, totalCardinality, writeCount); // removes

        System.out.println("COMMIT ALL");
        List<Future<Object>> futures = index.commit(true, true);
        for (Future<Object> future : (futures != null) ? futures : Collections.<Future<Object>>emptyList()) {
            future.get();
        }
        System.out.println("COMMITED ALL");

        System.out.println("COMPACT ALL");
        futures = index.compact(true, 0, 0, true);
        for (Future<Object> future : (futures != null) ? futures : Collections.<Future<Object>>emptyList()) {
            future.get();
        }
        System.out.println("COMPACTED ALL");

        System.gc();
        System.runFinalization();

        System.out.println("-------------------------------");


        for (int i = 1; i < totalCardinality; i = i * 2) {
            System.out.println("minStep:" + i + " -------------------------------");
            read(index, totalCardinality, i);
            skipread(index, totalCardinality, i);
            bruteread(index, totalCardinality, i);
        }
    }

    private void write(
        ValueIndex index,
        long offset,
        long totalCardinality,
        int writeCount) throws Exception {

        AtomicLong version = new AtomicLong();
        AtomicLong value = new AtomicLong();
        AtomicLong count = new AtomicLong();

        Random rand = new Random(12345);

        NumberFormat numberInstance = NumberFormat.getNumberInstance();

        byte[] keyBytes = new byte[8];
        byte[] valuesBytes = new byte[8];
        BolBuffer rawEntryBuffer = new BolBuffer();
        BolBuffer keyBuffer = new BolBuffer();
        int accumRate = 0;
        int loops = 0;
        while (writeCount > 0) {
            long start = System.currentTimeMillis();
            int ef_writeCount = writeCount;
            index.append((stream) -> {
                for (int i = 0; i < Math.min(ef_writeCount, 100_000); i++) {
                    count.incrementAndGet();
                    long key = offset + (long) (rand.nextDouble() * totalCardinality);
                    stream.stream(-1,
                        UIO.longBytes(key, keyBytes, 0),
                        System.currentTimeMillis(),
                        false,
                        version.incrementAndGet(),
                        UIO.longBytes(value.incrementAndGet(), valuesBytes, 0));
                }
                return true;
            }, true, rawEntryBuffer, keyBuffer);
            writeCount -= 100_000;
            long elapse = System.currentTimeMillis() - start;
            int rate = (int) ((100_000 / (double) elapse) * 1000);
            accumRate += rate;
            System.out.println("write:" + numberInstance.format(count.get()) + " in " + elapse + "millis rate:" + rate);
            loops++;
        }
        System.out.println("AvgRate:" + (accumRate/(double)loops));


    }

    private void read(
        ValueIndex index,
        long totalCardinality,
        int maxStep) throws Exception {

        Random rand = new Random(12345);

        NumberFormat numberInstance = NumberFormat.getNumberInstance();

        byte[] keyBytes = new byte[8];
        long start = System.currentTimeMillis();


        AtomicLong misses = new AtomicLong();
        AtomicLong hits = new AtomicLong();
        long k = 0;
        while (k < totalCardinality) {
            k += 1 + rand.nextInt(maxStep);
            long ef_k = k;
            index.get((KeyStream keyStream) -> {
                UIO.longBytes(ef_k, keyBytes, 0);
                keyStream.key(0, keyBytes, 0, keyBytes.length);
                return true;
            }, (index1, key, timestamp, tombstoned, version1, value1) -> {
                if (value1 != null && !tombstoned) {
                    hits.incrementAndGet();
                } else {
                    misses.incrementAndGet();
                }
                return true;
            }, true);
        }
        long count = misses.get() + hits.get();
        long readElapse = (System.currentTimeMillis() - start);

        System.out.println(
            " pointRead:" + numberInstance.format(
                count) + " in " + readElapse + "millis rate:" + numberInstance.format(
                (int) ((count / (double) readElapse) * 1000)) + " miss:" + misses.get()
                + " hits:" + hits.get());


    }

    private void skipread(
        ValueIndex index,
        long totalCardinality,
        int maxStep) throws Exception {

        Random rand = new Random(12345);
        NumberFormat numberInstance = NumberFormat.getNumberInstance();
        byte[] keyBytes = new byte[8];
        long start = System.currentTimeMillis();
        AtomicLong misses = new AtomicLong();
        AtomicLong hits = new AtomicLong();


        ScanKeys scanKeys = new ScanKeys() {
            int k = 0;

            @Override
            public BolBuffer nextKey() throws Exception {
                if (k < totalCardinality) {
                    k += 1 + rand.nextInt(maxStep);
                    return new BolBuffer(UIO.longBytes(k));
                }
                return null;
            }
        };


        index.rowScan(scanKeys, (index1, key, timestamp, tombstoned, version, value1) -> {
            if (value1 != null && !tombstoned) {
                hits.incrementAndGet();
            } else {
                misses.incrementAndGet();
            }
            return true;
        }, true);

        long count = misses.get() + hits.get();
        long readElapse = (System.currentTimeMillis() - start);

        System.out.println(
            " skipread:" + numberInstance.format(
                count) + " in " + readElapse + "millis rate:" + numberInstance.format(
                (int) ((count / (double) readElapse) * 1000)) + " miss:" + misses.get() +
                " hits:" + hits.get());


    }

    private void bruteread(
        ValueIndex index,
        long totalCardinality,
        int maxStep) throws Exception {

        Random rand = new Random(12345);
        NumberFormat numberInstance = NumberFormat.getNumberInstance();
        long start = System.currentTimeMillis();
        AtomicLong misses = new AtomicLong();
        AtomicLong hits = new AtomicLong();


        AtomicLong k = new AtomicLong();
        index.rowScan((index1, key, timestamp, tombstoned, version, value1) -> {


            long sk = key.getLong(0);
            if (sk < k.get()) {
                misses.incrementAndGet();
            } else if (sk > k.get()) {
                while (sk > k.get()) {
                    misses.incrementAndGet();
                    k.addAndGet(1 + rand.nextInt(maxStep));
                }

            }

            if (sk == k.get()) {
                hits.incrementAndGet();
                k.addAndGet(1 + rand.nextInt(maxStep));
            }
            return true;
        }, true);

        long count = misses.get() + hits.get();
        long readElapse = (System.currentTimeMillis() - start);

        System.out.println(
            " bruteread:" + numberInstance.format(
                count) + " in " + readElapse + "millis rate:" + numberInstance.format(
                (int) ((count / (double) readElapse) * 1000)) + " miss:" + misses.get() +
                " hits:" + hits.get());


    }


    private ValueIndex createIndex(File root,
        LABHashIndexType indexType,
        double hashIndexLoadFactor,
        LABStats stats,
        AtomicLong globalHeapCostInBytes,
        String rawhideName) throws Exception {

        System.out.println("Created root " + root);
        LRUConcurrentBAHLinkedHash<Leaps> leapsCache = LABEnvironment.buildLeapsCache(100_000, 8);
        LABHeapPressure labHeapPressure = new LABHeapPressure(stats,
            LABEnvironment.buildLABHeapSchedulerThreadPool(1),
            "default",
            1024 * 1024 * 10,
            1024 * 1024 * 20,
            globalHeapCostInBytes,
            LABHeapPressure.FreeHeapStrategy.mostBytesFirst);

        LABEnvironment env = new LABEnvironment(stats,
            null,
            LABEnvironment.buildLABSchedulerThreadPool(1),
            LABEnvironment.buildLABCompactorThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors() - 1)), // compact
            LABEnvironment.buildLABDestroyThreadPool(1), // destroy
            null,
            root, // rootFile
            labHeapPressure,
            4, // minMergeDebt
            8, // maxMergeDebt
            leapsCache,
            new StripingBolBufferLocks(1024),
            true,
            false);

        env.register("8x8fixedWidthRawhide", new LABFixedWidthKeyFixedWidthValueRawhide(8, 8));
        env.register("8fixedWidthRawhide", new LABFixedWidthKeyRawhide(8));

        System.out.println("Created env");
        ValueIndex index = env.open(new ValueIndexConfig("foo",
            1024 * 4, // entriesBetweenLeaps
            1024 * 1024 * 100, // maxHeapPressureInBytes
            -1, // splitWhenKeysTotalExceedsNBytes
            -1, // splitWhenValuesTotalExceedsNBytes
            1024 * 1024 * 64, // splitWhenValuesAndKeysTotalExceedsNBytes
            "deprecated",
            rawhideName, //new LABRawhide(),
            MemoryRawEntryFormat.NAME,
            27,
            indexType,
            hashIndexLoadFactor,
            true,
            Long.MAX_VALUE));
        return index;
    }

}
