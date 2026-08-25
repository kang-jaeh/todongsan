package com.todongsan.marketservice.market.service;

import com.todongsan.marketservice.global.exception.CustomException;
import com.todongsan.marketservice.global.exception.errorcode.CommonErrorCode;
import com.todongsan.marketservice.global.exception.errorcode.MarketErrorCode;
import com.todongsan.marketservice.market.dto.request.CreatePredictionRequest;
import com.todongsan.marketservice.market.dto.request.QuoteMarketPredictionRequest;
import com.todongsan.marketservice.market.dto.response.CreatePredictionResponse;
import com.todongsan.marketservice.market.dto.response.QuoteMarketPredictionResponse;
import com.todongsan.marketservice.market.entity.MarketPrediction;
import com.todongsan.marketservice.outbox.OutboxEventCreator;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Market 예측 참여 서비스.
 *
 * 기존: REST 동기 spend → 즉시 CONFIRMED/FAILED
 * 변경: Choreography Saga — prediction.created 이벤트 발행 후 202 Accepted.
 *       Member-Point가 비동기로 spend 처리 → point.deducted/failed 이벤트 → Market Consumer가 상태 전이.
 *
 * createPendingPrediction TX 안에서 outbox INSERT하므로 이중 쓰기 문제 없음.
 */
@Service
@RequiredArgsConstructor
public class MarketPredictionService {

    private final MarketPredictionTransactionService transactionService;
    private final OutboxEventCreator outboxEventCreator;

    public CreatePredictionResponse createPrediction(
            long marketId,
            Long memberId,
            String idempotencyKey,
            CreatePredictionRequest request
    ) {
        validateHeaders(marketId, memberId, idempotencyKey);

        MarketPrediction prediction;
        try {
            // TX: prediction INSERT (POINT_PENDING) + outbox INSERT (prediction.created)
            prediction = transactionService.createPendingPredictionWithOutbox(
                    marketId, memberId, idempotencyKey, request, outboxEventCreator);
        } catch (DuplicateKeyException e) {
            throw new CustomException(MarketErrorCode.MARKET_ALREADY_PREDICTED);
        }

        // 202 Accepted — 포인트 차감은 비동기로 진행.
        // 클라이언트는 prediction 상태를 폴링하여 CONFIRMED/FAILED를 확인한다.
        return toResponse(prediction);
    }

    public QuoteMarketPredictionResponse quotePrediction(
            long marketId,
            QuoteMarketPredictionRequest request
    ) {
        return transactionService.quotePrediction(marketId, request);
    }

    private CreatePredictionResponse toResponse(MarketPrediction prediction) {
        return new CreatePredictionResponse(
                prediction.getId(),
                prediction.getMarketId(),
                prediction.getOptionId(),
                prediction.getPointAmount(),
                prediction.getPriceSnapshot(),
                prediction.getContractQuantity(),
                prediction.getStatus()
        );
    }

    private void validateHeaders(long marketId, Long memberId, String idempotencyKey) {
        if (memberId == null || memberId <= 0) {
            throw new CustomException(CommonErrorCode.VALIDATION_FAILED);
        }
        String expectedIdempotencyKey = "MARKET_PREDICTION_SPEND:market:%d:member:%d"
                .formatted(marketId, memberId);
        if (!expectedIdempotencyKey.equals(idempotencyKey)) {
            throw new CustomException(CommonErrorCode.VALIDATION_FAILED);
        }
    }
}
