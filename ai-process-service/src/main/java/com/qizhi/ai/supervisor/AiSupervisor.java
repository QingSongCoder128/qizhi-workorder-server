package com.qizhi.ai.supervisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI 任务监管器（并发控制）
 * <p>
 * SRS 需求: AI-01 Supervisor 组件
 * 控制 AI 处理任务的最大并发数，防止 DeepSeek API 被打爆。
 * 使用 AtomicInteger 追踪当前运行中的任务数，
 * 最大并发数从 Nacos 读取（支持热更新）。
 * </p>
 */
@Slf4j
@Component
@RefreshScope
public class AiSupervisor {

    /** 当前运行中的 AI 任务数 */
    private final AtomicInteger runningTasks = new AtomicInteger(0);

    /** 最大并发数（Nacos 配置，默认 5） */
    @Value("${ai.supervisor.max-concurrent:5}")
    private int maxConcurrent;

    /**
     * 尝试获取执行许可
     *
     * @return true=获取成功可以执行, false=超过并发限制
     */
    public boolean tryAcquire() {
        int current = runningTasks.get();
        if (current >= maxConcurrent) {
            log.warn("AI并发已满: running={}, max={}, 任务将排队", current, maxConcurrent);
            return false;
        }
        runningTasks.incrementAndGet();
        log.debug("AI任务许可获取: running={}/{}", runningTasks.get(), maxConcurrent);
        return true;
    }

    /**
     * 释放执行许可（任务完成后调用）
     */
    public void release() {
        int after = runningTasks.decrementAndGet();
        log.debug("AI任务许可释放: running={}/{}", after, maxConcurrent);
    }

    /**
     * 获取当前运行中的任务数
     */
    public int getRunningCount() {
        return runningTasks.get();
    }

    /**
     * 获取最大并发数
     */
    public int getMaxConcurrent() {
        return maxConcurrent;
    }
}
