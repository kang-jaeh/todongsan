package com.todongsan.memberpointservice.point.service;

import com.todongsan.memberpointservice.point.entity.PointHistory;
import com.todongsan.memberpointservice.point.entity.PointTransactionStatus;
import com.todongsan.memberpointservice.point.repository.PointHistoryRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * PENDING 선삽입 패턴의 동시성 검증.
 *
 * 실제 MySQL(InnoDB)에서 동일 Idempotency-Key로 10스레드 동시 요청 시:
 * - 정확히 1건 성공 (SUCCEEDED)
 * - 나머지 9건 ALREADY_PROCESSED (HTTP 200)
 * - 5xx 에러 0건
 * - DB에 point_history 1건, 잔액 정확
 *
 * H2는 InnoDB의 유니크 인덱스 락 동작을 재현하지 못하므로 Testcontainers(MySQL) 사용.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("integration")
class EarnIdempotencyConcurrencyTest {

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
    }

    @Autowired MockMvc mockMvc;
    @Autowired DataSource dataSource;
    @Autowired PointHistoryRepository pointHistoryRepository;

    @Test
    void 동일키_10스레드_동시요청_1건성공_9건_ALREADY_PROCESSED_5xx_0건() throws Exception {
        // given: 테스트 회원 (잔액 100P)
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO member (nickname, point_balance, role, oauth_provider, oauth_id) "
                    + "VALUES ('concurrent-test', 100.00, 'USER', 'KAKAO', 'concurrent-test-001')");
        }

        String idempotencyKey = "concurrent-test:earn:001";
        String requestBody = """
                {
                    "memberId": 1,
                    "type": "EARN_VOTE",
                    "amount": 10.00,
                    "referenceType": "BATTLE",
                    "referenceId": 1,
                    "reason": "동시성 테스트"
                }
                """;

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();

        // when: 10스레드가 동시에 같은 키로 요청
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();       // "나 준비됐어"
                startLatch.await();           // 전원 준비될 때까지 대기
                return mockMvc.perform(post("/internal/api/v1/points/earn")
                                .header("Idempotency-Key", idempotencyKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                        .andReturn();
            }));
        }

        readyLatch.await();   // 10스레드 모두 준비 완료
        startLatch.countDown(); // 동시 출발

        // then: 결과 수집
        int successCount = 0;       // 200 + 최초 처리
        int alreadyCount = 0;       // 200 + ALREADY_PROCESSED
        int serverErrorCount = 0;   // 5xx

        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get(30, TimeUnit.SECONDS);
            int status = result.getResponse().getStatus();
            String body = result.getResponse().getContentAsString();

            if (status >= 500) {
                serverErrorCount++;
            } else if (status == 200) {
                if (body.contains("POINT_TRANSACTION_ALREADY_PROCESSED")) {
                    alreadyCount++;
                } else {
                    successCount++;
                }
            }
        }

        executor.shutdown();

        // 검증: 5xx 0건
        assertThat(serverErrorCount)
                .as("5xx 에러는 0건이어야 한다")
                .isZero();

        // 검증: 정확히 1건 성공 + 9건 ALREADY_PROCESSED
        assertThat(successCount)
                .as("최초 처리 성공은 정확히 1건")
                .isEqualTo(1);
        assertThat(alreadyCount)
                .as("나머지는 ALREADY_PROCESSED")
                .isEqualTo(threadCount - 1);

        // 검증: DB에 point_history 1건 (SUCCEEDED)
        Optional<PointHistory> history = pointHistoryRepository.findByIdempotencyKey(idempotencyKey);
        assertThat(history).isPresent();
        assertThat(history.get().getStatus()).isEqualTo(PointTransactionStatus.SUCCEEDED);
        assertThat(history.get().getAmount()).isEqualByComparingTo(new BigDecimal("10.00"));

        // 검증: 잔액 정확 (100 + 10 = 110)
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            var rs = stmt.executeQuery("SELECT point_balance FROM member WHERE id = 1");
            rs.next();
            assertThat(rs.getBigDecimal("point_balance")).isEqualByComparingTo(new BigDecimal("110.00"));
        }
    }
}
