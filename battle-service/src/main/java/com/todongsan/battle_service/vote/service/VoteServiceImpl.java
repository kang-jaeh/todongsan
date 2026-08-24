package com.todongsan.battle_service.vote.service;

import com.todongsan.battle_service.battle.entity.Battle;
import com.todongsan.battle_service.battle.entity.BattleStatus;
import com.todongsan.battle_service.battle.repository.BattleRepository;
import com.todongsan.battle_service.global.exception.CustomException;
import com.todongsan.battle_service.global.exception.ErrorCode;
import com.todongsan.battle_service.outbox.service.OutboxEventCreator;
import com.todongsan.battle_service.vote.dto.request.VoteRequest;
import com.todongsan.battle_service.vote.dto.response.*;
import com.todongsan.battle_service.vote.entity.BattleVote;
import com.todongsan.battle_service.vote.repository.BattleVoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
public class VoteServiceImpl implements VoteService {

    private static final BigDecimal VOTE_REWARD = BigDecimal.valueOf(10);

    private final BattleRepository battleRepository;
    private final BattleVoteRepository battleVoteRepository;
    private final OutboxEventCreator outboxEventCreator;
    private final TransactionTemplate txTemplate;

    private static final long RESULT_OPEN_HOURS = 72;

    @Override
    public VoteResponse vote(Long battleId, Long memberId, VoteRequest request) {
        // 투표 저장 + 집계 + 보상 이벤트를 한 트랜잭션에서 처리.
        // outbox INSERT가 투표 TX 안에 있으므로 이중 쓰기 문제가 없다.
        String option = txTemplate.execute(status -> {
            Battle battle = findActiveOrThrow(battleId);

            if (LocalDateTime.now().isBefore(battle.getStartAt())) {
                throw new CustomException(ErrorCode.BATTLE_CLOSED);
            }

            String opt = request.getOption().toUpperCase();
            if (!opt.equals("A") && !opt.equals("B")) {
                throw new CustomException(ErrorCode.BATTLE_INVALID_OPTION);
            }

            // uq_battle_vote 충돌 → GlobalExceptionHandler에서 BATTLE_ALREADY_VOTED 변환
            battleVoteRepository.save(BattleVote.builder()
                    .battleId(battleId)
                    .memberId(memberId)
                    .selectedOption(opt)
                    .build());

            // 같은 트랜잭션에서 집계 UPDATE (원자성 보장)
            if (opt.equals("A")) {
                battleRepository.incrementOptionA(battleId);
            } else {
                battleRepository.incrementOptionB(battleId);
            }

            // 보상 이벤트 outbox INSERT (같은 트랜잭션)
            outboxEventCreator.createRewardEvent(
                    battleId, memberId, "EARN_VOTE", VOTE_REWARD,
                    "Battle 투표 참여 보상",
                    "battle:vote:" + battleId + ":member:" + memberId);

            return opt;
        });

        return VoteResponse.builder()
                .battleId(battleId)
                .selectedOption(option)
                .message("투표가 완료되었습니다.")
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public VoteResultResponse getResult(Long battleId, Long memberId) {
        Battle battle = battleRepository.findByIdAndDeletedAtIsNull(battleId)
                .orElseThrow(() -> new CustomException(ErrorCode.BATTLE_NOT_FOUND));

        boolean voted = memberId != null && battleVoteRepository.existsByBattleIdAndMemberId(battleId, memberId);
        boolean isActive = battle.getStatus() == BattleStatus.ACTIVE;
        boolean isClosed = battle.getStatus() == BattleStatus.CLOSED;
        boolean past72h = isClosed && battle.getEndAt().plusHours(RESULT_OPEN_HOURS).isBefore(LocalDateTime.now());

        // 결과 공개 정책 분기
        boolean resultVisible = voted || past72h;

        if (!resultVisible) {
            return VoteResultResponse.builder()
                    .battleId(battleId)
                    .status(battle.getStatus().name())
                    .voted(false)
                    .resultVisible(false)
                    .voteCount(battle.getVoteCount())
                    .message(isClosed ? "투표 종료 72시간 후 공개됩니다." : "투표 후 결과를 확인할 수 있습니다.")
                    .build();
        }

        int total = battle.getVoteCount();
        double aRatio = total > 0 ? (double) battle.getOptionACount() / total * 100 : 0;
        double bRatio = total > 0 ? (double) battle.getOptionBCount() / total * 100 : 0;

        return VoteResultResponse.builder()
                .battleId(battleId)
                .status(battle.getStatus().name())
                .voted(voted)
                .resultVisible(true)
                .optionACount(battle.getOptionACount())
                .optionBCount(battle.getOptionBCount())
                .voteCount(total)
                .optionARatio(aRatio)
                .optionBRatio(bRatio)
                .winningOption(battle.getWinningOption())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MyVoteBattleResponse> getMyVotedBattles(Long memberId, String status, int page, int size) {
        List<BattleStatus> statuses = parseMyStatuses(status);
        Pageable pageable = PageRequest.of(page, size); // 정렬은 쿼리의 ORDER BY(최신 투표순)로 처리
        return battleVoteRepository.findMyVotedBattles(memberId, statuses, pageable)
                .map(row -> MyVoteBattleResponse.of((Battle) row[0], (BattleVote) row[1]));
    }

    private List<BattleStatus> parseMyStatuses(String status) {
        if (status == null || status.isBlank()) {
            return List.of(BattleStatus.ACTIVE, BattleStatus.CLOSED);
        }
        List<BattleStatus> result = new ArrayList<>();
        for (String token : status.split(",")) {
            String s = token.trim().toUpperCase();
            if (s.isEmpty()) continue;
            if (s.equals("ACTIVE")) {
                if (!result.contains(BattleStatus.ACTIVE)) result.add(BattleStatus.ACTIVE);
            } else if (s.equals("CLOSED")) {
                if (!result.contains(BattleStatus.CLOSED)) result.add(BattleStatus.CLOSED);
            } else {
                // PENDING/CANCELLED 등 사용자 노출 대상이 아닌 값
                throw new CustomException(ErrorCode.VALIDATION_FAILED);
            }
        }
        if (result.isEmpty()) {
            return List.of(BattleStatus.ACTIVE, BattleStatus.CLOSED);
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public CrossAnalysisResponse getCrossResult(Long battleId) {
        Battle battle = battleRepository.findByIdAndDeletedAtIsNull(battleId)
                .orElseThrow(() -> new CustomException(ErrorCode.BATTLE_NOT_FOUND));

        if (battle.getStatus() != BattleStatus.CLOSED) {
            throw new CustomException(ErrorCode.BATTLE_RESULT_NOT_AVAILABLE);
        }

        // TODO: 교차분석 집계 데이터 조회 (Feature 3)

        return CrossAnalysisResponse.builder()
                .battleId(battleId)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public CertifiedResultResponse getCertifiedResult(Long battleId) {
        Battle battle = battleRepository.findByIdAndDeletedAtIsNull(battleId)
                .orElseThrow(() -> new CustomException(ErrorCode.BATTLE_NOT_FOUND));

        if (battle.getStatus() != BattleStatus.CLOSED) {
            throw new CustomException(ErrorCode.BATTLE_RESULT_NOT_AVAILABLE);
        }

        // TODO: 방문 인증자 필터 집계 데이터 조회 (Feature 3)

        return CertifiedResultResponse.builder()
                .battleId(battleId)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public VoteRawResponse getRawVotes(Long battleId) {
        Battle battle = battleRepository.findByIdAndDeletedAtIsNull(battleId)
                .orElseThrow(() -> new CustomException(ErrorCode.BATTLE_NOT_FOUND));

        List<BattleVote> votes = battleVoteRepository.findByBattleId(battleId);
        return VoteRawResponse.from(battle, votes);
    }

    private Battle findActiveOrThrow(Long battleId) {
        Battle battle = battleRepository.findByIdAndDeletedAtIsNull(battleId)
                .orElseThrow(() -> new CustomException(ErrorCode.BATTLE_NOT_FOUND));

        if (battle.getStatus() == BattleStatus.PENDING) {
            throw new CustomException(ErrorCode.BATTLE_NOT_FOUND);
        }
        if (battle.getStatus() == BattleStatus.CLOSED || battle.getStatus() == BattleStatus.CANCELLED) {
            throw new CustomException(ErrorCode.BATTLE_CLOSED);
        }
        return battle;
    }
}
