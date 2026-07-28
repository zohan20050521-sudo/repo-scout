package io.github.chada010.reposcout.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 索引在单独的单线程有限队列中执行，避免 embedding 并发耗尽 VPS 资源。 */
@Configuration
public class IndexingExecutorConfig {

    @Bean(name = "indexTaskExecutor")
    public ThreadPoolTaskExecutor indexTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(8);
        executor.setThreadNamePrefix("repo-index-");
        executor.initialize();
        return executor;
    }
}
