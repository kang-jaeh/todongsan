package com.todongsan.memberpointservice.point.service;

import com.todongsan.memberpointservice.global.exception.CustomException;
import com.todongsan.memberpointservice.global.exception.ErrorCode;
import com.todongsan.memberpointservice.global.util.RequestHashUtil;
import com.todongsan.memberpointservice.member.entity.Member;
import com.todongsan.memberpointservice.member.repository.MemberRepository;
import com.todongsan.memberpointservice.point.dto.request.*;
import com.todongsan.memberpointservice.point.dto.response.*;
import com.todongsan.memberpointservice.point.entity.PointHistory;
import com.todongsan.memberpointservice.point.entity.PointHistoryType;
import com.todongsan.memberpointservice.point.entity.PointReferenceType;
import com.todongsan.memberpointservice.point.entity.PointTransactionStatus;
import com.todongsan.memberpointservice.point.repository.PointHistoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 포인트 적립/차감/정산/환불 처리.
 *
 * earn/spend는 TransactionTemplate으로 트랜잭션을 명시적으로 관리한다.
 * 이유: PENDING 선삽입 시 DataIntegrityViolationException이 발생하면
 * Spring이 트랜잭션을 rollback-only로 마킹하기 때문에,
 * 트랜잭션 외부에서 예외를 처리해야 UnexpectedRollbackException을 피할 수 있다.
 */
@Slf4j
@Service
public class PointInternalServiceImpl implements PointInternalService {

    private final MemberRepository memberRepository;
    private final PointHistoryRepository pointHistoryRepository;
    private final TransactionTemplate txTemplate;

    public PointInternalServiceImpl(MemberRepository memberRepository,
                                    PointHistoryRepository pointHistoryRepository,
                                    PlatformTransactionManager transactionManager) {
        this.memberRepository = memberRepository;
        this.pointHistoryRepository = pointHistoryRepository;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    // ─── earn ─────────────────────────────────────────────────

    @Override
    public PointResult<EarnResponse> earn(String idempotencyKey, EarnRequest request) {
        validateIdempotencyKey(idempotencyKey);
        validateAmount(request.getAmount());

        PointReferenceType refType = parseReferenceType(request.getReferenceType());
        PointHistoryType histType = parseHistoryType(request.getType());
        BigDecimal normalizedAmount = request.getAmount().setScale(2, RoundingMode.DOWN);
        String requestHash = RequestHashUtil.compute(
                request.getMemberId(), request.getType(),
                request.getAmount(), request.getReferenceType(), request.getReferenceId());

        // 1. 낙관적 검사 — 대부분의 중복 요청은 여기서 걸린다 (auto-commit read)
        Optional<PointHistory> existing = pointHistoryRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return handleExistingEarn(existing.get(), requestHash);
        }

        // 2. 단일 트랜잭션: PENDING 선삽입 → 잔액 UPDATE → confirm
        try {
            return txTemplate.execute(status -> {
                memberRepository.findByIdAndDeletedAtIsNull(request.getMemberId())
                        .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));

                PointHistory pending = PointHistory.builder()
                        .memberId(request.getMemberId())
                        .type(histType)
                        .amount(normalizedAmount)
                        .balanceSnapshot(BigDecimal.ZERO)
                        .reason(request.getReason())
                        .referenceType(refType)
                        .referenceId(request.getReferenceId())
                        .idempotencyKey(idempotencyKey)
                        .requestHash(requestHash)
                        .status(PointTransactionStatus.PENDING)
                        .build();

                // UNIQUE 제약이 동시 중복을 차단한다.
                // 두 번째 요청은 InnoDB 유니크 인덱스 락에서 대기 → 선행 커밋 후 DuplicateKey
                pointHistoryRepository.saveAndFlush(pending);

                // 잔액 적립 + snapshot 확정 (같은 트랜잭션)
                // @Modifying(clearAutomatically=true)가 영속성 컨텍스트를 초기화하므로
                // save()로 pending을 재영속화해야 confirm()의 변경이 커밋에 반영된다
                memberRepository.earnPoint(request.getMemberId(), normalizedAmount);
                Member updated = memberRepository.findByIdAndDeletedAtIsNull(request.getMemberId())
                        .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));
                pending = pointHistoryRepository.save(pending);
                pending.confirm(updated.getPointBalance());

                return PointResult.of(new EarnResponse(pending));
            });
        } catch (DataIntegrityViolationException e) {
            // 트랜잭션이 롤백된 후 여기에 도달. auto-commit read로 선행 요청의 확정 결과를 조회한다.
            // InnoDB 유니크 인덱스 락 덕분에 선행 트랜잭션은 이미 커밋된 상태.
            PointHistory winner = pointHistoryRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> e); // 키로 못 찾으면 다른 제약 위반 → 원래 예외 전파
            return handleExistingEarn(winner, requestHash);
        }
    }

    /**
     * 이미 존재하는 earn 이력에 대한 응답 매핑.
     *
     * SUCCEEDED + 해시 일치 → 200 ALREADY_PROCESSED
     * FAILED    + 해시 일치 → 최초와 동일한 실패 응답 재현
     * 해시 불일치           → 409 IDEMPOTENCY_KEY_CONFLICT
     * PENDING (방어적)      → 409 — 단일 트랜잭션에서는 구조적으로 불가하나,
     *                         락 타임아웃 등 극단적 경우에 대비
     */
    private PointResult<EarnResponse> handleExistingEarn(PointHistory history, String requestHash) {
        if (history.getStatus() == PointTransactionStatus.PENDING) {
            log.warn("PENDING 상태 이력 발견 (idempotencyKey={}). "
                    + "선행 요청이 처리 중이거나 비정상 잔재. 재시도 필요.", history.getIdempotencyKey());
            throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        if (!requestHash.equals(history.getRequestHash())) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        return PointResult.alreadyProcessed(new EarnResponse(history));
    }

    // ─── spend ────────────────────────────────────────────────

    @Override
    public PointResult<SpendResponse> spend(String idempotencyKey, SpendRequest request) {
        validateIdempotencyKey(idempotencyKey);
        validateAmount(request.getAmount());

        PointReferenceType refType = parseReferenceType(request.getReferenceType());
        PointHistoryType histType = parseHistoryType(request.getType());
        BigDecimal normalizedAmount = request.getAmount().setScale(2, RoundingMode.DOWN);
        String requestHash = RequestHashUtil.compute(
                request.getMemberId(), request.getType(),
                request.getAmount(), request.getReferenceType(), request.getReferenceId());

        // 1. 낙관적 검사
        Optional<PointHistory> existing = pointHistoryRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return handleExistingSpend(existing.get(), requestHash);
        }

        // 2. 단일 트랜잭션: PENDING → 차감 → confirm/fail
        //    잔액 부족 시 FAILED 상태를 커밋하기 위해 callback에서 예외를 던지지 않고
        //    null을 반환한 뒤 트랜잭션 커밋 후 바깥에서 CustomException을 던진다.
        try {
            PointResult<SpendResponse> result = txTemplate.execute(status -> {
                Member member = memberRepository.findByIdAndDeletedAtIsNull(request.getMemberId())
                        .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));

                PointHistory pending = PointHistory.builder()
                        .memberId(request.getMemberId())
                        .type(histType)
                        .amount(normalizedAmount)
                        .balanceSnapshot(BigDecimal.ZERO)
                        .reason(request.getReason())
                        .referenceType(refType)
                        .referenceId(request.getReferenceId())
                        .idempotencyKey(idempotencyKey)
                        .requestHash(requestHash)
                        .status(PointTransactionStatus.PENDING)
                        .build();

                pointHistoryRepository.saveAndFlush(pending);

                int affected = memberRepository.spendPoint(request.getMemberId(), normalizedAmount);
                if (affected == 0) {
                    // 잔액 부족: PENDING → FAILED 확정.
                    // 예외를 던지지 않으므로 트랜잭션이 커밋되어 FAILED 이력이 저장된다.
                    pending = pointHistoryRepository.save(pending);
                    pending.fail(member.getPointBalance(), ErrorCode.POINT_INSUFFICIENT.getCode());
                    return null; // 잔액 부족 시그널
                }

                Member updated = memberRepository.findByIdAndDeletedAtIsNull(request.getMemberId())
                        .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));
                pending = pointHistoryRepository.save(pending);
                pending.confirm(updated.getPointBalance());

                return PointResult.of(new SpendResponse(pending));
            });

            if (result == null) {
                throw new CustomException(ErrorCode.POINT_INSUFFICIENT);
            }
            return result;

        } catch (DataIntegrityViolationException e) {
            PointHistory winner = pointHistoryRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> e);
            return handleExistingSpend(winner, requestHash);
        }
    }

    /**
     * 이미 존재하는 spend 이력에 대한 응답 매핑.
     *
     * SUCCEEDED + 해시 일치 → 최초 성공 응답 재반환
     * FAILED    + 해시 일치 → "같은 요청 → 같은 응답" — 잔액부족이면 다시 POINT_INSUFFICIENT
     * 해시 불일치           → 409
     * PENDING (방어적)      → 409
     */
    private PointResult<SpendResponse> handleExistingSpend(PointHistory history, String requestHash) {
        if (history.getStatus() == PointTransactionStatus.PENDING) {
            log.warn("PENDING 상태 이력 발견 (idempotencyKey={}). "
                    + "선행 요청이 처리 중이거나 비정상 잔재. 재시도 필요.", history.getIdempotencyKey());
            throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        if (!requestHash.equals(history.getRequestHash())) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        if (history.getStatus() == PointTransactionStatus.FAILED) {
            throw new CustomException(ErrorCode.POINT_INSUFFICIENT);
        }
        return PointResult.alreadyProcessed(new SpendResponse(history));
    }

    // ─── getTransaction ──────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public TransactionResponse getTransaction(String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);

        Optional<PointHistory> opt = pointHistoryRepository.findByIdempotencyKey(idempotencyKey);
        if (opt.isEmpty()) {
            return TransactionResponse.builder()
                    .idempotencyKey(idempotencyKey)
                    .status("NOT_FOUND")
                    .build();
        }

        PointHistory history = opt.get();
        String status = switch (history.getStatus()) {
            case SUCCEEDED -> "PROCESSED";
            case FAILED -> "FAILED";
            case PENDING -> "PENDING";
        };

        return TransactionResponse.builder()
                .idempotencyKey(history.getIdempotencyKey())
                .status(status)
                .memberId(history.getMemberId())
                .type(history.getType().name())
                .amount(history.getAmount().toPlainString())
                .referenceType(history.getReferenceType() != null ? history.getReferenceType().name() : null)
                .referenceId(history.getReferenceId())
                .balanceSnapshot(history.getBalanceSnapshot().toPlainString())
                .createdAt(history.getCreatedAt())
                .failReason(history.getFailReason())
                .build();
    }

    // ─── settle / refund (Phase 2.5에서 트랜잭션 경계 수정 예정) ──

    @Override
    @Transactional
    public SettlementResponse settle(String idempotencyKey, SettlementRequest request) {
        validateIdempotencyKey(idempotencyKey);
        if (!idempotencyKey.equals(request.getSettlementId())) {
            throw new CustomException(ErrorCode.INVALID_REQUEST);
        }
        for (SettlementItem item : request.getItems()) {
            validateIdempotencyKey(item.getIdempotencyKey());
        }

        List<BatchItemResult> results = new ArrayList<>();
        for (SettlementItem item : request.getItems()) {
            results.add(processEarnItem(
                    item.getPredictionId(), item.getMemberId(), item.getAmount(),
                    item.getReferenceType(), item.getReferenceId(),
                    item.getReason(), item.getIdempotencyKey(),
                    PointHistoryType.SETTLE_MARKET));
        }

        return SettlementResponse.builder()
                .marketId(request.getMarketId())
                .results(results)
                .build();
    }

    @Override
    @Transactional
    public RefundResponse refund(String idempotencyKey, RefundRequest request) {
        validateIdempotencyKey(idempotencyKey);
        if (!idempotencyKey.equals(request.getRefundId())) {
            throw new CustomException(ErrorCode.INVALID_REQUEST);
        }
        for (RefundItem item : request.getItems()) {
            validateIdempotencyKey(item.getIdempotencyKey());
        }

        List<BatchItemResult> results = new ArrayList<>();
        for (RefundItem item : request.getItems()) {
            PointHistoryType histType = "INSIGHT_REPORT".equals(item.getReferenceType())
                    ? PointHistoryType.REFUND_INSIGHT
                    : PointHistoryType.REFUND_MARKET;
            results.add(processEarnItem(
                    item.getPredictionId(), item.getMemberId(), item.getAmount(),
                    item.getReferenceType(), item.getReferenceId(),
                    item.getReason(), item.getIdempotencyKey(),
                    histType));
        }

        return RefundResponse.builder()
                .marketId(request.getMarketId())
                .results(results)
                .build();
    }

    private BatchItemResult processEarnItem(Long predictionId, Long memberId, BigDecimal amount,
                                            String referenceType, Long referenceId,
                                            String reason, String idempotencyKey,
                                            PointHistoryType histType) {
        Optional<PointHistory> existing = pointHistoryRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            PointHistory history = existing.get();
            String newHash = RequestHashUtil.compute(memberId, histType.name(), amount, referenceType, referenceId);
            if (newHash.equals(history.getRequestHash())) {
                return BatchItemResult.builder()
                        .predictionId(predictionId)
                        .memberId(memberId)
                        .status("ALREADY_PROCESSED")
                        .amount(history.getAmount().toPlainString())
                        .balanceSnapshot(history.getBalanceSnapshot().toPlainString())
                        .build();
            }
            return BatchItemResult.builder()
                    .predictionId(predictionId)
                    .memberId(memberId)
                    .status("FAILED")
                    .errorCode(ErrorCode.IDEMPOTENCY_KEY_CONFLICT.getCode())
                    .amount(amount != null ? amount.toPlainString() : null)
                    .build();
        }

        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return BatchItemResult.builder()
                    .predictionId(predictionId)
                    .memberId(memberId)
                    .status("FAILED")
                    .errorCode(ErrorCode.POINT_INVALID_AMOUNT.getCode())
                    .amount(amount != null ? amount.toPlainString() : null)
                    .build();
        }

        PointReferenceType refType = null;
        if (referenceType != null && !referenceType.isBlank()) {
            try {
                refType = PointReferenceType.valueOf(referenceType);
            } catch (IllegalArgumentException e) {
                return BatchItemResult.builder()
                        .predictionId(predictionId)
                        .memberId(memberId)
                        .status("FAILED")
                        .errorCode(ErrorCode.POINT_INVALID_REFERENCE_TYPE.getCode())
                        .amount(amount.toPlainString())
                        .build();
            }
        }

        Optional<Member> memberOpt = memberRepository.findById(memberId);
        if (memberOpt.isEmpty()) {
            return BatchItemResult.builder()
                    .predictionId(predictionId)
                    .memberId(memberId)
                    .status("FAILED")
                    .errorCode(ErrorCode.MEMBER_NOT_FOUND.getCode())
                    .amount(amount.toPlainString())
                    .build();
        }

        BigDecimal normalizedAmount = amount.setScale(2, RoundingMode.DOWN);
        String requestHash = RequestHashUtil.compute(memberId, histType.name(), amount, referenceType, referenceId);

        memberRepository.earnPoint(memberId, normalizedAmount);

        Member updated = memberRepository.findById(memberId)
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));

        PointHistory history = PointHistory.builder()
                .memberId(memberId)
                .type(histType)
                .amount(normalizedAmount)
                .balanceSnapshot(updated.getPointBalance())
                .reason(reason)
                .referenceType(refType)
                .referenceId(referenceId)
                .idempotencyKey(idempotencyKey)
                .requestHash(requestHash)
                .status(PointTransactionStatus.SUCCEEDED)
                .build();

        pointHistoryRepository.save(history);

        return BatchItemResult.builder()
                .predictionId(predictionId)
                .memberId(memberId)
                .status("PROCESSED")
                .amount(normalizedAmount.toPlainString())
                .balanceSnapshot(updated.getPointBalance().toPlainString())
                .build();
    }

    // ─── 공통 검증 ────────────────────────────────────────────

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
    }

    private void validateAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new CustomException(ErrorCode.POINT_INVALID_AMOUNT);
        }
    }

    private PointReferenceType parseReferenceType(String referenceType) {
        if (referenceType == null || referenceType.isBlank()) {
            return null;
        }
        try {
            return PointReferenceType.valueOf(referenceType);
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.POINT_INVALID_REFERENCE_TYPE);
        }
    }

    private PointHistoryType parseHistoryType(String type) {
        if (type == null || type.isBlank()) {
            throw new CustomException(ErrorCode.VALIDATION_FAILED);
        }
        try {
            return PointHistoryType.valueOf(type);
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.VALIDATION_FAILED);
        }
    }
}
