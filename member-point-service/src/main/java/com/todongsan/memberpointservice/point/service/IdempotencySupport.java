package com.todongsan.memberpointservice.point.service;

import com.todongsan.memberpointservice.point.entity.PointHistory;
import com.todongsan.memberpointservice.point.repository.PointHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * PENDING 선삽입 시 UNIQUE 위반(DataIntegrityViolationException) 후 재조회용.
 *
 * 왜 별도 빈인가:
 * 1. saveAndFlush 실패 후 JPA 세션이 깨짐 -> entityManager.clear()로 세션 복구
 * 2. REPEATABLE READ 스냅샷 때문에 같은 트랜잭션에서는 선행 커밋된 row를 못 볼 수 있음
 * 3. REQUIRES_NEW로 새 트랜잭션(새 스냅샷)을 열면 확정된 row를 확실히 읽을 수 있음
 *
 * 프로덕션 참고: InnoDB에서 두 번째 요청의 동일 키 INSERT는
 * 첫 트랜잭션이 커밋될 때까지 유니크 인덱스 락에서 대기하다가,
 * 커밋 후 DuplicateKey로 떨어진다. 즉 재조회 시점에는 선행 트랜잭션이
 * 이미 SUCCEEDED/FAILED로 확정된 상태이므로 PENDING을 만날 일이 구조적으로 거의 없다.
 */
@Component
@RequiredArgsConstructor
public class IdempotencySupport {

    private final PointHistoryRepository pointHistoryRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<PointHistory> findByKeyInNewTransaction(String idempotencyKey) {
        return pointHistoryRepository.findByIdempotencyKey(idempotencyKey);
    }
}
