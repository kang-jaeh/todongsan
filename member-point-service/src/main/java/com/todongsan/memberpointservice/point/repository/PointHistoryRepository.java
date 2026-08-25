package com.todongsan.memberpointservice.point.repository;

import com.todongsan.memberpointservice.point.entity.PointHistory;
import com.todongsan.memberpointservice.point.entity.PointTransactionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface PointHistoryRepository extends JpaRepository<PointHistory, Long> {

    // 멱등성 키로 단건 조회
    Optional<PointHistory> findByIdempotencyKey(String idempotencyKey);

    // 회원 전체 이력 페이징 조회
    Page<PointHistory> findByMemberId(Long memberId, Pageable pageable);

    // 회원 이력 type prefix 필터 페이징 조회 (EARN_, SPEND_, SETTLE_, REFUND_)
    @Query(
            value = "SELECT * FROM point_history WHERE member_id = :memberId AND type LIKE CONCAT(:typePrefix, '%') ORDER BY created_at DESC",
            countQuery = "SELECT COUNT(*) FROM point_history WHERE member_id = :memberId AND type LIKE CONCAT(:typePrefix, '%')",
            nativeQuery = true
    )
    Page<PointHistory> findByMemberIdAndTypeStartingWith(
            @Param("memberId") Long memberId,
            @Param("typePrefix") String typePrefix,
            Pageable pageable);

    // 회원 전체 이력 (성공 건만) 페이징 조회
    Page<PointHistory> findByMemberIdAndStatusOrderByCreatedAtDesc(
            Long memberId, PointTransactionStatus status, Pageable pageable);

    // 회원 이력 type prefix 필터 + 성공 건만 페이징 조회
    @Query(
            value = "SELECT * FROM point_history WHERE member_id = :memberId AND type LIKE CONCAT(:typePrefix, '%') AND status = 'SUCCEEDED' ORDER BY created_at DESC",
            countQuery = "SELECT COUNT(*) FROM point_history WHERE member_id = :memberId AND type LIKE CONCAT(:typePrefix, '%') AND status = 'SUCCEEDED'",
            nativeQuery = true
    )
    Page<PointHistory> findByMemberIdAndTypeStartingWithAndStatusSucceeded(
            @Param("memberId") Long memberId,
            @Param("typePrefix") String typePrefix,
            Pageable pageable);

    // 정합성 대사: 타입+상태별 금액 합계
    @Query(value = "SELECT COALESCE(SUM(amount), 0) FROM point_history " +
            "WHERE type = :type AND status = 'SUCCEEDED' AND reference_type = :refType AND reference_id = :refId",
            nativeQuery = true)
    BigDecimal sumAmountByTypeAndReference(
            @Param("type") String type,
            @Param("refType") String refType,
            @Param("refId") Long refId);

    // 정합성 대사: 특정 마켓의 SPEND_MARKET 성공 합
    @Query(value = "SELECT COALESCE(SUM(amount), 0) FROM point_history " +
            "WHERE type = 'SPEND_MARKET' AND status = 'SUCCEEDED' AND reference_type = 'MARKET_PREDICTION' " +
            "AND reference_id IN (SELECT id FROM point_history WHERE 1=0)", // placeholder
            nativeQuery = true)
    BigDecimal sumSpendMarketByReferenceIds(@Param("ids") List<Long> predictionIds);
}
