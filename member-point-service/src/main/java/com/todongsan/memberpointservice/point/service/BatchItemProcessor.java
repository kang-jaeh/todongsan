package com.todongsan.memberpointservice.point.service;

import com.todongsan.memberpointservice.global.exception.CustomException;
import com.todongsan.memberpointservice.global.exception.ErrorCode;
import com.todongsan.memberpointservice.global.util.RequestHashUtil;
import com.todongsan.memberpointservice.member.entity.Member;
import com.todongsan.memberpointservice.member.repository.MemberRepository;
import com.todongsan.memberpointservice.point.dto.response.BatchItemResult;
import com.todongsan.memberpointservice.point.entity.PointHistory;
import com.todongsan.memberpointservice.point.entity.PointHistoryType;
import com.todongsan.memberpointservice.point.entity.PointReferenceType;
import com.todongsan.memberpointservice.point.entity.PointTransactionStatus;
import com.todongsan.memberpointservice.point.repository.PointHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * settle/refund 배치의 개별 항목을 독립 트랜잭션(REQUIRES_NEW)으로 처리한다.
 *
 * 왜 별도 빈인가:
 * - Spring AOP 프록시는 self-invocation(같은 클래스 내 호출)을 인터셉트하지 못한다.
 *   PointInternalServiceImpl에서 @Transactional(REQUIRES_NEW) 메서드를 직접 호출하면
 *   부모 트랜잭션에 참여하게 되어 독립 트랜잭션이 보장되지 않는다.
 * - 별도 빈으로 분리하면 프록시를 통해 호출되어 REQUIRES_NEW가 정상 동작한다.
 *
 * 이로써 "부분 성공 허용" 계약과 실제 트랜잭션 경계가 일치한다:
 * - item 3이 실패해도 item 1, 2의 커밋은 유지된다.
 * - 각 항목의 결과가 BatchItemResult로 독립적으로 반환된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchItemProcessor {

    private final MemberRepository memberRepository;
    private final PointHistoryRepository pointHistoryRepository;

    /**
     * 개별 항목을 독립 트랜잭션에서 처리한다.
     * 예외가 발생해도 호출자(settle/refund)로 전파하지 않고 FAILED 결과를 반환한다.
     */
    public BatchItemResult processItem(Long predictionId, Long memberId, BigDecimal amount,
                                        String referenceType, Long referenceId,
                                        String reason, String idempotencyKey,
                                        PointHistoryType histType) {
        try {
            return processItemInNewTransaction(
                    predictionId, memberId, amount, referenceType, referenceId,
                    reason, idempotencyKey, histType);
        } catch (Exception e) {
            // 독립 트랜잭션 실패 시 FAILED 결과 반환 (부분 성공 보장)
            log.warn("Batch item failed: predictionId={}, key={}, error={}",
                    predictionId, idempotencyKey, e.getMessage());
            return BatchItemResult.builder()
                    .predictionId(predictionId)
                    .memberId(memberId)
                    .status("FAILED")
                    .errorCode("INTERNAL_ERROR")
                    .amount(amount != null ? amount.toPlainString() : null)
                    .build();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BatchItemResult processItemInNewTransaction(
            Long predictionId, Long memberId, BigDecimal amount,
            String referenceType, Long referenceId,
            String reason, String idempotencyKey,
            PointHistoryType histType) {

        // 1. 멱등성 검사
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

        // 2. 검증
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

        // findById — 탈퇴 회원도 정산/환불 가능 (정책 준수)
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

        // 3. 적립 + 이력 기록
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
}
