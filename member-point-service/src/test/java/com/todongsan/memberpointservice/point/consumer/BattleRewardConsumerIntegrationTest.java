package com.todongsan.memberpointservice.point.consumer;

import com.todongsan.memberpointservice.point.entity.PointHistory;
import com.todongsan.memberpointservice.point.entity.PointTransactionStatus;
import com.todongsan.memberpointservice.point.repository.PointHistoryRepository;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Kafka 컨슈머 통합 테스트.
 *
 * Testcontainers(MySQL + Kafka)에서:
 * 1. 이벤트 발행 → 컨슈머 소비 → earn() 처리 → point_history 생성 확인
 * 2. 같은 이벤트 재발행 → 중복 소비 차단 (멱등성, DB 1건 유지)
 * 3. 탈퇴 회원 이벤트 → 비즈니스 거절 ack 종결 (DLT 미유입)
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("integration")
class BattleRewardConsumerIntegrationTest {

    private static final String TOPIC = "battle.reward.requested";

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
    @Autowired DataSource dataSource;

    private String buildEventJson(String eventId, Long memberId, String type,
                                   double amount, String idempotencyKey) {
        return """
                {
                  "eventId": "%s",
                  "eventType": "BATTLE_REWARD_REQUESTED",
                  "schemaVersion": 1,
                  "occurredAt": "2026-08-25T10:00:00",
                  "aggregateType": "BATTLE",
                  "aggregateId": 1,
                  "payload": {
                    "memberId": %d,
                    "type": "%s",
                    "amount": %.2f,
                    "referenceType": "BATTLE",
                    "referenceId": 1,
                    "reason": "통합 테스트 보상",
                    "idempotencyKey": "%s"
                  }
                }
                """.formatted(eventId, memberId, type, amount, idempotencyKey);
    }

    private void insertTestMember(Long id, String nickname, String oauthId) throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO member (id, nickname, point_balance, role, oauth_provider, oauth_id) "
                    + "VALUES (" + id + ", '" + nickname + "', 100.00, 'USER', 'KAKAO', '" + oauthId + "')");
        }
    }

    @Test
    void 이벤트_발행_소비_earn_처리_확인() throws Exception {
        insertTestMember(1L, "kafka-test-user", "kafka-oauth-001");

        String eventId = UUID.randomUUID().toString();
        String idempotencyKey = "kafka-test:earn:001";
        String event = buildEventJson(eventId, 1L, "EARN_VOTE", 10.00, idempotencyKey);

        // when: Kafka에 이벤트 발행
        kafkaTemplate.send(TOPIC, "1", event).get(10, TimeUnit.SECONDS);

        // then: 컨슈머가 소비하여 point_history 생성 (비동기이므로 대기)
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            Optional<PointHistory> history = pointHistoryRepository.findByIdempotencyKey(idempotencyKey);
            assertThat(history).isPresent();
            assertThat(history.get().getStatus()).isEqualTo(PointTransactionStatus.SUCCEEDED);
            assertThat(history.get().getAmount()).isEqualByComparingTo("10.00");
        });

        // 잔액 확인 (100 + 10 = 110)
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            var rs = stmt.executeQuery("SELECT point_balance FROM member WHERE id = 1");
            rs.next();
            assertThat(rs.getBigDecimal("point_balance")).isEqualByComparingTo("110.00");
        }
    }

    @Test
    void 같은_이벤트_재발행_중복_소비_차단() throws Exception {
        insertTestMember(2L, "kafka-dup-user", "kafka-oauth-002");

        String eventId = UUID.randomUUID().toString();
        String idempotencyKey = "kafka-test:earn:dup:001";
        String event = buildEventJson(eventId, 2L, "EARN_VOTE_WIN", 10.00, idempotencyKey);

        // 첫 번째 발행
        kafkaTemplate.send(TOPIC, "2", event).get(10, TimeUnit.SECONDS);

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(pointHistoryRepository.findByIdempotencyKey(idempotencyKey)).isPresent();
        });

        // 두 번째 발행 (중복)
        kafkaTemplate.send(TOPIC, "2", event).get(10, TimeUnit.SECONDS);

        // 잠시 대기 후 DB에 여전히 1건만 존재하는지 확인
        Thread.sleep(3000);
        long count = pointHistoryRepository.findAll().stream()
                .filter(h -> idempotencyKey.equals(h.getIdempotencyKey()))
                .count();
        assertThat(count).as("중복 소비 차단: DB에 1건만 존재").isEqualTo(1);

        // 잔액도 1회만 반영 (100 + 10 = 110, 120이 아님)
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            var rs = stmt.executeQuery("SELECT point_balance FROM member WHERE id = 2");
            rs.next();
            assertThat(rs.getBigDecimal("point_balance")).isEqualByComparingTo("110.00");
        }
    }

    @Test
    void 탈퇴회원_이벤트_비즈니스_거절_ack_종결() throws Exception {
        // memberId=9999는 DB에 없음 → MEMBER_NOT_FOUND → 비즈니스 거절 → ack
        String eventId = UUID.randomUUID().toString();
        String idempotencyKey = "kafka-test:earn:notfound:001";
        String event = buildEventJson(eventId, 9999L, "EARN_VOTE", 10.00, idempotencyKey);

        kafkaTemplate.send(TOPIC, "9999", event).get(10, TimeUnit.SECONDS);

        // 비즈니스 거절은 ack로 종결되므로 point_history가 생기지 않아야 함
        // 3초 대기 후 확인 (DLT로 가지 않고 조용히 종결)
        Thread.sleep(3000);
        Optional<PointHistory> history = pointHistoryRepository.findByIdempotencyKey(idempotencyKey);
        assertThat(history).as("비즈니스 거절은 point_history 미생성").isEmpty();
    }
}
