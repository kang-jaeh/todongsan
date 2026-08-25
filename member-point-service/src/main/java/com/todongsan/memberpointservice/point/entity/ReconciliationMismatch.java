package com.todongsan.memberpointservice.point.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "reconciliation_mismatch")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReconciliationMismatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "check_type", nullable = false, length = 50)
    private String checkType;

    @Column(name = "market_id")
    private Long marketId;

    @Column(name = "expected_value", nullable = false, precision = 18, scale = 2)
    private BigDecimal expectedValue;

    @Column(name = "actual_value", nullable = false, precision = 18, scale = 2)
    private BigDecimal actualValue;

    @Column(name = "diff_value", nullable = false, precision = 18, scale = 2)
    private BigDecimal diffValue;

    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(nullable = false)
    private boolean resolved;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public ReconciliationMismatch(String checkType, Long marketId,
                                   BigDecimal expectedValue, BigDecimal actualValue,
                                   BigDecimal diffValue, String detail) {
        this.checkType = checkType;
        this.marketId = marketId;
        this.expectedValue = expectedValue;
        this.actualValue = actualValue;
        this.diffValue = diffValue;
        this.detail = detail;
        this.resolved = false;
        this.createdAt = LocalDateTime.now();
    }
}
