package com.todongsan.battle_service.battle.service;

import com.todongsan.battle_service.battle.dto.request.BattleCreateRequest;
import com.todongsan.battle_service.battle.dto.response.BattleCreateResponse;
import com.todongsan.battle_service.battle.dto.response.BattleDetailResponse;
import com.todongsan.battle_service.battle.dto.response.BattleStatusResponse;
import com.todongsan.battle_service.battle.dto.response.MyCreatedBattleResponse;
import com.todongsan.battle_service.battle.entity.Battle;
import com.todongsan.battle_service.battle.entity.BattleStatus;
import com.todongsan.battle_service.battle.repository.BattleRepository;
import com.todongsan.battle_service.outbox.service.OutboxEventCreator;
import com.todongsan.battle_service.comment.repository.CommentRepository;
import com.todongsan.battle_service.global.exception.CustomException;
import com.todongsan.battle_service.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BattleServiceImplTest {

    @Mock private BattleRepository battleRepository;
    @Mock private CommentRepository commentRepository;
    @Mock private OutboxEventCreator outboxEventCreator;
    @Mock private TransactionTemplate txTemplate;

    @InjectMocks
    private BattleServiceImpl battleService;

    @BeforeEach
    void setUpTxTemplate() {
        // 실제 트랜잭션 매니저 없이 콜백을 즉시 실행하도록 모킹
        lenient().when(txTemplate.execute(any())).thenAnswer(inv ->
                inv.getArgument(0, TransactionCallback.class).doInTransaction(null));
    }

    // ===================== createBattle =====================

    @Test
    @DisplayName("Battle 생성 성공")
    void createBattle_success() {
        Long memberId = 1L;
        BattleCreateRequest request = BattleCreateRequest.builder()
                .title("성수 vs 연남")
                .optionA("성수")
                .optionB("연남")
                .startAt(LocalDateTime.now().plusDays(1))
                .endAt(LocalDateTime.now().plusDays(7))
                .build();

        Battle saved = Battle.builder()
                .title(request.getTitle())
                .optionA(request.getOptionA())
                .optionB(request.getOptionB())
                .createdBy(memberId)
                .startAt(request.getStartAt())
                .endAt(request.getEndAt())
                .build();
        ReflectionTestUtils.setField(saved, "id", 1L);

        given(battleRepository.save(any(Battle.class))).willReturn(saved);

        BattleCreateResponse response = battleService.createBattle(memberId, request);

        assertThat(response.getBattleId()).isEqualTo(1L);
        assertThat(response.getStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("Battle 생성 실패 - endAt이 startAt 이전")
    void createBattle_fail_endAtBeforeStartAt() {
        BattleCreateRequest request = BattleCreateRequest.builder()
                .title("테스트").optionA("A").optionB("B")
                .startAt(LocalDateTime.now().plusDays(7))
                .endAt(LocalDateTime.now().plusDays(1))
                .build();

        assertThatThrownBy(() -> battleService.createBattle(1L, request))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.BATTLE_INVALID_PERIOD));
    }

    @Test
    @DisplayName("Battle 생성 실패 - endAt이 현재 시각 이전")
    void createBattle_fail_endAtInPast() {
        BattleCreateRequest request = BattleCreateRequest.builder()
                .title("테스트").optionA("A").optionB("B")
                .startAt(LocalDateTime.now().minusDays(10))
                .endAt(LocalDateTime.now().minusDays(1))
                .build();

        assertThatThrownBy(() -> battleService.createBattle(1L, request))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.BATTLE_INVALID_PERIOD));
    }

    // ===================== getBattles =====================

    @Test
    @DisplayName("Battle 목록 조회 성공 - ACTIVE, 기본 정렬(최신순)")
    void getBattles_activeStatus() {
        Page<Battle> page = new PageImpl<>(List.of(activeBattle()));
        given(battleRepository.findByStatusAndDeletedAtIsNull(eq(BattleStatus.ACTIVE), any(Pageable.class)))
                .willReturn(page);

        var result = battleService.getBattles("ACTIVE", null, 0, 20);

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    @DisplayName("Battle 목록 조회 성공 - sort=popular, vote_count 내림차순")
    void getBattles_popularSort() {
        Page<Battle> page = new PageImpl<>(List.of(activeBattle()));
        given(battleRepository.findByStatusAndDeletedAtIsNull(eq(BattleStatus.ACTIVE), any(Pageable.class)))
                .willReturn(page);

        var result = battleService.getBattles("ACTIVE", "popular", 0, 20);

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    @DisplayName("Battle 목록 조회 실패 - PENDING 요청 시 VALIDATION_FAILED")
    void getBattles_fail_pendingStatus() {
        assertThatThrownBy(() -> battleService.getBattles("PENDING", null, 0, 20))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    // ===================== getMyCreatedBattles =====================

    @Test
    @DisplayName("내가 만든 배틀 목록 - status 미지정 시 전체 상태 조회")
    void getMyCreatedBattles_noStatus_allStatuses() {
        Page<Battle> page = new PageImpl<>(List.of(pendingBattle()));
        given(battleRepository.findByCreatedByAndStatusInAndDeletedAtIsNull(eq(1L), any(), any(Pageable.class)))
                .willReturn(page);

        Page<MyCreatedBattleResponse> result = battleService.getMyCreatedBattles(1L, null, 0, 20);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("내가 만든 배틀 목록 - PENDING/CANCELLED 등 모든 상태 허용")
    void getMyCreatedBattles_allStatusesAllowed() {
        given(battleRepository.findByCreatedByAndStatusInAndDeletedAtIsNull(eq(1L), any(), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(pendingBattle())));

        Page<MyCreatedBattleResponse> result =
                battleService.getMyCreatedBattles(1L, "PENDING,CANCELLED", 0, 20);

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    @DisplayName("내가 만든 배틀 목록 - 미정산이면 winningOption=null")
    void getMyCreatedBattles_unsettled_winningOptionNull() {
        given(battleRepository.findByCreatedByAndStatusInAndDeletedAtIsNull(eq(1L), any(), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(activeBattle())));

        MyCreatedBattleResponse item = battleService.getMyCreatedBattles(1L, null, 0, 20)
                .getContent().get(0);

        assertThat(item.getWinningOption()).isNull();
        assertThat(item.getSettledAt()).isNull();
    }

    @Test
    @DisplayName("내가 만든 배틀 목록 - 정산 완료면 winningOption/settledAt 노출")
    void getMyCreatedBattles_settled_winningOptionShown() {
        given(battleRepository.findByCreatedByAndStatusInAndDeletedAtIsNull(eq(1L), any(), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(settledBattle())));

        MyCreatedBattleResponse item = battleService.getMyCreatedBattles(1L, null, 0, 20)
                .getContent().get(0);

        assertThat(item.getWinningOption()).isEqualTo("A");
        assertThat(item.getSettledAt()).isNotNull();
    }

    @Test
    @DisplayName("내가 만든 배틀 목록 - 생성 내역 없음 → 빈 페이지")
    void getMyCreatedBattles_empty_returnsEmptyPage() {
        given(battleRepository.findByCreatedByAndStatusInAndDeletedAtIsNull(eq(1L), any(), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of()));

        Page<MyCreatedBattleResponse> result = battleService.getMyCreatedBattles(1L, null, 0, 20);

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    @DisplayName("내가 만든 배틀 목록 - 잘못된 status 값 → VALIDATION_FAILED")
    void getMyCreatedBattles_invalidStatus_throwsValidationFailed() {
        assertThatThrownBy(() -> battleService.getMyCreatedBattles(1L, "FOO", 0, 20))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    // ===================== getBattle =====================

    @Test
    @DisplayName("Battle 상세 조회 성공")
    void getBattle_success() {
        given(battleRepository.findByIdAndStatusInAndDeletedAtIsNull(eq(1L), any()))
                .willReturn(Optional.of(activeBattle()));

        BattleDetailResponse response = battleService.getBattle(1L);

        assertThat(response.getBattleId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Battle 상세 조회 실패 - 존재하지 않음 (PENDING/CANCELLED 포함)")
    void getBattle_fail_notFound() {
        given(battleRepository.findByIdAndStatusInAndDeletedAtIsNull(any(), any()))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> battleService.getBattle(999L))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.BATTLE_NOT_FOUND));
    }

    // ===================== approveBattle =====================

    @Test
    @DisplayName("Battle 승인 성공 - PENDING → ACTIVE + 보상 지급")
    void approveBattle_success() {
        Battle battle = pendingBattle();
        given(battleRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(battle));

        BattleStatusResponse response = battleService.approveBattle(1L);

        assertThat(response.getStatus()).isEqualTo("ACTIVE");
        verify(outboxEventCreator).createRewardEvent(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Battle 승인 성공 - outbox 보상 이벤트 생성 확인")
    void approveBattle_success_outboxEventCreated() {
        Battle battle = pendingBattle();
        given(battleRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(battle));

        BattleStatusResponse response = battleService.approveBattle(1L);

        assertThat(response.getStatus()).isEqualTo("ACTIVE");
        verify(outboxEventCreator).createRewardEvent(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Battle 승인 실패 - PENDING 아님")
    void approveBattle_fail_notPending() {
        Battle battle = activeBattle();
        given(battleRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(battle));

        assertThatThrownBy(() -> battleService.approveBattle(1L))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.BATTLE_INVALID_STATUS));
    }

    // ===================== rejectBattle =====================

    @Test
    @DisplayName("Battle 거절 성공 - PENDING → CANCELLED")
    void rejectBattle_success() {
        given(battleRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(pendingBattle()));

        BattleStatusResponse response = battleService.rejectBattle(1L);

        assertThat(response.getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("Battle 거절 실패 - PENDING 아님")
    void rejectBattle_fail_notPending() {
        given(battleRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(activeBattle()));

        assertThatThrownBy(() -> battleService.rejectBattle(1L))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.BATTLE_INVALID_STATUS));
    }

    // ===================== cancelBattle =====================

    @Test
    @DisplayName("Battle 강제 취소 성공 - ACTIVE → CANCELLED")
    void cancelBattle_success() {
        given(battleRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(activeBattle()));

        BattleStatusResponse response = battleService.cancelBattle(1L);

        assertThat(response.getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("Battle 강제 취소 실패 - ACTIVE 아님 (PENDING)")
    void cancelBattle_fail_notActive() {
        given(battleRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(pendingBattle()));

        assertThatThrownBy(() -> battleService.cancelBattle(1L))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.BATTLE_INVALID_STATUS));
    }

    // ===================== cancelBattleByUser =====================

    @Test
    @DisplayName("사용자 취소 성공 - PENDING → CANCELLED")
    void cancelBattleByUser_success() {
        given(battleRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(pendingBattle()));

        BattleStatusResponse response = battleService.cancelBattleByUser(1L, 1L);

        assertThat(response.getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("사용자 취소 실패 - PENDING 아님 → BATTLE_INVALID_STATUS")
    void cancelBattleByUser_fail_notPending() {
        given(battleRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(activeBattle()));

        assertThatThrownBy(() -> battleService.cancelBattleByUser(1L, 1L))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.BATTLE_INVALID_STATUS));
    }

    @Test
    @DisplayName("사용자 취소 실패 - 본인 배틀 아님 → FORBIDDEN")
    void cancelBattleByUser_fail_notOwner() {
        given(battleRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(pendingBattle()));

        assertThatThrownBy(() -> battleService.cancelBattleByUser(1L, 999L))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
    }

    // ===================== helpers =====================

    private Battle pendingBattle() {
        Battle battle = Battle.builder()
                .title("성수 vs 연남").optionA("성수").optionB("연남")
                .createdBy(1L)
                .startAt(LocalDateTime.now().plusDays(1))
                .endAt(LocalDateTime.now().plusDays(7))
                .build();
        ReflectionTestUtils.setField(battle, "id", 1L);
        return battle;
    }

    private Battle activeBattle() {
        Battle battle = pendingBattle();
        battle.approve();
        return battle;
    }

    private Battle settledBattle() {
        Battle battle = activeBattle();
        battle.close("A");
        battle.settle(BigDecimal.valueOf(10));
        return battle;
    }
}