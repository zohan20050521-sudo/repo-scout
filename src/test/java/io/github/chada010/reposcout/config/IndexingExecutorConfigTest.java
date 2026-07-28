package io.github.chada010.reposcout.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class IndexingExecutorConfigTest {

    @Test
    void indexExecutorIsSingleThreadedWithFiniteQueue() {
        ThreadPoolTaskExecutor executor = new IndexingExecutorConfig().indexTaskExecutor();

        assertThat(executor.getCorePoolSize()).isEqualTo(1);
        assertThat(executor.getMaxPoolSize()).isEqualTo(1);
        assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(8);
        executor.shutdown();
    }
}
