package com.todongsan.memberpointservice.point.consumer;

import com.todongsan.memberpointservice.outbox.entity.OutboxEvent;
import com.todongsan.memberpointservice.outbox.repository.OutboxEventRepository;
import com.todongsan.memberpointservice.point.entity.PointHistory;
import com.todongsan.memberpointservice.point.entity.PointTransactionStatus;
import com.todongsan.memberpointservice.point.repository.PointHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Market 예측 참여 Saga 통합 테스트 (Member-Point 관점).
 *
 * Testcontainers(MySQL + Kafka)에서:
 * 1. prediction.created → spend 성공 → point.deducted outbox 생성
 * 2. prediction.created → spend 실패(잔액부족) → point.deduction.failed outbox 생성
 * 3. prediction.refund.requested → 환불 → point.refunded outbox 생성 + 원거래 금액 상한 검증
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("integration")
class SagaIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("memberpoint")
            .withUsername("root")
            .withPassword("test")
            .withInitScript("schema-integration.sql");

    @Container
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired PointHistoryRepository pointHistoryRepository;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired DataSource dataSource;

    @BeforeEach
    void cleanUp() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM outbox_event");
            stmt.execute("DELETE FROM point_history");
            stmt.execute("DELETE FROM member");
        }
    }

    private void insertMember(Long id, String nickname, String oauthId, String balance) throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO member (id, nickname, point_balance, role, oauth_provider, oauth_id) "
                    + "VALUES (" + id + ", '" + nickname + "', " + balance + ", 'USER', 'KAKAO', '" + oauthId + "')");
        }
    }

    private String predictionCreatedEvent(String eventId, Long memberId, Long predictionId,
                                           Long marketId, String amount, String idempotencyKey) {
        return """
                {
                  "eventId": "%s",
                  "eventType": "PREDICTION_CREATED",
                  "schemaVersion": 1,
                  "occurredAt": "2026-08-25T10:00:00",
                  "aggregateType": "MARKET_PREDICTION",
                  "aggregateId": %d,
                  "payload": {
                    "memberId": %d,
                    "marketId": %d,
                    "predictionId": %d,
                    "pointAmount": %s,
                    "idempotencyKey": "%s"
                  }
                }
                """.formatted(eventId, predictionId, memberId, marketId, predictionId, amount, idempotencyKey);
    }

    private String refundRequestedEvent(String eventId, Long memberId, Long predictionId,
                                         Long marketId, String amount, String refundKey, String originalKey) {
        return """
                {
                  "eventId": "%s",
                  "eventType": "PREDICTION_REFUND_REQUESTED",
                  "schemaVersion": 1,
                  "occurredAt": "2026-08-25T10:00:00",
                  "aggregateType": "MARKET_PREDICTION",
                  "aggregateId": %d,
                  "payload": {
                    "memberId": %d,
                    "marketId": %d,
                    "predictionId": %d,
                    "amount": %s,
                    "refundIdempotencyKey": "%s",
                    "originalSpendKey": "%s"
                  }
                }
                """.formatted(eventId, predictionId, memberId, marketId, predictionId, amount, refundKey, originalKey);
    }

    @Test
    void prediction_created_spend_성공_point_deducted_outbox_생성() throws Exception {
        insertMember(1L, "saga-user-1", "saga-001", "500.00");

        String spendKey = "MARKET_PREDICTION_SPEND:market:1:member:1:attempt:1";
        String event = predictionCreatedEvent(UUID.randomUUID().toString(), 1L, 100L, 1L, "100.00", spendKey);

        kafkaTemplate.send("prediction.created", "1", event).get(10, TimeUnit.SECONDS);

        // spend 처리 + outbox 생성 대기
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            Optional<PointHistory> history = pointHistoryRepository.findByIdempotencyKey(spendKey);
            assertThat(history).isPresent();
            assertThat(history.get().getStatus()).isEqualTo(PointTransactionStatus.SUCCEEDED);
            assertThat(history.get().getAmount()).isEqualByComparingTo("100.00");

            List<OutboxEvent> outbox = outboxEventRepository.findAll();
            assertThat(outbox).anyMatch(e -> "POINT_DEDUCTED".equals(e.getEventType()));
        });

        // 잔액 확인 (500 - 100 = 400)
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            var rs = stmt.executeQuery("SELECT point_balance FROM member WHERE id = 1");
            rs.next();
            assertThat(rs.getBigDecimal("point_balance")).isEqualByComparingTo("400.00");
        }
    }

    @Test
    void prediction_created_잔액부족_point_deduction_failed_outbox_생성() throws Exception {
        insertMember(2L, "saga-user-2", "saga-002", "10.00"); // 잔액 10P < 차감 100P

        String spendKey = "MARKET_PREDICTION_SPEND:market:1:member:2:attempt:1";
        String event = predictionCreatedEvent(UUID.randomUUID().toString(), 2L, 200L, 1L, "100.00", spendKey);

        kafkaTemplate.send("prediction.created", "2", event).get(10, TimeUnit.SECONDS);

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            // FAILED 이력 생성
            Optional<PointHistory> history = pointHistoryRepository.findByIdempotencyKey(spendKey);
            assertThat(history).isPresent();
            assertThat(history.get().getStatus()).isEqualTo(PointTransactionStatus.FAILED);

            // point.deduction.failed outbox 생성
            List<OutboxEvent> outbox = outboxEventRepository.findAll();
            assertThat(outbox).anyMatch(e -> "POINT_DEDUCTION_FAILED".equals(e.getEventType()));
        });

        // 잔액 변동 없음 (10.00 그대로)
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            var rs = stmt.executeQuery("SELECT point_balance FROM member WHERE id = 2");
            rs.next();
            assertThat(rs.getBigDecimal("point_balance")).isEqualByComparingTo("10.00");
        }
    }

    @Test
    void prediction_refund_환불_성공_원거래_금액_상한_검증() throws Exception {
        insertMember(3L, "saga-user-3", "saga-003", "400.00");

        // 먼저 원거래(spend) 생성
        String spendKey = "MARKET_PREDICTION_SPEND:market:1:member:3:attempt:1";
        String spendEvent = predictionCreatedEvent(UUID.randomUUID().toString(), 3L, 300L, 1L, "100.00", spendKey);
        kafkaTemplate.send("prediction.created", "3", spendEvent).get(10, TimeUnit.SECONDS);

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(pointHistoryRepository.findByIdempotencyKey(spendKey)).isPresent();
        });

        // 환불 요청 (원거래 100P보다 큰 200P 요청 → 100P로 클램프)
        String refundKey = "REFUND-" + spendKey;
        String refundEvent = refundRequestedEvent(
                UUID.randomUUID().toString(), 3L, 300L, 1L, "200.00", refundKey, spendKey);
        kafkaTemplate.send("prediction.refund.requested", "3", refundEvent).get(10, TimeUnit.SECONDS);

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            Optional<PointHistory> refundHistory = pointHistoryRepository.findByIdempotencyKey(refundKey);
            assertThat(refundHistory).isPresent();
            // 원거래 100P 상한으로 클램프되어 100P만 환불
            assertThat(refundHistory.get().getAmount()).isEqualByComparingTo("100.00");
            assertThat(refundHistory.get().getStatus()).isEqualTo(PointTransactionStatus.SUCCEEDED);

            List<OutboxEvent> outbox = outboxEventRepository.findAll();
            assertThat(outbox).anyMatch(e -> "POINT_REFUNDED".equals(e.getEventType()));
        });

        // 잔액: 400(시작) - 100(차감) + 100(환불) = 400
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            var rs = stmt.executeQuery("SELECT point_balance FROM member WHERE id = 3");
            rs.next();
            assertThat(rs.getBigDecimal("point_balance")).isEqualByComparingTo("400.00");
        }
    }
}
