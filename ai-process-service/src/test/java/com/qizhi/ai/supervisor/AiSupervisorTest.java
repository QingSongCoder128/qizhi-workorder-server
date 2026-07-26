package com.qizhi.ai.supervisor;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.qizhi.ai.config.AiRuntimeConfig;
import com.qizhi.ai.config.AiRuntimeConfigService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiSupervisorTest {

    @Test
    void shouldNeverExceedConfiguredConcurrency() throws Exception {
        AiRuntimeConfigService configService = mock(AiRuntimeConfigService.class);
        when(configService.get()).thenReturn(new AiRuntimeConfig(
                "https://example.invalid", "configured-key", "test-model",
                0.1, 100, 1000, 5, 2, 10,
                List.of("CATEGORY", "RATING", "PRE_AUDIT")));
        AiSupervisor supervisor = new AiSupervisor(configService);
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        for (int i = 0; i < 50; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                return supervisor.tryAcquire();
            }));
        }
        start.countDown();

        int acquired = 0;
        for (Future<Boolean> future : futures) {
            if (future.get()) {
                acquired++;
            }
        }
        executor.shutdownNow();

        assertThat(acquired).isEqualTo(5);
        assertThat(supervisor.getRunningCount()).isEqualTo(5);
        for (int i = 0; i < acquired; i++) {
            supervisor.release();
        }
        supervisor.release();
        assertThat(supervisor.getRunningCount()).isZero();
    }
}
