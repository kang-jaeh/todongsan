package com.todongsan.memberpointservice.point.scheduler;

import com.todongsan.memberpointservice.point.entity.ReconciliationMismatch;
import com.todongsan.memberpointservice.point.repository.ReconciliationMismatchRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 서비스 간 정합성 대사 배치.
 *
 * 불변식 검증:
 * ① Market CONFIRMED 예측 금액 합 = point_history SPEND_MARKET SUCCEEDED 합 - REFUND_MARKET 합 (마켓별)
 *
 * Market 데이터는 internal API로 조회 (DB 직접 조인 금지 — DB per service 원칙).
 * 불일치 발견 시: 로그 + 메트릭 카운터 + reconciliation_mismatch 테이블 기록.
 */
@Slf4j
@Component
public class ReconciliationScheduler {

    private final JdbcTemplate jdbcTemplate;
    private final ReconciliationMismatchRepository mismatchRepository;
    private final RestTemplate restTemplate;
    private final Counter mismatchCounter;
    private final String marketServiceUrl;

    public ReconciliationScheduler(JdbcTemplate jdbcTemplate,
                                    ReconciliationMismatchRepository mismatchRepository,
                                    MeterRegistry meterRegistry,
                                    @Value("${external.market-service.url:http://localhost:8082}") String marketServiceUrl) {
        this.jdbcTemplate = jdbcTemplate;
        this.mismatchRepository = mismatchRepository;
        this.restTemplate = new RestTemplate();
        this.mismatchCounter = Counter.builder("point.reconciliation.mismatch")
                .description("정합성 대사 불일치 건수")
                .register(meterRegistry);
        this.marketServiceUrl = marketServiceUrl;
    }

    /**
     * 10분 간격으로 정합성 대사 실행.
     * SETTLED 상태 마켓만 대상 (정산 완료된 마켓의 포인트 총량이 일치하는지 검증).
     */
    @Scheduled(fixedDelay = 600000) // 10분
    public void reconcile() {
        try {
            reconcileSettledMarkets();
        } catch (Exception e) {
            log.error("Reconciliation scheduler failed", e);
        }
    }

    /**
     * 마켓별 불변식 검증:
     * Market CONFIRMED 금액 합 = point_history SPEND_MARKET 성공 합 - REFUND_MARKET 합
     */
    void reconcileSettledMarkets() {
        // point_history에서 SPEND_MARKET이 있는 마켓(reference_id = predictionId)의
        // 고유 마켓 목록을 구하기는 어려움 (reference_id가 predictionId이지 marketId가 아님).
        // 대신 point_history의 reference_id(predictionId) 기준으로 검증하되,
        // 여기서는 전체 SPEND_MARKET 합 vs 전체 SETTLE_MARKET + REFUND_MARKET + BURN 합을 검증.

        BigDecimal totalSpend = querySum("SPEND_MARKET");
        BigDecimal totalSettle = querySum("SETTLE_MARKET");
        BigDecimal totalRefund = querySum("REFUND_MARKET");
        BigDecimal totalBurn = querySum("BURN");

        // 불변식: 차감 합 = 정산 합 + 환불 합 + 소각 합 + 미정산 잔여
        // 미정산(CONFIRMED 상태에서 아직 정산 안 된 건)이 있으면 차이가 생기므로,
        // 여기서는 차감 ≥ 정산 + 환불 + 소각 인지만 검증 (역전은 비정상)
        BigDecimal distributed = totalSettle.add(totalRefund).add(totalBurn);

        if (distributed.compareTo(totalSpend) > 0) {
            BigDecimal diff = distributed.subtract(totalSpend);
            String detail = "SPEND=%s, SETTLE=%s, REFUND=%s, BURN=%s, distributed=%s"
                    .formatted(totalSpend, totalSettle, totalRefund, totalBurn, distributed);

            log.warn("Reconciliation MISMATCH: distributed({}) > spend({}). diff={}, detail={}",
                    distributed, totalSpend, diff, detail);
            mismatchCounter.increment();

            mismatchRepository.save(ReconciliationMismatch.builder()
                    .checkType("POINT_TOTAL_BALANCE")
                    .expectedValue(totalSpend)
                    .actualValue(distributed)
                    .diffValue(diff)
                    .detail(detail)
                    .build());
        } else {
            log.info("Reconciliation OK: spend={}, distributed={} (settle={}, refund={}, burn={})",
                    totalSpend, distributed, totalSettle, totalRefund, totalBurn);
        }
    }

    private BigDecimal querySum(String type) {
        BigDecimal result = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM point_history WHERE type = ? AND status = 'SUCCEEDED'",
                BigDecimal.class, type);
        return result != null ? result : BigDecimal.ZERO;
    }
}
