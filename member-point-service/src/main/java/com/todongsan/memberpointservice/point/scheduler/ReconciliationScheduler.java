package com.todongsan.memberpointservice.point.scheduler;

import com.todongsan.memberpointservice.point.entity.ReconciliationMismatch;
import com.todongsan.memberpointservice.point.repository.ReconciliationMismatchRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 서비스 간 정합성 대사 배치.
 *
 * 불변식 검증 2가지:
 * ① 자기 원장: SPEND ≥ SETTLE + REFUND + BURN (분배 금액이 차감 금액을 초과할 수 없다)
 * ② 교차 검증: Market CONFIRMED+REFUNDED 합 vs point_history SPEND_MARKET 합 (마켓별)
 *
 * Market 데이터는 internal API로 조회 (DB 직접 조인 금지 — DB per service 원칙).
 * Market 다운 시 교차 검증 스킵+로그 (대사는 안전망이므로 장애 전파 금지).
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

    @Scheduled(fixedDelay = 600000)
    public void reconcile() {
        try {
            checkSelfLedgerInvariant();
            checkCrossServiceInvariant();
        } catch (Exception e) {
            log.error("Reconciliation scheduler failed", e);
        }
    }

    /**
     * ① 자기 원장 불변식: SPEND ≥ SETTLE + REFUND + BURN
     */
    void checkSelfLedgerInvariant() {
        BigDecimal totalSpend = querySum("SPEND_MARKET");
        BigDecimal totalSettle = querySum("SETTLE_MARKET");
        BigDecimal totalRefund = querySum("REFUND_MARKET");
        BigDecimal totalBurn = querySum("BURN");
        BigDecimal distributed = totalSettle.add(totalRefund).add(totalBurn);

        if (distributed.compareTo(totalSpend) > 0) {
            BigDecimal diff = distributed.subtract(totalSpend);
            String detail = "SPEND=%s, SETTLE=%s, REFUND=%s, BURN=%s".formatted(
                    totalSpend, totalSettle, totalRefund, totalBurn);
            log.warn("Reconciliation MISMATCH [SELF]: distributed({}) > spend({}), diff={}", distributed, totalSpend, diff);
            recordMismatch("POINT_TOTAL_BALANCE", null, totalSpend, distributed, diff, detail);
        } else {
            log.info("Reconciliation OK [SELF]: spend={}, distributed={}", totalSpend, distributed);
        }
    }

    /**
     * ② 교차 검증: Market의 CONFIRMED+REFUNDED 합 vs point_history SPEND 합.
     * Market 다운 시 스킵 (장애 전파 금지).
     */
    void checkCrossServiceInvariant() {
        List<Long> marketIds;
        try {
            marketIds = restTemplate.exchange(
                    marketServiceUrl + "/internal/api/v1/markets/reconciliation-targets",
                    HttpMethod.GET, null,
                    new ParameterizedTypeReference<List<Long>>() {}
            ).getBody();
        } catch (Exception e) {
            log.warn("Reconciliation [CROSS]: Market unavailable, skipping. error={}", e.getMessage());
            return;
        }

        if (marketIds == null || marketIds.isEmpty()) {
            log.info("Reconciliation [CROSS]: no targets");
            return;
        }

        for (Long marketId : marketIds) {
            try {
                checkOneMarket(marketId);
            } catch (Exception e) {
                log.warn("Reconciliation [CROSS]: failed for marketId={}, error={}", marketId, e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void checkOneMarket(Long marketId) {
        Map<String, Object> summary = restTemplate.getForObject(
                marketServiceUrl + "/internal/api/v1/markets/{id}/reconciliation-summary",
                Map.class, marketId);
        if (summary == null) return;

        BigDecimal marketConfirmed = toBigDecimal(summary.get("confirmedTotalAmount"));
        BigDecimal marketRefunded = toBigDecimal(summary.get("refundedTotalAmount"));
        BigDecimal marketTotal = marketConfirmed.add(marketRefunded);

        // point_history 전체 SPEND_MARKET 합과 대조.
        // Market 합계가 전체 SPEND보다 크면 비정상 (없는 차감이 Market에 기록된 것)
        BigDecimal pointSpendTotal = querySum("SPEND_MARKET");

        if (marketTotal.compareTo(pointSpendTotal) > 0) {
            BigDecimal diff = marketTotal.subtract(pointSpendTotal);
            String detail = "marketId=%d, marketConfirmed=%s, marketRefunded=%s, pointSpendTotal=%s"
                    .formatted(marketId, marketConfirmed, marketRefunded, pointSpendTotal);
            log.warn("Reconciliation MISMATCH [CROSS]: marketTotal({}) > pointSpend({}) for marketId={}",
                    marketTotal, pointSpendTotal, marketId);
            recordMismatch("MARKET_SPEND_MATCH", marketId, pointSpendTotal, marketTotal, diff, detail);
        } else {
            log.debug("Reconciliation OK [CROSS]: marketId={}, marketTotal={}", marketId, marketTotal);
        }
    }

    private void recordMismatch(String checkType, Long marketId,
                                 BigDecimal expected, BigDecimal actual, BigDecimal diff, String detail) {
        mismatchCounter.increment();
        mismatchRepository.save(ReconciliationMismatch.builder()
                .checkType(checkType).marketId(marketId)
                .expectedValue(expected).actualValue(actual)
                .diffValue(diff).detail(detail).build());
    }

    private BigDecimal querySum(String type) {
        BigDecimal result = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM point_history WHERE type = ? AND status = 'SUCCEEDED'",
                BigDecimal.class, type);
        return result != null ? result : BigDecimal.ZERO;
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal bd) return bd;
        return new BigDecimal(value.toString());
    }
}
