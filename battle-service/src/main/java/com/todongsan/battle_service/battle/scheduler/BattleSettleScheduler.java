package com.todongsan.battle_service.battle.scheduler;

import com.todongsan.battle_service.battle.entity.Battle;
import com.todongsan.battle_service.battle.repository.BattleRepository;
import com.todongsan.battle_service.outbox.service.OutboxEventCreator;
import com.todongsan.battle_service.vote.entity.BattleVote;
import com.todongsan.battle_service.vote.repository.BattleVoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;

/**
 * Battle 정산 스케줄러.
 *
 * 기존: REST 동기 호출(Battle → Member-Point) + RetryQueue
 * 변경: Transactional Outbox 패턴 — 정산 TX 안에서 outbox_event INSERT,
 *       폴링 퍼블리셔가 Kafka로 발행, Member-Point 컨슈머가 earn() 호출.
 *
 * 핵심: 비즈니스 변경(정산 확정)과 이벤트 기록이 같은 트랜잭션이므로
 *       "정산은 됐는데 보상 이벤트 유실" 문제(이중 쓰기)가 원천 차단된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BattleSettleScheduler {

    private static final BigDecimal VOTE_WIN_REWARD = BigDecimal.valueOf(10);
    private static final String DRAW = "DRAW";

    private final BattleRepository battleRepository;
    private final BattleVoteRepository battleVoteRepository;
    private final OutboxEventCreator outboxEventCreator;
    private final TransactionTemplate txTemplate;

    @Scheduled(fixedDelay = 60000)
    public void settleClosedBattles() {
        List<Long> battleIds = txTemplate.execute(status ->
                battleRepository.findUnsettledClosedBattles().stream()
                        .map(Battle::getId)
                        .toList());

        if (battleIds == null) return;
        for (Long battleId : battleIds) {
            try {
                settleOneBattle(battleId);
            } catch (Exception e) {
                log.error("Battle [{}] 정산 실패", battleId, e);
            }
        }
    }

    /**
     * Battle 한 건 정산.
     * 하나의 트랜잭션에서: 승자 확정 + 승자별 outbox_event INSERT + Battle settled 처리.
     * 외부 REST 호출 없음 — 보상 지급은 Kafka 컨슈머(Member-Point)가 비동기로 처리한다.
     */
    private void settleOneBattle(Long battleId) {
        txTemplate.executeWithoutResult(status -> {
            Battle battle = battleRepository.findById(battleId).orElse(null);
            if (battle == null || battle.isSettled()) {
                return;
            }

            if (DRAW.equals(battle.getWinningOption())) {
                battle.settle(BigDecimal.ZERO);
                log.info("Battle [{}] settled as DRAW", battleId);
                return;
            }

            List<BattleVote> winnerVotes = battleVoteRepository
                    .findByBattleIdAndSelectedOptionAndIsRewardedFalse(battleId, battle.getWinningOption());

            for (BattleVote vote : winnerVotes) {
                String idempotencyKey = settleKey(battleId, vote.getMemberId());
                outboxEventCreator.createRewardEvent(
                        battleId, vote.getMemberId(),
                        "EARN_VOTE_WIN", VOTE_WIN_REWARD,
                        "배틀 승리 보상", idempotencyKey);
                vote.markRewarded();
            }

            battle.settle(VOTE_WIN_REWARD);
            log.info("Battle [{}] settled. outbox events={}", battleId, winnerVotes.size());
        });
    }

    private String settleKey(Long battleId, Long memberId) {
        return "battle:settle:" + battleId + ":member:" + memberId;
    }
}
