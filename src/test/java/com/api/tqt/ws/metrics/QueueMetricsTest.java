package com.api.tqt.ws.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class QueueMetricsTest {

    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");

    private SimpleMeterRegistry registry;
    private QueueMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new QueueMetrics(registry, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void 입장_허용_수만큼_카운터가_증가한다() {
        metrics.recordAdmitted(5);
        metrics.recordAdmitted(0);
        metrics.recordAdmitted(2);

        assertThat(registry.get(QueueMetrics.ADMITTED).counter().count()).isEqualTo(7.0);
    }

    @Test
    void 대기_인원_게이지는_마지막으로_측정한_값을_보여준다() {
        metrics.updateWaiting(120);
        metrics.updateWaiting(42);

        assertThat(registry.get(QueueMetrics.WAITING).gauge().value()).isEqualTo(42.0);
    }

    @Test
    void 마지막_성공_시각은_성공을_기록할_때만_갱신된다() {
        assertThat(registry.get(QueueMetrics.SCHEDULER_LAST_SUCCESS).gauge().value()).isZero();

        metrics.markSchedulerSuccess();

        assertThat(registry.get(QueueMetrics.SCHEDULER_LAST_SUCCESS).gauge().value())
                .isEqualTo((double) NOW.getEpochSecond());
    }

    @Test
    void 진입_거절은_사유와_상태코드_태그로_나뉘어_집계된다() {
        metrics.recordMissingToken();
        metrics.recordInvalidToken();
        metrics.recordInvalidToken();

        assertThat(registry.get(QueueMetrics.ENTRY_REJECTED)
                .tags("reason", "missing", "status", "401").counter().count()).isEqualTo(1.0);
        assertThat(registry.get(QueueMetrics.ENTRY_REJECTED)
                .tags("reason", "invalid", "status", "403").counter().count()).isEqualTo(2.0);
    }
}
