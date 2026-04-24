package com.voyu.agent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {

    @Bean("agentExecutor")
    public Executor agentExecutor(@Value("${voyu.agent.max-parallelism:4}") int maxParallelism) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(4, maxParallelism));
        executor.setMaxPoolSize(Math.max(8, maxParallelism * 2));
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("voyu-agent-");
        executor.initialize();
        return executor;
    }

    @Bean("historyExecutor")
    public Executor historyExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("voyu-history-");
        executor.initialize();
        return executor;
    }
}
