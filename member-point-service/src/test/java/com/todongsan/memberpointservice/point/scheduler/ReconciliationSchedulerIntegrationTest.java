package com.todongsan.memberpointservice.point.scheduler;

import com.todongsan.memberpointservice.point.entity.PointHistory;
import com.todongsan.memberpointservice.point.entity.PointHistoryType;
import com.todongsan.memberpointservice.point.entity.PointReferenceType;
import com.todongsan.memberpointservice.point.entity.PointTransactionStatus;
import com.todongsan.memberpointservice.point.entity.ReconciliationMismatch;
import com.todongsan.memberpointservice.point.repository.PointHistoryRepository;
import com.todongsan.memberpointservice.point.repository.ReconciliationMismatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * ReconciliationScheduler 통합 테스트 (Testcontainers MySQL).
 *
 * (a) 정합 상태 → mismatch 0건
 * (b) SETTLE 이력 과다 삽입 → distributed > spend 불일치 검출
 * (c) Market 정상 응답 → 교차 검증 통과
 * (d) Market 다운(5xx) → 스킵, 예외 전파 없음
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("integration")
class ReconciliationSchedulerIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("memberpoint")
            .withUsername("root")
            .withPassword("test")
            .withInitScript("schema-integration.sql");

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:19092");
        registry.add("external.market-service.url", () -> "http://localhost:19999");
    }

    @Autowired ReconciliationScheduler scheduler;
    @Autowired PointHistoryRepository pointHistoryRepository;
    @Autowired ReconciliationMismatchRepository mismatchRepository;
    @Autowired DataSource dataSource;

    @BeforeEach
    void cleanUp() throws Exception {
        mismatchRepository.deleteAll();
        pointHistoryRepository.deleteAll();
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM member");
            stmt.execute("INSERT INTO member (id, nickname, point_balance, role, oauth_provider, oauth_id) "
                    + "VALUES (1, 'recon-user', 1000.00, 'USER', 'KAKAO', 'recon-001')");
        }
    }

    private void insertHistory(PointHistoryType type, BigDecimal amount, String key) {
        pointHistoryRepository.save(PointHistory.builder()
                .memberId(1L)
                .type(type)
                .amount(amount)
                .balanceSnapshot(BigDecimal.ZERO)
                .referenceType(PointReferenceType.MARKET_PREDICTION)
                .referenceId(1L)
                .idempotencyKey(key)
                .requestHash("hash")
                .status(PointTransactionStatus.SUCCEEDED)
                .build());
    }

    private MockRestServiceServer createMockServer() throws Exception {
        Field field = ReconciliationScheduler.class.getDeclaredField("restTemplate");
        field.setAccessible(true);
        RestTemplate rt = (RestTemplate) field.get(scheduler);
        return MockRestServiceServer.createServer(rt);
    }

    // ─── (a) 정합 상태 → mismatch 0건 ────────────────────────

    @Test
    void 정합_상태에서_불일치_0건() {
        insertHistory(PointHistoryType.SPEND_MARKET, new BigDecimal("100.00"), "recon:spend:1");
        insertHistory(PointHistoryType.SETTLE_MARKET, new BigDecimal("80.00"), "recon:settle:1");
        insertHistory(PointHistoryType.BURN, new BigDecimal("20.00"), "recon:burn:1");

        scheduler.checkSelfLedgerInvariant();

        assertThat(mismatchRepository.findAll()).isEmpty();
    }

    // ─── (b) SETTLE 과다 → 불일치 검출 ───────────────────────

    @Test
    void SETTLE_과다_삽입시_불일치_검출() {
        insertHistory(PointHistoryType.SPEND_MARKET, new BigDecimal("100.00"), "recon:spend:2");
        insertHistory(PointHistoryType.SETTLE_MARKET, new BigDecimal("80.00"), "recon:settle:2");
        insertHistory(PointHistoryType.SETTLE_MARKET, new BigDecimal("50.00"), "recon:settle:2-extra");

        scheduler.checkSelfLedgerInvariant();

        List<ReconciliationMismatch> mismatches = mismatchRepository.findAll();
        assertThat(mismatches).hasSize(1);
        assertThat(mismatches.get(0).getCheckType()).isEqualTo("POINT_TOTAL_BALANCE");
        assertThat(mismatches.get(0).getDiffValue()).isEqualByComparingTo("30.00");
    }

    // ─── (c) Market 정상 응답 → 교차 검증 통과 ────────────────

    @Test
    void Market_정상_응답시_교차_검증_통과() throws Exception {
        insertHistory(PointHistoryType.SPEND_MARKET, new BigDecimal("100.00"), "recon:spend:3");

        MockRestServiceServer mockServer = createMockServer();
        mockServer.expect(requestTo("http://localhost:19999/internal/api/v1/markets/reconciliation-targets"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[1]", MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo("http://localhost:19999/internal/api/v1/markets/1/reconciliation-summary"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"marketId\":1,\"confirmedTotalAmount\":80,\"refundedTotalAmount\":10}",
                        MediaType.APPLICATION_JSON));

        scheduler.checkCrossServiceInvariant();

        assertThat(mismatchRepository.findAll()).isEmpty();
        mockServer.verify();
    }

    // ─── (d) Market 다운 → 스킵, 예외 전파 없음 ──────────────

    @Test
    void Market_다운시_스킵_예외_미전파() throws Exception {
        MockRestServiceServer mockServer = createMockServer();
        mockServer.expect(requestTo("http://localhost:19999/internal/api/v1/markets/reconciliation-targets"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        scheduler.checkCrossServiceInvariant();

        assertThat(mismatchRepository.findAll()).isEmpty();
        mockServer.verify();
    }
}
