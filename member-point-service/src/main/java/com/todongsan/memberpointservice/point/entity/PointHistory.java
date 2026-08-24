package com.todongsan.memberpointservice.point.entity;

import com.todongsan.memberpointservice.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

// 포인트 적립 / 차감 / 정산 이력
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "point_history")
public class PointHistory extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 회원 ID (JPA 관계 없이 BIGINT로만 보관)
    @Column(nullable = false)
    private Long memberId;

    // 이력 유형
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private PointHistoryType type;

    // 변동 포인트 (항상 양수)
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    // 처리 후 잔액 스냅샷
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal balanceSnapshot;

    @Column(length = 255)
    private String reason;

    // 참조 도메인 유형
    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private PointReferenceType referenceType;

    // 참조 도메인 ID
    private Long referenceId;

    // 멱등성 키 (중복 처리 방지)
    @Column(nullable = false, unique = true, length = 150)
    private String idempotencyKey;

    // 요청 해시 (SHA-256, 충돌 감지)
    @Column(length = 64)
    private String requestHash;

    // 처리 상태 (SUCCEEDED / FAILED). POINT_INSUFFICIENT 등 실패도 이력으로 저장
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PointTransactionStatus status;

    // 실패 사유 ErrorCode. status=FAILED 일 때만 값이 있음
    @Column(length = 50)
    private String failReason;

    @Builder
    private PointHistory(Long memberId, PointHistoryType type,
                         BigDecimal amount, BigDecimal balanceSnapshot,
                         String reason, PointReferenceType referenceType,
                         Long referenceId, String idempotencyKey, String requestHash,
                         PointTransactionStatus status, String failReason) {
        this.memberId = memberId;
        this.type = type;
        this.amount = amount;
        this.balanceSnapshot = balanceSnapshot;
        this.reason = reason;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.status = status != null ? status : PointTransactionStatus.SUCCEEDED;
        this.failReason = failReason;
    }

    /**
     * PENDING → SUCCEEDED 확정. 잔액 UPDATE 후 확정된 balanceSnapshot을 함께 기록한다.
     */
    public void confirm(BigDecimal confirmedBalance) {
        if (this.status != PointTransactionStatus.PENDING) {
            throw new IllegalStateException("PENDING 상태에서만 confirm 가능: " + this.status);
        }
        this.status = PointTransactionStatus.SUCCEEDED;
        this.balanceSnapshot = confirmedBalance;
    }

    /**
     * PENDING → FAILED 확정. 잔액 변경 없이 현재 잔액을 snapshot으로 기록한다.
     */
    public void fail(BigDecimal currentBalance, String failReason) {
        if (this.status != PointTransactionStatus.PENDING) {
            throw new IllegalStateException("PENDING 상태에서만 fail 가능: " + this.status);
        }
        this.status = PointTransactionStatus.FAILED;
        this.balanceSnapshot = currentBalance;
        this.failReason = failReason;
    }

}
