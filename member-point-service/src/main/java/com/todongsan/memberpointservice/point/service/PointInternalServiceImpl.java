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
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PointInternalServiceImpl implements PointInternalService {

    private final MemberRepository memberRepository;
    private final PointHistoryRepository pointHistoryRepository;
    private final EntityManager entityManager;
    private final IdempotencySupport idempotencySupport;

    // ─── earn ─────────────────────────────────────────────────

    @Override
    @Transactional
    public PointResult<EarnResponse> earn(String idempotencyKey, EarnRequest request) {
        validateIdempotencyKey(idempotencyKey);
        validateAmount(request.getAmount());

        PointReferenceType refType = parseReferenceType(request.getReferenceType());
        PointHistoryType histType = parseHistoryType(request.getType());
        BigDecimal normalizedAmount = request.getAmount().setScale(2, RoundingMode.DOWN);
        String requestHash = RequestHashUtil.compute(
                request.getMemberId(), request.getType(),
                request.getAmount(), request.getReferenceType(), request.getReferenceId());

        // 1. 낙관적 검사 — 대부분의 중복 요청은 여기서 걸린다
        Optional<PointHistory> existing = pointHistoryRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return handleExistingEarn(existing.get(), requestHash);
        }

        // 2. 회원 존재 확인
        memberRepository.findByIdAndDeletedAtIsNull(request.getMemberId())
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));

        // 3. PENDING 선삽입 — UNIQUE 제약이 동시 중복을 차단한다.
        //    InnoDB에서 동일 키 INSERT는 선행 트랜잭션 커밋까지 유니크 인덱스 락에서 대기 후
        //    DuplicateKey로 떨어진다. 즉 "같은 키 동시 요청은 직렬화된다."
        PointHistory pending = PointHistory.builder()
                .memberId(request.getMemberId())
                .type(histType)
                .amount(normalizedAmount)
                .balanceSnapshot(BigDecimal.ZERO) // PENDING placeholder, confirm()에서 확정
                .reason(request.getReason())
                .referenceType(refType)
                .referenceId(request.getReferenceId())
                .idempotencyKey(idempotencyKey)
                .requestHash(requestHash)
                .status(PointTransactionStatus.PENDING)
                .build();

        try {
            pointHistoryRepository.saveAndFlush(pending);
        } catch (DataIntegrityViolationException e) {
            // 키로 재조회하여 idempotency_key 유니크 위반인지 확인한다.
            // 다른 제약(FK 등) 위반이면 원래 예외를 전파한다.
            return handleUniqueViolationForEarn(idempotencyKey, requestHash, e);
        }

        // 4. 잔액 적립 + snapshot 확정 (같은 트랜잭션 — 원자적)
        memberRepository.earnPoint(request.getMemberId(), normalizedAmount);
        Member updated = memberRepository.findByIdAndDeletedAtIsNull(request.getMemberId())
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));

        // 5. PENDING -> SUCCEEDED 확정. JPA 더티체킹이 커밋 시 UPDATE를 발행한다.
        pending.confirm(updated.getPointBalance());

        return PointResult.of(new EarnResponse(pending));
    }

    /**
     * 이미 존재하는 earn 이력에 대한 응답 매핑 (재조회 결과 분기).
     *
     * SUCCEEDED + 해시 일치 → 저장된 결과로 200 (ALREADY_PROCESSED)
     * FAILED    + 해시 일치 → 최초와 동일한 실패 응답 재현
     * 해시 불일치           → 409 IDEMPOTENCY_KEY_CONFLICT
     * PENDING (방어적)      → 409 — 단일 트랜잭션에서는 구조적으로 불가하나,
     *                         락 타임아웃(innodb_lock_wait_timeout) 등 극단적 경우에 대비
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
        // "같은 요청 -> 같은 응답" 원칙: 성공이든 실패든 최초 결과를 그대로 반환
        return PointResult.alreadyProcessed(new EarnResponse(history));
    }

    private PointResult<EarnResponse> handleUniqueViolationForEarn(
            String idempotencyKey, String requestHash, DataIntegrityViolationException original) {
        // 세션 복구 후 새 트랜잭션에서 재조회
        entityManager.clear();
        PointHistory winner = idempotencySupport.findByKeyInNewTransaction(idempotencyKey)
                .orElseThrow(() -> original); // 키로 못 찾으면 다른 제약 위반 -> 원래 예외 전파
        return handleExistingEarn(winner, requestHash);
    }

    // ─── spend ────────────────────────────────────────────────

    @Override
    @Transactional(noRollbackFor = CustomException.class)
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

        // 2. 회원 존재 확인
        Member member = memberRepository.findByIdAndDeletedAtIsNull(request.getMemberId())
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));

        // 3. PENDING 선삽입
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

        try {
            pointHistoryRepository.saveAndFlush(pending);
        } catch (DataIntegrityViolationException e) {
            return handleUniqueViolationForSpend(idempotencyKey, requestHash, e);
        }

        // 4. Atomic 차감 — affected row로 성공/잔액부족 판단
        int affected = memberRepository.spendPoint(request.getMemberId(), normalizedAmount);
        if (affected == 0) {
            // 잔액 부족: PENDING -> FAILED 확정.
            // noRollbackFor = CustomException.class이므로 FAILED 이력이 커밋된다.
            pending.fail(member.getPointBalance(), ErrorCode.POINT_INSUFFICIENT.getCode());
            throw new CustomException(ErrorCode.POINT_INSUFFICIENT);
        }

        // 5. 확정
        Member updated = memberRepository.findByIdAndDeletedAtIsNull(request.getMemberId())
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));
        pending.confirm(updated.getPointBalance());

        return PointResult.of(new SpendResponse(pending));
    }

    /**
     * 이미 존재하는 spend 이력에 대한 응답 매핑.
     *
     * SUCCEEDED + 해시 일치 → 최초 성공 응답 재반환
     * FAILED    + 해시 일치 → 최초와 동일한 실패 응답 재현 (잔액부족이면 다시 POINT_INSUFFICIENT)
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
        // "같은 요청 -> 같은 응답" — 실패에도 적용
        if (history.getStatus() == PointTransactionStatus.FAILED) {
            throw new CustomException(ErrorCode.POINT_INSUFFICIENT);
        }
        return PointResult.alreadyProcessed(new SpendResponse(history));
    }

    private PointResult<SpendResponse> handleUniqueViolationForSpend(
            String idempotencyKey, String requestHash, DataIntegrityViolationException original) {
        entityManager.clear();
        PointHistory winner = idempotencySupport.findByKeyInNewTransaction(idempotencyKey)
                .orElseThrow(() -> original);
        return handleExistingSpend(winner, requestHash);
    }

    // ─── getTransaction ──────────────────────────────────────

    @Override
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

    /**
     * settle/refund 개별 항목 처리.
     * 배치 내에서 예외를 전파하지 않고 BatchItemResult로 결과를 반환한다.
     *
     * 참고: 이 메서드는 부모 트랜잭션(settle/refund) 안에서 실행되므로
     * PENDING 선삽입 패턴을 적용하면 한 항목의 UNIQUE 위반이 전체 세션을 깨뜨린다.
     * Phase 2.5에서 항목별 트랜잭션 분리 시 함께 수정 예정.
     * 현재는 낙관적 검사(find-then-insert)로 유지한다.
     */
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
