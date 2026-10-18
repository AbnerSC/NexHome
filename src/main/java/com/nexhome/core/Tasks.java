package com.nexhome.core;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 全局轻量任务调度器。
 * <p>
 * 基于 JDK 内置 {@link ScheduledExecutorService}，不引入 Quartz 等重型框架。
 * DDNS 定时同步、证书自动续期检查、日志清理均在此调度。
 * <p>
 * 分层设计：调度池（平台线程）只负责定时触发，任务体提交到虚拟线程执行——
 * 证书签发、DDNS 同步等分钟级阻塞 IO 不再挤占调度线程，避免饿死同池的其他周期任务
 * （MonitorService 的探测早已采用同样的线程隔离策略）。
 * <p>
 * 同一 {@link #every} 注册的周期任务保证串行：上一次执行未结束时本周期跳过，
 * 语义与原先"任务占着调度线程执行"一致，不会出现同一任务并发重入。
 */
public final class Tasks {

    /** 定时触发池：只做时间触发与防重入检查，不执行任务体 */
    private static final ScheduledExecutorService SCHED =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "nexhome-scheduler");
                t.setDaemon(true);
                return t;
            });

    /** 任务执行池：每次任务一个虚拟线程，即用即弃（虚拟线程不池化） */
    private static final ExecutorService JOBS =
            Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("nexhome-job-", 0).factory());

    private Tasks() {
    }

    /**
     * 周期性任务。
     * <p>
     * 同一任务串行执行：上一周期仍在虚拟线程中执行时，本周期直接跳过（不排队），
     * 避免 STUN 巡检这类带内部状态机的周期任务并发重入，也避免慢任务积压雪崩。
     */
    public static ScheduledFuture<?> every(long initialDelaySec, long periodSec, Runnable job) {
        AtomicBoolean running = new AtomicBoolean();
        return SCHED.scheduleAtFixedRate(() -> {
            if (running.compareAndSet(false, true)) {
                JOBS.execute(() -> {
                    try {
                        job.run();
                    } catch (Throwable t) {
                        Logs.error(Logs.SYS, "调度任务异常: " + t);
                    } finally {
                        running.set(false);
                    }
                });
            }
        }, initialDelaySec, periodSec, TimeUnit.SECONDS);
    }

    /** 延迟一次性任务 */
    public static ScheduledFuture<?> delay(long delaySec, Runnable job) {
        return SCHED.schedule(() -> JOBS.execute(safe(job)), delaySec, TimeUnit.SECONDS);
    }

    /** 异步执行一次（不阻塞 HTTP 线程） */
    public static void run(Runnable job) {
        JOBS.execute(safe(job));
    }

    public static void shutdown() {
        SCHED.shutdownNow();
        JOBS.shutdownNow();
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
