package com.todongsan.battle_service.vote.service;

import com.todongsan.battle_service.battle.entity.Battle;
import com.todongsan.battle_service.battle.repository.BattleRepository;
import com.todongsan.battle_service.global.exception.CustomException;
import com.todongsan.battle_service.global.exception.ErrorCode;
import com.todongsan.battle_service.outbox.service.OutboxEventCreator;
import com.todongsan.battle_service.vote.dto.request.VoteRequest;
import com.todongsan.battle_service.vote.dto.response.CrossAnalysisResponse;
import com.todongsan.battle_service.vote.dto.response.MyVoteBattleResponse;
import com.todongsan.battle_service.vote.dto.response.VoteRawResponse;
import com.todongsan.battle_service.vote.dto.response.VoteResponse;
import com.todongsan.battle_service.vote.dto.response.VoteResultResponse;
import com.todongsan.battle_service.vote.entity.BattleVote;
import com.todongsan.battle_service.vote.repository.BattleVoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class VoteServiceImplTest {

    @Mock private BattleRepository battleRepository;
    @Mock private BattleVoteRepository battleVoteRepository;
    @Mock private OutboxEventCreator outboxEventCreator;
    @Mock private TransactionTemplate txTemplate;

    @InjectMocks
    private VoteServiceImpl voteService;

    @BeforeEach
    void setUpTxTemplate() {
        // 실제 트랜잭션 매니저 없이 콜백을 즉시 실행하도록 모킹
        lenient().when(txTemplate.execute(any())).thenAnswer(inv ->
                inv.getArgument(0, TransactionCallback.class).doInTransaction(null));
    }

    // ===================== vote =====================

    @Test
    @DisplayName("투표 성공 - 옵션 A + 보상 지급")
    void vote_success_optionA() {
        Battle battle = activeBattle(LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(7));
        given(battleRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(battle));
        given(battleVoteRepository.save(any(BattleVote.class))).willReturn(new BattleVote());

        VoteResponse response = voteService.vote(1L, 1L, voteRequest("A"));

        assertThat(response.getSelectedOption()).isEqualTo("A");
        verify(battleRepository).incrementOptionA(1L);
        verify(outboxEventCreator).createRewardEvent(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("투표 성공 - 옵션 B")
    void vote_success_optionB() {
        Battle battle = activeBattle(LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(7));
        given(battleRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(battle));
        given(battleVoteRepository.save(any(BattleVote.class))).willReturn(new BattleVote());

        VoteResponse response = voteService.vote(1L, 1L, voteRequest("B"));

        assertThat(response.getSelectedOption()).isEqualTo("B");
        verify(battleRepository).incrementOptionB(1L);
    }

    // RetryQueue 테스트 제거됨 — Kafka+Outbox 전환으로 보상 실패는 컨슈머 쪽에서 DLT로 처리

    @Test
    @DisplayName("투표 실패 - PENDING 상태 Battle → BATTLE_NOT_FOUND")
    void vote_fail_pendingBattle() {
        given(battleRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(pendingBattle()));

        assertThatThrownBy(() -> voteService.vote(1L, 1L, voteRequest("A")))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.BATTLE_NOT_FOUND));
    }

    @Test
    @DisplayName("투표 실패 - CLOSED 상태 Battle → BATTLE_CLOSED")
    void vote_fail_closedBattle() {
        given(battleRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(closedBattle()));

        assertThatThrownBy(() -> voteService.vote(1L, 1L, voteRequest("A")))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.BATTLE_CLOSED));
    }

    @Test
    @DisplayName("투표 실패 - CANCELLED 상태 Battle → BATTLE_CLOSED")
    void vote_fail_cancelledBattle() {
        given(battleRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(cancelledBattle()));

        assertThatThrownBy(() -> voteService.vote(1L, 1L, voteRequest("A")))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.BATTLE_CLOSED));
    }

    @Test
    @DisplayName("투표 실패 - start_at 미도달 → BATTLE_CLOSED")
    void vote_fail_beforeStartAt() {
        Battle battle = activeBattle(LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(7));
        given(battleRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(battle));

        assertThatThrownBy(() -> voteService.vote(1L, 1L, voteRequest("A")))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.BATTLE_CLOSED));
    }

    @Test
    @DisplayName("투표 실패 - 잘못된 옵션 → BATTLE_INVALID_OPTION")
    void vote_fail_invalidOption() {
        Battle battle = activeBattle(LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(7));
        given(battleRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(battle));

        assertThatThrownBy(() -> voteService.vote(1L, 1L, voteRequest("C")))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.BATTLE_INVALID_OPTION));
    }

    // ===================== getResult =====================

    @Test
    @DisplayName("결과 조회 - ACTIVE 배틀, 미투표 → 비공개")
    void getResult_active_notVoted_hidden() {
        Battle battle = activeBattle(LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(7));
        given(battleRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(battle));
        given(battleVoteRepository.existsByBattleIdAndMemberId(1L, 1L)).willReturn(false);

        VoteResultResponse response = voteService.getResult(1L, 1L);

        assertThat(response.isResultVisible()).isFalse();
    }

    @Test
    @DisplayName("결과 조회 - ACTIVE 배틀, 투표 완료 → 공개")
    void getResult_active_voted_visible() {
        Battle battle = activeBattle(LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(7));
        given(battleRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(battle));
        given(battleVoteRepository.existsByBattleIdAndMemberId(1L, 1L)).willReturn(true);

        VoteResultResponse response = voteService.getResult(1L, 1L);

        assertThat(response.isResultVisible()).isTrue();
    }

    @Test
    @DisplayName("결과 조회 - CLOSED 배틀, 72시간 미경과, 미투표 → 비공개")
    void getResult_closed_before72h_notVoted_hidden() {
        Battle battle = closedBattleWithEndAt(LocalDateTime.now().minusHours(1));
        given(battleRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(battle));
        given(battleVoteRepository.existsByBattleIdAndMemberId(1L, 1L)).willReturn(false);

        VoteResultResponse response = voteService.getResult(1L, 1L);

        assertThat(response.isResultVisible()).isFalse();
    }

    @Test
    @DisplayName("결과 조회 - CLOSED 배틀, 72시간 경과 → 공개")
    void getResult_closed_after72h_visible() {
        Battle battle = closedBattleWithEndAt(LocalDateTime.now().minusHours(73));
        given(battleRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(battle));
        given(battleVoteRepository.existsByBattleIdAndMemberId(1L, 1L)).willReturn(false);

        VoteResultResponse response = voteService.getResult(1L, 1L);

        assertThat(response.isResultVisible()).isTrue();
    }

    @Test
    @DisplayName("결과 조회 실패 - Battle 없음")
    void getResult_fail_notFound() {
        given(battleRepository.findByIdAndDeletedAtIsNull(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> voteService.getResult(999L, null))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.BATTLE_NOT_FOUND));
    }

    // ===================== getMyVotedBattles =====================

    @Test
    @DisplayName("내 참여 배틀 - 정산 승리 → isWin=true, rewardAmount 노출")
    void getMyVotedBattles_settledWin_mapped() {
        Battle battle = settledBattle("A", BigDecimal.valueOf(10));
        BattleVote vote = votedVote("A");
        given(battleVoteRepository.findMyVotedBattles(any(), any(), any()))
                .willReturn(pageOf(battle, vote));

        Page<MyVoteBattleResponse> result = voteService.getMyVotedBattles(1L, null, 0, 20);

        assertThat(result.getContent()).hasSize(1);
        MyVoteBattleResponse item = result.getContent().get(0);
        assertThat(item.getSelectedOption()).isEqualTo("A");
        assertThat(item.getWinningOption()).isEqualTo("A");
        assertThat(item.getIsWin()).isTrue();
        assertThat(item.getRewardAmount()).isEqualTo("10");
        assertThat(item.getSettledAt()).isNotNull();
        assertThat(item.getVotedAt()).isNotNull();
    }

    @Test
    @DisplayName("내 참여 배틀 - 정산 패배 → isWin=false, rewardAmount=null")
    void getMyVotedBattles_settledLose_noReward() {
        Battle battle = settledBattle("A", BigDecimal.valueOf(10));
        BattleVote vote = votedVote("B");
        given(battleVoteRepository.findMyVotedBattles(any(), any(), any()))
                .willReturn(pageOf(battle, vote));

        MyVoteBattleResponse item = voteService.getMyVotedBattles(1L, null, 0, 20)
                .getContent().get(0);

        assertThat(item.getIsWin()).isFalse();
        assertThat(item.getRewardAmount()).isNull();
        assertThat(item.getWinningOption()).isEqualTo("A");
    }

    @Test
    @DisplayName("내 참여 배틀 - 무승부(DRAW) → isWin=false, rewardAmount=null")
    void getMyVotedBattles_draw_noReward() {
        Battle battle = settledBattle("DRAW", BigDecimal.ZERO);
        BattleVote vote = votedVote("A");
        given(battleVoteRepository.findMyVotedBattles(any(), any(), any()))
                .willReturn(pageOf(battle, vote));

        MyVoteBattleResponse item = voteService.getMyVotedBattles(1L, null, 0, 20)
                .getContent().get(0);

        assertThat(item.getWinningOption()).isEqualTo("DRAW");
        assertThat(item.getIsWin()).isFalse();
        assertThat(item.getRewardAmount()).isNull();
    }

    @Test
    @DisplayName("내 참여 배틀 - 미정산(ACTIVE) → winningOption/isWin/rewardAmount/settledAt 모두 null")
    void getMyVotedBattles_unsettled_nullFields() {
        Battle battle = activeBattle(LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(7));
        BattleVote vote = votedVote("A");
        given(battleVoteRepository.findMyVotedBattles(any(), any(), any()))
                .willReturn(pageOf(battle, vote));

        MyVoteBattleResponse item = voteService.getMyVotedBattles(1L, null, 0, 20)
                .getContent().get(0);

        assertThat(item.getWinningOption()).isNull();
        assertThat(item.getIsWin()).isNull();
        assertThat(item.getRewardAmount()).isNull();
        assertThat(item.getSettledAt()).isNull();
    }

    @Test
    @DisplayName("내 참여 배틀 - 참여 내역 없음 → 빈 페이지(content=[])")
    void getMyVotedBattles_empty_returnsEmptyPage() {
        given(battleVoteRepository.findMyVotedBattles(any(), any(), any()))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        Page<MyVoteBattleResponse> result = voteService.getMyVotedBattles(1L, null, 0, 20);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }

    @Test
    @DisplayName("내 참여 배틀 - 잘못된 status 값 → VALIDATION_FAILED")
    void getMyVotedBattles_invalidStatus_throwsValidationFailed() {
        assertThatThrownBy(() -> voteService.getMyVotedBattles(1L, "PENDING", 0, 20))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    // ===================== getCrossResult (관리자 전용) =====================

    @Test
    @DisplayName("교차분석 조회 성공 - CLOSED 상태")
    void getCrossResult_success() {
        given(battleRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(closedBattle()));

        CrossAnalysisResponse response = voteService.getCrossResult(1L);

        assertThat(response.getBattleId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("교차분석 조회 실패 - CLOSED 아님 → BATTLE_RESULT_NOT_AVAILABLE")
    void getCrossResult_fail_notClosed() {
        Battle battle = activeBattle(LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(7));
        given(battleRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(battle));

        assertThatThrownBy(() -> voteService.getCrossResult(1L))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.BATTLE_RESULT_NOT_AVAILABLE));
    }

    // ===================== getRawVotes =====================

    @Test
    @DisplayName("투표 원본 데이터 조회 성공")
    void getRawVotes_success() {
        BattleVote vote = BattleVote.builder().battleId(1L).memberId(1L).selectedOption("A").build();
        given(battleRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(closedBattle()));
        given(battleVoteRepository.findByBattleId(1L)).willReturn(List.of(vote));

        VoteRawResponse response = voteService.getRawVotes(1L);

        assertThat(response.getBattleId()).isEqualTo(1L);
        assertThat(response.getVotes()).hasSize(1);
    }

    // ===================== helpers =====================

    private VoteRequest voteRequest(String option) {
        return VoteRequest.builder().option(option).build();
    }

    private Battle pendingBattle() {
        Battle battle = Battle.builder()
                .title("테스트").optionA("A").optionB("B").createdBy(1L)
                .startAt(LocalDateTime.now().minusDays(1))
                .endAt(LocalDateTime.now().plusDays(7))
                .build();
        ReflectionTestUtils.setField(battle, "id", 1L);
        return battle;
    }

    private Battle activeBattle(LocalDateTime startAt, LocalDateTime endAt) {
        Battle battle = Battle.builder()
                .title("테스트").optionA("A").optionB("B").createdBy(1L)
                .startAt(startAt).endAt(endAt)
                .build();
        ReflectionTestUtils.setField(battle, "id", 1L);
        battle.approve();
        return battle;
    }

    private Battle closedBattle() {
        return closedBattleWithEndAt(LocalDateTime.now().minusDays(1));
    }

    private Battle closedBattleWithEndAt(LocalDateTime endAt) {
        Battle battle = Battle.builder()
                .title("테스트").optionA("A").optionB("B").createdBy(1L)
                .startAt(endAt.minusDays(7)).endAt(endAt)
                .build();
        ReflectionTestUtils.setField(battle, "id", 1L);
        battle.approve();
        battle.close("A");
        return battle;
    }

    private Battle cancelledBattle() {
        Battle battle = pendingBattle();
        battle.reject();
        return battle;
    }

    private Battle settledBattle(String winningOption, BigDecimal rewardAmount) {
        Battle battle = closedBattleWithEndAt(LocalDateTime.now().minusDays(1));
        ReflectionTestUtils.setField(battle, "winningOption", winningOption);
        battle.settle(rewardAmount);
        return battle;
    }

    private BattleVote votedVote(String selectedOption) {
        BattleVote vote = BattleVote.builder()
                .battleId(1L).memberId(1L).selectedOption(selectedOption)
                .build();
        ReflectionTestUtils.setField(vote, "createdAt", LocalDateTime.now().minusDays(2));
        return vote;
    }

    private Page<Object[]> pageOf(Battle battle, BattleVote vote) {
        Pageable pageable = PageRequest.of(0, 20);
        return new PageImpl<>(Collections.singletonList(new Object[]{battle, vote}), pageable, 1);
    }
}