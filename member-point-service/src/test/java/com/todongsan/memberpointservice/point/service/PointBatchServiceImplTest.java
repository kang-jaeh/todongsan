package com.todongsan.memberpointservice.point.service;

import com.todongsan.memberpointservice.global.exception.CustomException;
import com.todongsan.memberpointservice.global.exception.ErrorCode;
import com.todongsan.memberpointservice.member.repository.MemberRepository;
import com.todongsan.memberpointservice.point.dto.request.RefundItem;
import com.todongsan.memberpointservice.point.dto.request.RefundRequest;
import com.todongsan.memberpointservice.point.dto.request.SettlementItem;
import com.todongsan.memberpointservice.point.dto.request.SettlementRequest;
import com.todongsan.memberpointservice.point.dto.response.BatchItemResult;
import com.todongsan.memberpointservice.point.dto.response.RefundResponse;
import com.todongsan.memberpointservice.point.dto.response.SettlementResponse;
import com.todongsan.memberpointservice.point.entity.PointHistoryType;
import com.todongsan.memberpointservice.point.repository.PointHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * settle/refund 배치의 트랜잭션 경계 테스트.
 *
 * Phase 2.5: 각 item이 독립 트랜잭션(REQUIRES_NEW)으로 처리되므로,
 * settle/refund는 BatchItemProcessor에 위임만 하고,
 * 한 항목 실패가 다른 항목에 영향을 주지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class PointBatchServiceImplTest {

    @Mock MemberRepository memberRepository;
    @Mock PointHistoryRepository pointHistoryRepository;
    @Mock BatchItemProcessor batchItemProcessor;
    @Mock PlatformTransactionManager transactionManager;

    PointInternalServiceImpl service;

    private static final String SETTLEMENT_ID = "settle-market-7-20260528";
    private static final String REFUND_ID = "refund-market-7-20260528";

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any()))
                .thenReturn(new SimpleTransactionStatus());
        lenient().doNothing().when(transactionManager).commit(any());
        lenient().doNothing().when(transactionManager).rollback(any());
        lenient().when(pointHistoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(pointHistoryRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        service = new PointInternalServiceImpl(
                memberRepository, pointHistoryRepository, batchItemProcessor, transactionManager);
    }

    // ─── settle ───────────────────────────────────────────────

    @Test
    void settle_각_항목을_batchItemProcessor에_위임() {
        SettlementItem item1 = mockSettleItem(1L, 1001L, "key-1");
        SettlementItem item2 = mockSettleItem(2L, 1002L, "key-2");
        SettlementRequest request = mockSettleRequest(List.of(item1, item2));

        when(batchItemProcessor.processItem(eq(1001L), eq(1L), any(), any(), any(), any(), eq("key-1"), eq(PointHistoryType.SETTLE_MARKET)))
                .thenReturn(BatchItemResult.builder().predictionId(1001L).memberId(1L).status("PROCESSED").build());
        when(batchItemProcessor.processItem(eq(1002L), eq(2L), any(), any(), any(), any(), eq("key-2"), eq(PointHistoryType.SETTLE_MARKET)))
                .thenReturn(BatchItemResult.builder().predictionId(1002L).memberId(2L).status("PROCESSED").build());

        SettlementResponse response = service.settle(SETTLEMENT_ID, request);

        assertThat(response.getResults()).hasSize(2);
        assertThat(response.getResults().get(0).getStatus()).isEqualTo("PROCESSED");
        assertThat(response.getResults().get(1).getStatus()).isEqualTo("PROCESSED");
        verify(batchItemProcessor, times(2)).processItem(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void settle_한_항목_실패해도_나머지_정상_처리_부분_성공() {
        SettlementItem item1 = mockSettleItem(1L, 1001L, "key-1");
        SettlementItem item2 = mockSettleItem(2L, 1002L, "key-2");
        SettlementItem item3 = mockSettleItem(3L, 1003L, "key-3");
        SettlementRequest request = mockSettleRequest(List.of(item1, item2, item3));

        when(batchItemProcessor.processItem(eq(1001L), any(), any(), any(), any(), any(), eq("key-1"), any()))
                .thenReturn(BatchItemResult.builder().predictionId(1001L).memberId(1L).status("PROCESSED").build());
        when(batchItemProcessor.processItem(eq(1002L), any(), any(), any(), any(), any(), eq("key-2"), any()))
                .thenReturn(BatchItemResult.builder().predictionId(1002L).memberId(2L).status("FAILED").errorCode("MEMBER_NOT_FOUND").build());
        when(batchItemProcessor.processItem(eq(1003L), any(), any(), any(), any(), any(), eq("key-3"), any()))
                .thenReturn(BatchItemResult.builder().predictionId(1003L).memberId(3L).status("PROCESSED").build());

        SettlementResponse response = service.settle(SETTLEMENT_ID, request);

        assertThat(response.getResults()).hasSize(3);
        assertThat(response.getResults().get(0).getStatus()).isEqualTo("PROCESSED");
        assertThat(response.getResults().get(1).getStatus()).isEqualTo("FAILED");
        assertThat(response.getResults().get(2).getStatus()).isEqualTo("PROCESSED");
    }

    @Test
    void settle_idempotencyKey_불일치_예외() {
        SettlementRequest request = mockSettleRequest(List.of());
        when(request.getSettlementId()).thenReturn("different-id");

        assertThatThrownBy(() -> service.settle(SETTLEMENT_ID, request))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REQUEST);
    }

    // ─── refund ───────────────────────────────────────────────

    @Test
    void refund_각_항목을_batchItemProcessor에_위임() {
        RefundItem item = mockRefundItem(1L, 1001L, "refund-key-1", "MARKET_PREDICTION");
        RefundRequest request = mockRefundRequest(List.of(item));

        when(batchItemProcessor.processItem(eq(1001L), eq(1L), any(), any(), any(), any(), eq("refund-key-1"), eq(PointHistoryType.REFUND_MARKET)))
                .thenReturn(BatchItemResult.builder().predictionId(1001L).memberId(1L).status("PROCESSED").build());

        RefundResponse response = service.refund(REFUND_ID, request);

        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().get(0).getStatus()).isEqualTo("PROCESSED");
    }

    @Test
    void refund_INSIGHT_REPORT_타입_구분() {
        RefundItem item = mockRefundItem(1L, 1001L, "refund-key-1", "INSIGHT_REPORT");
        RefundRequest request = mockRefundRequest(List.of(item));

        when(batchItemProcessor.processItem(any(), any(), any(), any(), any(), any(), any(), eq(PointHistoryType.REFUND_INSIGHT)))
                .thenReturn(BatchItemResult.builder().predictionId(1001L).memberId(1L).status("PROCESSED").build());

        service.refund(REFUND_ID, request);

        verify(batchItemProcessor).processItem(any(), any(), any(), any(), any(), any(), any(), eq(PointHistoryType.REFUND_INSIGHT));
    }

    // ─── helpers ──────────────────────────────────────────────

    private SettlementItem mockSettleItem(Long memberId, Long predictionId, String key) {
        SettlementItem item = mock(SettlementItem.class);
        lenient().when(item.getPredictionId()).thenReturn(predictionId);
        lenient().when(item.getMemberId()).thenReturn(memberId);
        lenient().when(item.getAmount()).thenReturn(new BigDecimal("100.00"));
        lenient().when(item.getReferenceType()).thenReturn("MARKET_PREDICTION");
        lenient().when(item.getReferenceId()).thenReturn(predictionId);
        lenient().when(item.getReason()).thenReturn("정산");
        lenient().when(item.getIdempotencyKey()).thenReturn(key);
        return item;
    }

    private SettlementRequest mockSettleRequest(List<SettlementItem> items) {
        SettlementRequest req = mock(SettlementRequest.class);
        lenient().when(req.getSettlementId()).thenReturn(SETTLEMENT_ID);
        lenient().when(req.getMarketId()).thenReturn(7L);
        lenient().when(req.getItems()).thenReturn(items);
        return req;
    }

    private RefundItem mockRefundItem(Long memberId, Long predictionId, String key, String refType) {
        RefundItem item = mock(RefundItem.class);
        lenient().when(item.getPredictionId()).thenReturn(predictionId);
        lenient().when(item.getMemberId()).thenReturn(memberId);
        lenient().when(item.getAmount()).thenReturn(new BigDecimal("100.00"));
        lenient().when(item.getReferenceType()).thenReturn(refType);
        lenient().when(item.getReferenceId()).thenReturn(predictionId);
        lenient().when(item.getReason()).thenReturn("환불");
        lenient().when(item.getIdempotencyKey()).thenReturn(key);
        return item;
    }

    private RefundRequest mockRefundRequest(List<RefundItem> items) {
        RefundRequest req = mock(RefundRequest.class);
        when(req.getRefundId()).thenReturn(REFUND_ID);
        lenient().when(req.getMarketId()).thenReturn(7L);
        when(req.getItems()).thenReturn(items);
        return req;
    }
}
