package com.github.jnthnclt.os.lab.core;

import com.github.jnthnclt.os.lab.base.BolBuffer;
import com.github.jnthnclt.os.lab.base.UIO;
import com.github.jnthnclt.os.lab.collections.bah.LRUConcurrentBAHLinkedHash;
import com.github.jnthnclt.os.lab.core.api.MemoryRawEntryFormat;
import com.github.jnthnclt.os.lab.api.ValueIndex;
import com.github.jnthnclt.os.lab.core.api.ValueIndexConfig;
import com.github.jnthnclt.os.lab.core.api.rawhide.LABRawhide;
import com.github.jnthnclt.os.lab.core.guts.Leaps;
import com.github.jnthnclt.os.lab.core.guts.StripingBolBufferLocks;
import com.google.common.io.Files;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.testng.annotations.Test;

/**
 *
 * @author jonathan.colt
 */
public class LABEnvironmentConcurrenyNGTest {

    @Test(enabled = true)
    public void testConcurrencyMethod() throws Exception {

        File root = Files.createTempDir();
        LRUConcurrentBAHLinkedHash<Leaps> leapsCache = LABEnvironment.buildLeapsCache(100, 8);
        AtomicLong globalHeapCostInBytes = new AtomicLong();
        LABStats stats = new LABStats(globalHeapCostInBytes);
        LABHeapPressure labHeapPressure = new LABHeapPressure(stats,
            LABEnvironment.buildLABHeapSchedulerThreadPool(1),
            "default",
            1024 * 1024 * 5,
            1024 * 1024 * 10,
            globalHeapCostInBytes,
            LABHeapPressure.FreeHeapStrategy.mostBytesFirst);
        LABEnvironment env = new LABEnvironment(stats,
            null,
            LABEnvironment.buildLABSchedulerThreadPool(1),
            LABEnvironment.buildLABCompactorThreadPool(4),
            LABEnvironment.buildLABDestroyThreadPool(1),
            null,
            root,
            labHeapPressure,
            4,
            10,
            leapsCache,
            new StripingBolBufferLocks(1024),
            true,
            false);

        concurentTest(env);
    }

    @Test(enabled = true)
    public void testConcurrencyWithMemMapMethod() throws Exception {

        File root = Files.createTempDir();
        LRUConcurrentBAHLinkedHash<Leaps> leapsCache = LABEnvironment.buildLeapsCache(100, 8);
        AtomicLong globalHeapCostInBytes = new AtomicLong();
        LABStats stats = new LABStats(globalHeapCostInBytes);
        LABHeapPressure labHeapPressure = new LABHeapPressure(stats,
            LABEnvironment.buildLABHeapSchedulerThreadPool(1),
            "default",
            1024 * 1024 * 5,
            1024 * 1024 * 10,
            globalHeapCostInBytes,
            LABHeapPressure.FreeHeapStrategy.mostBytesFirst);
        LABEnvironment env = new LABEnvironment(stats,
            null,
            LABEnvironment.buildLABSchedulerThreadPool(1),
            LABEnvironment.buildLABCompactorThreadPool(4),
            LABEnvironment.buildLABDestroyThreadPool(1),
            null,
            root,
            labHeapPressure,
            4,
            10,
            leapsCache,
            new StripingBolBufferLocks(1024),
            true,
            false);

        concurentTest(env);
    }

    private void concurentTest(LABEnvironment env) throws Exception {

        try {
            int writerCount = 16;
            int readerCount = 16;

            AtomicLong hits = new AtomicLong();
            AtomicLong version = new AtomicLong();
            AtomicLong value = new AtomicLong();
            AtomicLong count = new AtomicLong();

            int totalCardinality = 100_000_000;
            int commitCount = 10;
            int batchSize = 1000;
            boolean fsync = true;

            ExecutorService writers = Executors.newFixedThreadPool(writerCount, new ThreadFactoryBuilder().setNameFormat("writers-%d").build());
            ExecutorService readers = Executors.newFixedThreadPool(readerCount, new ThreadFactoryBuilder().setNameFormat("readers-%d").build());

            Random rand = new Random(12345);
            ValueIndexConfig valueIndexConfig = new ValueIndexConfig("foo",
                4096,
                1000,
                10 * 1024 * 1024,
                0,
                0,
                "deprecated",
                LABRawhide.NAME,
                MemoryRawEntryFormat.NAME,
                2,
                TestUtils.indexType,
                0.75d,
                false,
                Long.MAX_VALUE);
            ValueIndex index = env.open(valueIndexConfig);

            AtomicLong running = new AtomicLong();
            List<Future> writerFutures = new ArrayList<>();
            for (int i = 0; i < writerCount; i++) {
                running.incrementAndGet();
                writerFutures.add(writers.submit(() -> {
                    try {
                        BolBuffer rawEntryBuffer = new BolBuffer();
                        BolBuffer keyBuffer = new BolBuffer();
                        for (int c = 0; c < commitCount; c++) {
                            index.append((stream) -> {
                                for (int b = 0; b < batchSize; b++) {
                                    count.incrementAndGet();
                                    stream.stream(-1,
                                        UIO.longBytes(rand.nextInt(totalCardinality), new byte[8], 0),
                                        System.currentTimeMillis(),
                                        rand.nextBoolean(),
                                        version.incrementAndGet(),
                                        UIO.longBytes(value.incrementAndGet(), new byte[8], 0));
                                }
                                return true;
                            }, fsync, rawEntryBuffer, keyBuffer);
                            index.commit(fsync, true);
                            //System.out.println((c + 1) + " out of " + commitCount + " gets:" + hits.get() + " debt:" + index.debt() + ".");
                        }
                        return null;
                    } catch (Exception x) {
                        x.printStackTrace();
                        throw x;
                    } finally {
                        running.decrementAndGet();
                    }
                }));
            }

            List<Future> readerFutures = new ArrayList<>();
            for (int r = 0; r < readerCount; r++) {
                int readerId = r;
                readerFutures.add(readers.submit(() -> {
                    try {

                        while (running.get() > 0) {
                            index.get(
                                (keyStream) -> {
                                    byte[] key = UIO.longBytes(rand.nextInt(1_000_000), new byte[8], 0);
                                    keyStream.key(0, key, 0, key.length);
                                    return true;
                                },
                                (index1, key, timestamp, tombstoned, version1, value1) -> {
                                    hits.incrementAndGet();
                                    return true;
                                }, true);
                        }
                        //System.out.println("Reader (" + readerId + ") finished.");
                        return null;
                    } catch (Exception x) {
                        x.printStackTrace();
                        throw x;
                    }
                }));
            }

            for (Future f : writerFutures) {
                f.get();
            }

            for (Future f : readerFutures) {
                f.get();
            }

            writers.shutdown();
            readers.shutdown();
            //System.out.println("ALL DONE");
            env.shutdown();

        } catch (Throwable x) {
            x.printStackTrace();
            //System.out.println("Sleeping");
            Thread.sleep(Long.MAX_VALUE);
        }
    }

}
