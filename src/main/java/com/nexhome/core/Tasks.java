package com.nexhome.core;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 全局轻量任务调度器。
 * <p>
 * 基于 JDK 内置 {@link ScheduledExecutorService}，不引入 Quartz 等重型框架。
 * DDNS 定时同步、证书自动续期检查、日志清理均在此调度。
 */
public final class Tasks {

    private static final ScheduledExecutorService SCHED =
            Executors.newScheduledThreadPool(3, r -> {
                Thread t = new Thread(r, "nexhome-scheduler");
                t.setDaemon(true);
                return t;
            });

    private Tasks() {
    }

    /** 周期性任务 */
    public static ScheduledFuture<?> every(long initialDelaySec, long periodSec, Runnable job) {
        return SCHED.scheduleAtFixedRate(safe(job), initialDelaySec, periodSec, TimeUnit.SECONDS);
    }

    /** 延迟一次性任务 */
    public static ScheduledFuture<?> delay(long delaySec, Runnable job) {
        return SCHED.schedule(safe(job), delaySec, TimeUnit.SECONDS);
    }

    /** 异步执行一次（不阻塞 HTTP 线程） */
    public static void run(Runnable job) {
        SCHED.execute(safe(job));
    }

    public static void shutdown() {
        SCHED.shutdownNow();
    }

    /** 包装任务，异常只记录日志，避免周期任务因异常终止 */
    private static Runnable safe(Runnable job) {
        return () -> {
            try {
                job.run();
            } catch (Throwable t) {
                Logs.error(Logs.SYS, "调度任务异常: " + t);
            }
        };
    }
}
