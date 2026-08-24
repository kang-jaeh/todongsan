package com.todongsan.battle_service.battle.service;

import com.todongsan.battle_service.battle.dto.request.BattleCreateRequest;
import com.todongsan.battle_service.battle.dto.response.*;
import com.todongsan.battle_service.battle.entity.Battle;
import com.todongsan.battle_service.battle.entity.BattleStatus;
import com.todongsan.battle_service.battle.repository.BattleRepository;
import com.todongsan.battle_service.comment.repository.CommentRepository;
import com.todongsan.battle_service.global.exception.CustomException;
import com.todongsan.battle_service.global.exception.ErrorCode;
import com.todongsan.battle_service.outbox.service.OutboxEventCreator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BattleServiceImpl implements BattleService {

    private static final BigDecimal APPROVED_REWARD = BigDecimal.valueOf(20);

    private final BattleRepository battleRepository;
    private final CommentRepository commentRepository;
    private final OutboxEventCreator outboxEventCreator;
    private final TransactionTemplate txTemplate;

    @Override
    @Transactional
    public BattleCreateResponse createBattle(Long memberId, BattleCreateRequest request) {
        validatePeriod(request.getStartAt(), request.getEndAt());

        Battle battle = Battle.builder()
                .title(request.getTitle())
                .description(request.getDescription())
                .optionA(request.getOptionA())
                .optionB(request.getOptionB())
                .sido(request.getSido())
                .sigu(request.getSigu())
                .createdBy(memberId)
                .startAt(request.getStartAt())
                .endAt(request.getEndAt())
                .build();

        return BattleCreateResponse.from(battleRepository.save(battle));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BattleListResponse> getBattles(String status, String sort, int page, int size) {
        BattleStatus battleStatus = parsePublicStatus(status);
        Sort sorting = "popular".equalsIgnoreCase(sort)
                ? Sort.by("voteCount").descending()
                : Sort.by("createdAt").descending();
        PageRequest pageable = PageRequest.of(page, size, sorting);
        return battleRepository.findByStatusAndDeletedAtIsNull(battleStatus, pageable)
                .map(b -> BattleListResponse.from(b, commentRepository.countByBattleIdAndDeletedAtIsNull(b.getId())));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BattleDetailResponse> getPendingBattles(int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return battleRepository.findByStatusAndDeletedAtIsNull(BattleStatus.PENDING, pageable)
                .map(BattleDetailResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MyCreatedBattleResponse> getMyCreatedBattles(Long memberId, String status, int page, int size) {
        List<BattleStatus> statuses = parseAllStatuses(status);
        PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return battleRepository.findByCreatedByAndStatusInAndDeletedAtIsNull(memberId, statuses, pageable)
                .map(MyCreatedBattleResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public BattleDetailResponse getBattle(Long battleId) {
        Battle battle = battleRepository
                .findByIdAndStatusInAndDeletedAtIsNull(battleId,
                        List.of(BattleStatus.ACTIVE, BattleStatus.CLOSED))
                .orElseThrow(() -> new CustomException(ErrorCode.BATTLE_NOT_FOUND));
        return BattleDetailResponse.from(battle);
    }

    @Override
    public BattleStatusResponse approveBattle(Long battleId) {
        // 상태 전이 + 보상 이벤트를 한 트랜잭션에서 처리
        Battle battle = txTemplate.execute(status -> {
            Battle b = findByIdOrThrow(battleId);
            if (b.getStatus() != BattleStatus.PENDING) {
                throw new CustomException(ErrorCode.BATTLE_INVALID_STATUS);
            }
            b.approve();

            // 승인 보상 이벤트 outbox INSERT (같은 트랜잭션)
            outboxEventCreator.createRewardEvent(
                    battleId, b.getCreatedBy(), "EARN_BATTLE_APPROVED", APPROVED_REWARD,
                    "Battle 주제 등록 승인 보상",
                    "battle:approved:" + battleId + ":member:" + b.getCreatedBy());

            return b;
        });

        return BattleStatusResponse.from(battle);
    }

    @Override
    @Transactional
    public BattleStatusResponse rejectBattle(Long battleId) {
        Battle battle = findByIdOrThrow(battleId);
        if (battle.getStatus() != BattleStatus.PENDING) {
            throw new CustomException(ErrorCode.BATTLE_INVALID_STATUS);
        }
        battle.reject();
        return BattleStatusResponse.from(battle);
    }

    @Override
    @Transactional
    public BattleStatusResponse cancelBattle(Long battleId) {
        Battle battle = findByIdOrThrow(battleId);
        if (battle.getStatus() != BattleStatus.ACTIVE) {
            throw new CustomException(ErrorCode.BATTLE_INVALID_STATUS);
        }
        battle.cancel();
        return BattleStatusResponse.from(battle);
    }

    @Override
    @Transactional
    public BattleStatusResponse cancelBattleByUser(Long battleId, Long memberId) {
        Battle battle = findByIdOrThrow(battleId);
        if (battle.getStatus() != BattleStatus.PENDING) {
            throw new CustomException(ErrorCode.BATTLE_INVALID_STATUS);
        }
        if (!battle.getCreatedBy().equals(memberId)) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }
        battle.cancel();
        return BattleStatusResponse.from(battle);
    }

    @Override
    @Transactional(readOnly = true)
    public BattleDetailResponse getBattleInternal(Long battleId) {
        Battle battle = findByIdOrThrow(battleId);
        return BattleDetailResponse.from(battle);
    }

    @Override
    @Transactional
    public List<Long> closeExpiredBattles() {
        List<Battle> expired = battleRepository.findExpiredActiveBattles(LocalDateTime.now());
        List<Long> closedIds = new ArrayList<>();
        for (Battle battle : expired) {
            String winningOption = determineWinner(battle);
            battle.close(winningOption);
            closedIds.add(battle.getId());
            log.info("Battle [{}] closed. winner={}", battle.getId(), winningOption);
        }
        return closedIds;
    }

    private String determineWinner(Battle battle) {
        if (battle.getOptionACount() > battle.getOptionBCount()) return "A";
        if (battle.getOptionBCount() > battle.getOptionACount()) return "B";
        return "DRAW";
    }

    private Battle findByIdOrThrow(Long battleId) {
        return battleRepository.findByIdAndDeletedAtIsNull(battleId)
                .orElseThrow(() -> new CustomException(ErrorCode.BATTLE_NOT_FOUND));
    }

    private void validatePeriod(LocalDateTime startAt, LocalDateTime endAt) {
        if (endAt.isBefore(startAt) || endAt.isEqual(startAt)) {
            throw new CustomException(ErrorCode.BATTLE_INVALID_PERIOD);
        }
        if (endAt.isBefore(LocalDateTime.now())) {
            throw new CustomException(ErrorCode.BATTLE_INVALID_PERIOD);
        }
    }

    private BattleStatus parsePublicStatus(String status) {
        if (status == null || status.equalsIgnoreCase("ACTIVE")) return BattleStatus.ACTIVE;
        if (status.equalsIgnoreCase("CLOSED")) return BattleStatus.CLOSED;
        throw new CustomException(ErrorCode.VALIDATION_FAILED);
    }

    // 본인 배틀 조회: 모든 상태 허용, 콤마 구분, 미지정 시 전체
    private List<BattleStatus> parseAllStatuses(String status) {
        if (status == null || status.isBlank()) {
            return List.of(BattleStatus.values());
        }
        List<BattleStatus> result = new ArrayList<>();
        for (String token : status.split(",")) {
            String s = token.trim().toUpperCase();
            if (s.isEmpty()) continue;
            try {
                BattleStatus parsed = BattleStatus.valueOf(s);
                if (!result.contains(parsed)) result.add(parsed);
            } catch (IllegalArgumentException e) {
                throw new CustomException(ErrorCode.VALIDATION_FAILED);
            }
        }
        if (result.isEmpty()) {
            return List.of(BattleStatus.values());
        }
        return result;
    }
}
