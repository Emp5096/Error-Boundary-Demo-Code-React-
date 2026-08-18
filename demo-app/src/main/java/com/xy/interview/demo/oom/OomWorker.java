package com.xy.interview.demo.oom;

import com.sun.management.OperatingSystemMXBean;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A deliberately small program launched in an isolated JVM. Each scenario creates
 * a real, bounded failure signal while the Spring Boot process remains available.
 */
public final class OomWorker {

    private static volatile byte[] allocationSink;
    private static volatile long cpuSink;

    private OomWorker() {
    }

    public static void main(String[] args) throws Exception {
        Scenario scenario = Scenario.valueOf(args[0]);
        int chunkBytes = Integer.parseInt(args[1]);
        int intervalMillis = Integer.parseInt(args[2]);
        int intensity = Integer.parseInt(args[3]);
        long durationMillis = Long.parseLong(args[4]);

        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong allocatedBytes = new AtomicLong();
        AtomicReference<OutOfMemoryError> oom = new AtomicReference<>();
        List<Thread> workloadThreads = new ArrayList<>();
        byte[] emergencyReserve = new byte[512 * 1024];

        ThreadPoolExecutor businessPool = new ThreadPoolExecutor(
                4, 4, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(128), namedFactory("business-worker-"),
                new ThreadPoolExecutor.AbortPolicy());

        System.out.printf(Locale.ROOT, "READY|%d|%d%n",
                Runtime.getRuntime().maxMemory(), ProcessHandle.current().pid());
        System.out.println("EVENT|实验子 JVM 已就绪，开始制造故障信号");

        switch (scenario) {
            case HEAP_OOM -> workloadThreads.add(startHeapRetention(
                    running, allocatedBytes, oom, chunkBytes, intervalMillis));
            case CPU_HIGH -> workloadThreads.addAll(startCpuLoad(running, intensity));
            case GC_THRASH -> workloadThreads.add(startGcPressure(running, allocatedBytes, chunkBytes));
            case THREAD_POOL -> saturateBusinessPool(businessPool, running, intensity);
        }

        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + durationMillis;
        OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        long previousWallNanos = System.nanoTime();
        long previousCpuNanos = os.getProcessCpuTime();

        while (running.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
            long nowNanos = System.nanoTime();
            long cpuNanos = os.getProcessCpuTime();
            long wallDelta = Math.max(1L, nowNanos - previousWallNanos);
            double cpuPercent = Math.max(0d, Math.min(999d,
                    (cpuNanos - previousCpuNanos) * 100d / wallDelta));
            previousWallNanos = nowNanos;
            previousCpuNanos = cpuNanos;

            long elapsed = System.currentTimeMillis() - startedAt;
            long usedHeap = memory.getHeapMemoryUsage().getUsed();
            long maxHeap = memory.getHeapMemoryUsage().getMax();
            long gcCollections = gcCollections();
            long gcTimeMillis = gcTimeMillis();
            long probeRt = measureProbeRt(businessPool);

            System.out.printf(Locale.ROOT,
                    "SAMPLE|%d|%d|%d|%d|%.1f|%d|%d|%d|%d|%d|%d%n",
                    elapsed, usedHeap, maxHeap, allocatedBytes.get(), cpuPercent,
                    gcCollections, gcTimeMillis, threads.getThreadCount(),
                    businessPool.getActiveCount(), businessPool.getQueue().size(), probeRt);

            if (oom.get() != null) {
                running.set(false);
            }
        }

        running.set(false);
        for (Thread thread : workloadThreads) {
            thread.interrupt();
        }
        businessPool.shutdownNow();

        if (oom.get() != null) {
            OutOfMemoryError error = oom.get();
            emergencyReserve = null;
            System.gc();
            String message = error.getMessage() == null ? "Java heap space" : error.getMessage();
            System.out.printf(Locale.ROOT, "OOM|%s|%s|%d|%d%n",
                    error.getClass().getName(), sanitize(message), allocatedBytes.get(),
                    System.currentTimeMillis() - startedAt);
            System.out.flush();
            System.exit(100);
        }

        emergencyReserve = null;
        System.out.printf(Locale.ROOT, "DONE|%d%n", System.currentTimeMillis() - startedAt);
    }

    private static Thread startHeapRetention(
            AtomicBoolean running,
            AtomicLong allocatedBytes,
            AtomicReference<OutOfMemoryError> oom,
            int chunkBytes,
            int intervalMillis
    ) {
        Thread thread = new Thread(() -> {
            List<byte[]> retained = new ArrayList<>();
            try {
                while (running.get()) {
                    retained.add(new byte[chunkBytes]);
                    allocatedBytes.addAndGet(chunkBytes);
                    if (intervalMillis > 0) {
                        Thread.sleep(intervalMillis);
                    }
                }
            } catch (OutOfMemoryError error) {
                oom.compareAndSet(null, error);
                retained.clear();
                running.set(false);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "heap-retainer");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static List<Thread> startCpuLoad(AtomicBoolean running, int intensity) {
        List<Thread> workers = new ArrayList<>();
        for (int index = 1; index <= intensity; index++) {
            Thread thread = new Thread(() -> {
                long value = 0x9e3779b97f4a7c15L;
                while (running.get()) {
                    value ^= value << 13;
                    value ^= value >>> 7;
                    value ^= value << 17;
                    cpuSink = value;
                }
            }, "cpu-spin-" + index);
            thread.setDaemon(true);
            thread.start();
            workers.add(thread);
        }
        return workers;
    }

    private static Thread startGcPressure(
            AtomicBoolean running,
            AtomicLong allocatedBytes,
            int chunkBytes
    ) {
        int safeChunk = Math.max(64 * 1024, Math.min(chunkBytes, 1024 * 1024));
        Thread thread = new Thread(() -> {
            while (running.get()) {
                byte[] block = new byte[safeChunk];
                block[0] = (byte) allocatedBytes.incrementAndGet();
                allocationSink = block;
                allocatedBytes.addAndGet(safeChunk - 1L);
            }
        }, "short-lived-allocator");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void saturateBusinessPool(
            ThreadPoolExecutor businessPool,
            AtomicBoolean running,
            int intensity
    ) {
        for (int index = 0; index < businessPool.getMaximumPoolSize(); index++) {
            businessPool.submit(() -> {
                while (running.get()) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            });
        }
        int queuedTasks = Math.min(96, 24 * intensity);
        for (int index = 0; index < queuedTasks; index++) {
            businessPool.submit(() -> {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            });
        }
    }

    private static long measureProbeRt(ThreadPoolExecutor pool) {
        long submittedAt = System.nanoTime();
        Future<Long> future = null;
        try {
            future = pool.submit(System::nanoTime);
            long startedAt = future.get(1500, TimeUnit.MILLISECONDS);
            return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(startedAt - submittedAt));
        } catch (TimeoutException error) {
            if (future != null) {
                future.cancel(false);
                pool.remove((Runnable) future);
            }
            return 1500L;
        } catch (RejectedExecutionException error) {
            return 1500L;
        } catch (Exception error) {
            return 1500L;
        }
    }

    private static long gcCollections() {
        long total = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += Math.max(0L, bean.getCollectionCount());
        }
        return total;
    }

    private static long gcTimeMillis() {
        long total = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += Math.max(0L, bean.getCollectionTime());
        }
        return total;
    }

    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static String sanitize(String value) {
        return value.replace('|', '/').replace('\r', ' ').replace('\n', ' ');
    }

    private enum Scenario {
        HEAP_OOM,
        CPU_HIGH,
        GC_THRASH,
        THREAD_POOL
    }
}
