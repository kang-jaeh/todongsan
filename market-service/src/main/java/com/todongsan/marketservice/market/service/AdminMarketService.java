package com.todongsan.marketservice.market.service;

import com.todongsan.marketservice.global.exception.CustomException;
import com.todongsan.marketservice.global.exception.errorcode.CommonErrorCode;
import com.todongsan.marketservice.global.exception.errorcode.MarketErrorCode;
import com.todongsan.marketservice.market.dto.request.ConfirmMarketResultRequest;
import com.todongsan.marketservice.market.dto.request.CreateMarketOptionRequest;
import com.todongsan.marketservice.market.dto.request.CreateMarketRequest;
import com.todongsan.marketservice.market.dto.request.VoidMarketRequest;
import com.todongsan.marketservice.market.dto.response.ActivateMarketResponse;
import com.todongsan.marketservice.market.dto.response.ConfirmMarketResultResponse;
import com.todongsan.marketservice.market.dto.response.CreateMarketResponse;
import com.todongsan.marketservice.market.dto.response.RefundMarketResponse;
import com.todongsan.marketservice.market.dto.response.SettleMarketResponse;
import com.todongsan.marketservice.market.dto.response.VoidMarketResponse;
import com.todongsan.marketservice.market.entity.Market;
import com.todongsan.marketservice.market.entity.MarketOption;
import com.todongsan.marketservice.market.entity.MarketVoid;
import com.todongsan.marketservice.market.repository.MarketInsertRow;
import com.todongsan.marketservice.market.repository.MarketMapper;
import com.todongsan.marketservice.market.repository.MarketOptionInsertRow;
import com.todongsan.marketservice.market.type.MarketAnswerType;
import com.todongsan.marketservice.market.type.MarketPriceModel;
import com.todongsan.marketservice.market.type.MarketStatus;
import com.todongsan.marketservice.market.type.MarketVoidReasonType;
import com.todongsan.marketservice.outbox.OutboxEventCreator;
import com.todongsan.marketservice.market.type.RegionScope;
import com.todongsan.marketservice.market.type.RefundStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminMarketService {

    private static final BigDecimal DEFAULT_VIRTUAL_POOL_AMOUNT = new BigDecimal("100.00");
    private static final BigDecimal DEFAULT_FEE_RATE = new BigDecimal("5.00");
    private static final BigDecimal ZERO_AMOUNT = new BigDecimal("0.00");
    private static final BigDecimal ZERO_QUANTITY = new BigDecimal("0.00000000");
    private static final int PRICE_SCALE = 8;
    private static final String NATIONAL_REGION_SIDO = "\uC804\uAD6D";
    private static final Set<MarketStatus> VOIDABLE_STATUSES = Set.of(
            MarketStatus.PENDING,
            MarketStatus.ACTIVE,
            MarketStatus.CLOSED,
            MarketStatus.DATA_PENDING
    );

    private final MarketMapper marketMapper;
    private final MarketSettlementService marketSettlementService;
    private final MarketRefundService marketRefundService;
    private final OutboxEventCreator outboxEventCreator;

    @Transactional
    public CreateMarketResponse createMarket(CreateMarketRequest request) {
        LocalDateTime now = LocalDateTime.now();
        validateRequest(request, now);

        List<CreateMarketOptionRequest> options = request.getOptions();
        BigDecimal initialVirtualLiquidity = options.stream()
                .map(this::virtualPoolAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        MarketInsertRow market = MarketInsertRow.builder()
                .title(request.getTitle())
                .description(request.getDescription())
                .category(request.getCategory())
                .answerType(request.getAnswerType())
                .metricUnit(request.getMetricUnit())
                .regionScope(request.getRegionScope())
                .regionSido(request.getRegionSido())
                .regionSigu(request.getRegionSigu())
                .judgeDataSource(request.getJudgeDataSource())
                .judgeCriteria(request.getJudgeCriteria())
                .judgeDate(request.getJudgeDate())
                .status(MarketStatus.PENDING)
                .closeAt(request.getCloseAt())
                .settleDueAt(request.getSettleDueAt())
                .totalPool(ZERO_AMOUNT)
                .feeRate(request.getFeeRate() == null ? DEFAULT_FEE_RATE : request.getFeeRate())
                .feeAmount(ZERO_AMOUNT)
                .settlementPool(ZERO_AMOUNT)
                .initialVirtualLiquidity(initialVirtualLiquidity)
                .priceModel(MarketPriceModel.POOL_SHARE)
                .createdBy(request.getCreatedBy())
                .createdAt(now)
                .updatedAt(now)
                .build();
        marketMapper.insertMarket(market);

        List<MarketOptionInsertRow> optionRows = options.stream()
                .map(option -> toInsertRow(option, market.getId(), initialVirtualLiquidity, now))
                .toList();
        marketMapper.insertMarketOptions(optionRows);

        return new CreateMarketResponse(
                market.getId(),
                market.getRegionScope(),
                market.getRegionSido(),
                market.getRegionSigu()
        );
    }

    @Transactional
    public ActivateMarketResponse activateMarket(long marketId) {
        LocalDateTime now = LocalDateTime.now();
        Market market = getMarket(marketId);
        validateActivation(market, now);

        int updatedRows = marketMapper.activatePendingMarket(marketId, now);
        if (updatedRows == 0) {
            getMarket(marketId);
            throw new CustomException(MarketErrorCode.MARKET_INVALID_STATUS);
        }
        return new ActivateMarketResponse(marketId, MarketStatus.ACTIVE);
    }

    @Transactional
    public ConfirmMarketResultResponse confirmMarketResult(long marketId, ConfirmMarketResultRequest request) {
        LocalDateTime now = LocalDateTime.now();
        Market market = marketMapper.lockMarketById(marketId);
        if (market == null) {
            throw new CustomException(MarketErrorCode.MARKET_NOT_FOUND);
        }
        validateResultConfirmation(market, now);
        if (marketMapper.countUnresolvedPredictionsForResult(marketId) > 0) {
            throw new CustomException(MarketErrorCode.MARKET_INVALID_STATUS);
        }

        List<MarketOption> options = marketMapper.lockOptionsByMarketId(marketId);
        MarketOption resultOption = resolveResultOption(market, options, request);

        marketMapper.clearResultOptions(marketId, now);
        if (marketMapper.markResultOption(marketId, resultOption.getId(), now) != 1
                || marketMapper.updateMarketResult(
                        marketId,
                        resultOption.getId(),
                        request.getResultValue(),
                        request.getResultText(),
                        now
                ) != 1) {
            throw new CustomException(MarketErrorCode.MARKET_INVALID_SETTLEMENT_DATA);
        }
        return new ConfirmMarketResultResponse(
                marketId,
                resultOption.getId(),
                request.getResultValue(),
                request.getResultText(),
                MarketStatus.CLOSED
        );
    }

    public SettleMarketResponse settleMarket(long marketId) {
        return marketSettlementService.settleMarket(marketId);
    }

    public SettleMarketResponse retryMarketSettlement(long marketId) {
        return marketSettlementService.retryMarketSettlement(marketId);
    }

    public RefundMarketResponse refundMarket(long marketId) {
        return marketRefundService.refundMarket(marketId);
    }

    public RefundMarketResponse retryRefundMarket(long marketId) {
        return marketRefundService.retryRefundMarket(marketId);
    }

    @Transactional
    public VoidMarketResponse voidMarket(long marketId, VoidMarketRequest request) {
        MarketVoidReasonType reasonType = parseVoidReasonType(request.reasonCode());
        LocalDateTime now = LocalDateTime.now();

        Market market = marketMapper.lockMarketById(marketId);
        if (market == null) {
            throw new CustomException(MarketErrorCode.MARKET_NOT_FOUND);
        }
        validateVoidableMarket(market);
        if (marketMapper.countUnresolvedPredictionsForVoid(marketId) > 0) {
            throw new CustomException(MarketErrorCode.MARKET_INVALID_STATUS);
        }
        if (marketMapper.selectMarketVoidByMarketId(marketId) != null) {
            throw new CustomException(MarketErrorCode.MARKET_CANNOT_VOID);
        }

        long refundablePredictionCount = marketMapper.countConfirmedPredictionsForRefund(marketId);
        MarketVoid marketVoid = new MarketVoid(
                null,
                marketId,
                reasonType,
                request.reason(),
                RefundStatus.PENDING,
                null,
                now,
                now,
                now
        );
        marketMapper.insertMarketVoid(marketVoid);
        if (marketMapper.updateMarketStatusToVoided(marketId, now) != 1) {
            throw new CustomException(MarketErrorCode.MARKET_CANNOT_VOID);
        }

        // 환불 실행은 MarketRefundService → MemberPointClient REST 동기 batch가 담당.
        // 이벤트가 아닌 REST batch인 이유:
        // (1) 관리자 액션 → 무효 처리 결과를 즉시 확인해야 함
        // (2) 기존 batch 부분 성공 API(REQUIRES_NEW) 재사용이 자연스러움

        // 감사/알림용 이벤트 기록 (소비자 없음 — 의도된 설계).
        // 향후 알림 서비스 등에서 구독하여 관리자 알림을 보낼 수 있도록 확장 포인트로 남겨둠.
        if (refundablePredictionCount > 0) {
            outboxEventCreator.createMarketVoidedEvent(
                    marketId, marketVoid.getId(), Math.toIntExact(refundablePredictionCount));
        }

        return new VoidMarketResponse(
                marketId,
                marketVoid.getId(),
                MarketStatus.VOIDED,
                refundablePredictionCount > 0,
                Math.toIntExact(refundablePredictionCount),
                reasonType.name(),
                request.reason()
        );
    }

    private Market getMarket(long marketId) {
        Market market = marketMapper.selectMarketById(marketId);
        if (market == null) {
            throw new CustomException(MarketErrorCode.MARKET_NOT_FOUND);
        }
        return market;
    }

    private void validateActivation(Market market, LocalDateTime now) {
        if (market.getStatus() != MarketStatus.PENDING) {
            throw new CustomException(MarketErrorCode.MARKET_INVALID_STATUS);
        }
        if (!market.getCloseAt().isAfter(now)) {
            throw new CustomException(MarketErrorCode.MARKET_CLOSED);
        }
        if (marketMapper.selectOptionsByMarketId(market.getId()).size() < 2
                || hasInvalidInitialVirtualLiquidity(market)) {
            throw new CustomException(MarketErrorCode.MARKET_INVALID_OPTION);
        }
    }

    private boolean hasInvalidInitialVirtualLiquidity(Market market) {
        return market.getInitialVirtualLiquidity() == null
                || market.getInitialVirtualLiquidity().compareTo(BigDecimal.ZERO) <= 0;
    }

    private void validateResultConfirmation(Market market, LocalDateTime now) {
        if (market.getStatus() == MarketStatus.ACTIVE) {
            if (market.getCloseAt().isAfter(now)) {
                throw new CustomException(MarketErrorCode.MARKET_INVALID_STATUS);
            }
            return;
        }
        if (market.getStatus() != MarketStatus.DATA_PENDING) {
            throw new CustomException(MarketErrorCode.MARKET_INVALID_STATUS);
        }
    }

    private void validateVoidableMarket(Market market) {
        if (market.getStatus() == MarketStatus.SETTLEMENT_IN_PROGRESS
                || market.getStatus() == MarketStatus.SETTLED
                || market.getStatus() == MarketStatus.VOIDED) {
            throw new CustomException(MarketErrorCode.MARKET_CANNOT_VOID);
        }
        if (!VOIDABLE_STATUSES.contains(market.getStatus())) {
            throw new CustomException(MarketErrorCode.MARKET_INVALID_STATUS);
        }
    }

    private MarketVoidReasonType parseVoidReasonType(String reasonCode) {
        try {
            return MarketVoidReasonType.valueOf(reasonCode);
        } catch (IllegalArgumentException e) {
            throw new CustomException(CommonErrorCode.VALIDATION_FAILED);
        }
    }

    private MarketOption resolveResultOption(
            Market market,
            List<MarketOption> options,
            ConfirmMarketResultRequest request
    ) {
        if (market.getAnswerType() == MarketAnswerType.NUMERIC_RANGE) {
            return resolveNumericRangeResult(options, request);
        }
        if (request.getResultOptionId() == null) {
            throw new CustomException(CommonErrorCode.VALIDATION_FAILED);
        }
        return findOption(options, request.getResultOptionId());
    }

    private MarketOption resolveNumericRangeResult(
            List<MarketOption> options,
            ConfirmMarketResultRequest request
    ) {
        if (request.getResultValue() == null) {
            throw new CustomException(CommonErrorCode.VALIDATION_FAILED);
        }
        List<MarketOption> matchedOptions = options.stream()
                .filter(option -> matchesRange(option, request.getResultValue()))
                .toList();
        if (matchedOptions.isEmpty()) {
            throw new CustomException(MarketErrorCode.MARKET_WINNING_OPTION_NOT_FOUND);
        }
        if (matchedOptions.size() > 1) {
            throw new CustomException(MarketErrorCode.MARKET_INVALID_SETTLEMENT_DATA);
        }
        MarketOption resultOption = matchedOptions.get(0);
        if (request.getResultOptionId() != null
                && !request.getResultOptionId().equals(resultOption.getId())) {
            throw new CustomException(MarketErrorCode.MARKET_INVALID_SETTLEMENT_DATA);
        }
        return resultOption;
    }

    private boolean matchesRange(MarketOption option, BigDecimal resultValue) {
        boolean matchesMin = option.getRangeMin() == null
                || resultValue.compareTo(option.getRangeMin()) > 0
                || resultValue.compareTo(option.getRangeMin()) == 0 && Boolean.TRUE.equals(option.getMinInclusive());
        boolean matchesMax = option.getRangeMax() == null
                || resultValue.compareTo(option.getRangeMax()) < 0
                || resultValue.compareTo(option.getRangeMax()) == 0 && Boolean.TRUE.equals(option.getMaxInclusive());
        return matchesMin && matchesMax;
    }

    private MarketOption findOption(List<MarketOption> options, Long resultOptionId) {
        return options.stream()
                .filter(option -> option.getId().equals(resultOptionId))
                .findFirst()
                .orElseThrow(() -> new CustomException(MarketErrorCode.MARKET_OPTION_NOT_FOUND));
    }

    private void validateRequest(CreateMarketRequest request, LocalDateTime now) {
        validateRegion(request);
        if (request.getFeeRate() != null
                && (request.getFeeRate().compareTo(BigDecimal.ZERO) < 0
                || request.getFeeRate().compareTo(new BigDecimal("100.00")) > 0)) {
            throw new CustomException(MarketErrorCode.MARKET_INVALID_FEE_RATE);
        }
        if (!request.getCloseAt().isAfter(now)) {
            throw new CustomException(MarketErrorCode.MARKET_INVALID_OPTION);
        }
        if (request.getJudgeDate().isBefore(request.getCloseAt().toLocalDate())) {
            throw new CustomException(MarketErrorCode.MARKET_INVALID_OPTION);
        }
        if (request.getSettleDueAt() != null && !request.getSettleDueAt().isAfter(request.getCloseAt())) {
            throw new CustomException(MarketErrorCode.MARKET_INVALID_OPTION);
        }
        if (request.getSettleDueAt() != null
                && request.getSettleDueAt().toLocalDate().isBefore(request.getJudgeDate())) {
            throw new CustomException(MarketErrorCode.MARKET_INVALID_OPTION);
        }

        List<CreateMarketOptionRequest> options = request.getOptions();
        if (options.size() < 2) {
            throw new CustomException(MarketErrorCode.MARKET_INVALID_OPTION);
        }
        if (request.getAnswerType() == MarketAnswerType.YES_NO && options.size() != 2) {
            throw new CustomException(MarketErrorCode.MARKET_INVALID_OPTION);
        }

        Set<String> optionCodes = new HashSet<>();
        BigDecimal initialVirtualLiquidity = BigDecimal.ZERO;
        for (CreateMarketOptionRequest option : options) {
            if (!optionCodes.add(option.getOptionCode())) {
                throw new CustomException(MarketErrorCode.MARKET_INVALID_OPTION);
            }
            BigDecimal virtualPoolAmount = virtualPoolAmount(option);
            if (virtualPoolAmount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new CustomException(MarketErrorCode.MARKET_INVALID_OPTION);
            }
            initialVirtualLiquidity = initialVirtualLiquidity.add(virtualPoolAmount);
        }
        if (initialVirtualLiquidity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new CustomException(MarketErrorCode.MARKET_INVALID_OPTION);
        }

        if (request.getAnswerType() == MarketAnswerType.NUMERIC_RANGE) {
            validateNumericRanges(options);
        }
    }

    private void validateRegion(CreateMarketRequest request) {
        if (request.getRegionScope() == null) {
            throw new CustomException(CommonErrorCode.VALIDATION_FAILED);
        }

        String normalizedRegionSido = normalizeNullableRegionSido(request.getRegionSido());
        String normalizedRegionSigu = normalizeNullableRegionSigu(request.getRegionSigu());

        if (request.getRegionScope() == RegionScope.NON_REGIONAL) {
            validateNonRegional(normalizedRegionSido, normalizedRegionSigu, request);
            return;
        }
        if (request.getRegionScope() == RegionScope.NATIONAL) {
            validateNational(normalizedRegionSido, normalizedRegionSigu, request);
            return;
        }
        validateRegional(normalizedRegionSido, normalizedRegionSigu, request);
    }

    private String normalizeNullableRegionSido(String regionSido) {
        if (regionSido == null) {
            return null;
        }
        String trimmed = regionSido.trim();
        if (trimmed.isBlank()) {
            throw new CustomException(CommonErrorCode.VALIDATION_FAILED);
        }
        return trimmed;
    }

    private String normalizeNullableRegionSigu(String regionSigu) {
        if (regionSigu == null) {
            return null;
        }
        String trimmed = regionSigu.trim();
        if (trimmed.isBlank()) {
            throw new CustomException(CommonErrorCode.VALIDATION_FAILED);
        }
        return trimmed;
    }

    private void validateNonRegional(
            String normalizedRegionSido,
            String normalizedRegionSigu,
            CreateMarketRequest request
    ) {
        if (normalizedRegionSido != null || normalizedRegionSigu != null) {
            throw new CustomException(CommonErrorCode.VALIDATION_FAILED);
        }
        request.setRegionSido(null);
        request.setRegionSigu(null);
    }

    private void validateNational(
            String normalizedRegionSido,
            String normalizedRegionSigu,
            CreateMarketRequest request
    ) {
        if (normalizedRegionSido != null && !NATIONAL_REGION_SIDO.equals(normalizedRegionSido)) {
            throw new CustomException(CommonErrorCode.VALIDATION_FAILED);
        }
        if (normalizedRegionSigu != null) {
            throw new CustomException(CommonErrorCode.VALIDATION_FAILED);
        }
        request.setRegionSido(NATIONAL_REGION_SIDO);
        request.setRegionSigu(null);
    }

    private void validateRegional(
            String normalizedRegionSido,
            String normalizedRegionSigu,
            CreateMarketRequest request
    ) {
        if (normalizedRegionSido == null || NATIONAL_REGION_SIDO.equals(normalizedRegionSido)) {
            throw new CustomException(CommonErrorCode.VALIDATION_FAILED);
        }
        request.setRegionSido(normalizedRegionSido);
        request.setRegionSigu(normalizedRegionSigu);
    }

    private void validateNumericRanges(List<CreateMarketOptionRequest> options) {
        List<CreateMarketOptionRequest> sortedOptions = options.stream()
                .sorted(Comparator.comparing(CreateMarketOptionRequest::getRangeMin,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();

        int openStartCount = 0;
        int openEndCount = 0;
        for (CreateMarketOptionRequest option : sortedOptions) {
            if (option.getRangeMin() == null && option.getRangeMax() == null) {
                throw new CustomException(MarketErrorCode.MARKET_INVALID_OPTION_RANGE);
            }
            if (option.getRangeMin() == null) {
                openStartCount++;
            }
            if (option.getRangeMax() == null) {
                openEndCount++;
            }
            if (option.getRangeMin() != null
                    && option.getRangeMax() != null
                    && option.getRangeMin().compareTo(option.getRangeMax()) >= 0) {
                throw new CustomException(MarketErrorCode.MARKET_INVALID_OPTION_RANGE);
            }
        }
        if (openStartCount > 1 || openEndCount > 1) {
            throw new CustomException(MarketErrorCode.MARKET_INVALID_OPTION_RANGE);
        }

        for (int index = 1; index < sortedOptions.size(); index++) {
            CreateMarketOptionRequest previous = sortedOptions.get(index - 1);
            CreateMarketOptionRequest current = sortedOptions.get(index);
            if (previous.getRangeMax() == null || current.getRangeMin() == null) {
                throw new CustomException(MarketErrorCode.MARKET_INVALID_OPTION_RANGE);
            }
            int boundaryComparison = previous.getRangeMax().compareTo(current.getRangeMin());
            if (boundaryComparison != 0
                    || isMaxInclusive(previous) == isMinInclusive(current)) {
                throw new CustomException(MarketErrorCode.MARKET_INVALID_OPTION_RANGE);
            }
        }
    }

    private boolean isMinInclusive(CreateMarketOptionRequest option) {
        return option.getMinInclusive() == null || option.getMinInclusive();
    }

    private boolean isMaxInclusive(CreateMarketOptionRequest option) {
        return option.getMaxInclusive() != null && option.getMaxInclusive();
    }

    private MarketOptionInsertRow toInsertRow(
            CreateMarketOptionRequest option,
            Long marketId,
            BigDecimal totalVirtualPoolAmount,
            LocalDateTime now
    ) {
        BigDecimal virtualPoolAmount = virtualPoolAmount(option);
        return MarketOptionInsertRow.builder()
                .marketId(marketId)
                .optionCode(option.getOptionCode())
                .optionText(option.getOptionText())
                .displayOrder(option.getDisplayOrder() == null ? 0 : option.getDisplayOrder())
                .rangeMin(option.getRangeMin())
                .rangeMax(option.getRangeMax())
                .minInclusive(isMinInclusive(option))
                .maxInclusive(isMaxInclusive(option))
                .virtualPoolAmount(virtualPoolAmount)
                .realPoolAmount(ZERO_AMOUNT)
                .totalContractQuantity(ZERO_QUANTITY)
                .currentPrice(virtualPoolAmount.divide(totalVirtualPoolAmount, PRICE_SCALE, RoundingMode.HALF_UP))
                .predictionCount(0)
                .isResult(false)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private BigDecimal virtualPoolAmount(CreateMarketOptionRequest option) {
        return option.getVirtualPoolAmount() == null
                ? DEFAULT_VIRTUAL_POOL_AMOUNT
                : option.getVirtualPoolAmount();
    }
}
