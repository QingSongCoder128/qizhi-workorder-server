package com.qizhi.common.core.result;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class RTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void shouldBuildStandardResponseWithTraceAndTimestamp() {
        MDC.put("traceId", "trace-contract-test");

        R<String> response = R.ok("payload");

        assertThat(response.getCode()).isEqualTo(200);
        assertThat(response.getMsg()).isEqualTo("success");
        assertThat(response.getData()).isEqualTo("payload");
        assertThat(response.getTraceId()).isEqualTo("trace-contract-test");
        assertThat(response.getTimestamp()).isNotBlank();
    }

    @Test
    void shouldKeepBusinessErrorCode() {
        R<Void> response = R.conflict("重复提交");

        assertThat(response.getCode()).isEqualTo(409);
        assertThat(response.getMsg()).isEqualTo("重复提交");
        assertThat(response.getTimestamp()).isNotBlank();
    }
}
