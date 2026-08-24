package com.todongsan.memberpointservice.point.entity;

public enum PointTransactionStatus {
    PENDING,    // 선삽입 예약 (UNIQUE 제약으로 동시 중복 차단)
    SUCCEEDED,  // 처리 성공
    FAILED      // 처리 실패 (예: POINT_INSUFFICIENT)
}
