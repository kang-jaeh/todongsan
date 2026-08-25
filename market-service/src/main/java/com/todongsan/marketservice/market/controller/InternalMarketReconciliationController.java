package com.todongsan.marketservice.market.controller;

import com.todongsan.marketservice.market.repository.MarketMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 서비스 간 정합성 대사용 내부 API.
 * Member-Point의 대사 배치가 호출하여 Market의 CONFIRMED 예측 금액 합을 조회한다.
 */
@RestController
@RequestMapping("/internal/api/v1/markets")
@RequiredArgsConstructor
public class InternalMarketReconciliationController {

    private final MarketMapper marketMapper;

    /**
     * 정산 완료(SETTLED) 또는 무효(VOIDED) 마켓 ID 목록.
     * 대사 배치가 교차 검증할 대상 마켓을 조회한다.
     */
    @GetMapping("/reconciliation-targets")
    public List<Long> getReconciliationTargets() {
        return marketMapper.selectSettledOrVoidedMarketIds();
    }

    @GetMapping("/{marketId}/reconciliation-summary")
    public Map<String, Object> getReconciliationSummary(@PathVariable Long marketId) {
        BigDecimal confirmedTotal = marketMapper.sumConfirmedPredictionAmount(marketId);
        BigDecimal refundedTotal = marketMapper.sumRefundedPredictionAmount(marketId);

        return Map.of(
                "marketId", marketId,
                "confirmedTotalAmount", confirmedTotal != null ? confirmedTotal : BigDecimal.ZERO,
                "refundedTotalAmount", refundedTotal != null ? refundedTotal : BigDecimal.ZERO
        );
    }
}
