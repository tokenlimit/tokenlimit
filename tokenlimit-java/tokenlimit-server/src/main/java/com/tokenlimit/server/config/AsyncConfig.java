package com.tokenlimit.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 异步任务配置.
 * <p>开启 {@link EnableAsync} 并提供基于虚拟线程的任务执行器，
 * 用于异步落库 UsageLog、异步更新 API Key 最后使用时间等 IO 密集任务。</p>
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 默认异步执行器.
     * <p>使用线程池执行器，配置核心/最大线程数与队列容量，
     * 适配 IO 密集型异步任务（异步落库、异步更新指标）。</p>
     */
    @Bean(name = "asyncTaskExecutor")
    public AsyncTaskExecutor asyncTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("tokenlimit-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
