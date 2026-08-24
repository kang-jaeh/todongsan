package com.todongsan.battle_service.comment.service;

import com.todongsan.battle_service.battle.entity.Battle;
import com.todongsan.battle_service.battle.repository.BattleRepository;
import com.todongsan.battle_service.outbox.service.OutboxEventCreator;
import com.todongsan.battle_service.comment.dto.request.CommentCreateRequest;
import com.todongsan.battle_service.comment.dto.response.CommentInternalResponse;
import com.todongsan.battle_service.comment.dto.response.CommentResponse;
import com.todongsan.battle_service.comment.entity.Comment;
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
class CommentServiceImplTest {

    @Mock private BattleRepository battleRepository;
    @Mock private CommentRepository commentRepository;
    @Mock private OutboxEventCreator outboxEventCreator;
    @Mock private TransactionTemplate txTemplate;

    @InjectMocks
    private CommentServiceImpl commentService;

    @BeforeEach
    void setUpTxTemplate() {
        // 실제 트랜잭션 매니저 없이 콜백을 즉시 실행하도록 모킹
        lenient().when(txTemplate.execute(any())).thenAnswer(inv ->
                inv.getArgument(0, TransactionCallback.class).doInTransaction(null));
    }

    // ===================== createComment =====================

    @Test
    @DisplayName("댓글 작성 성공 + 보상 지급")
    void createComment_success() {
        Comment comment = comment(1L);
        given(battleRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(activeBattle()));
        given(commentRepository.save(any(Comment.class))).willReturn(comment);

        CommentResponse response = commentService.createComment(1L, 1L,
                CommentCreateRequest.builder().content("좋아요!").build());

        assertThat(response.getContent()).isEqualTo("테스트 댓글");
        verify(outboxEventCreator).createRewardEvent(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("댓글 작성 성공 - outbox 보상 이벤트 생성 확인")
    void createComment_success_outboxEventCreated() {
        Comment comment = comment(1L);
        given(battleRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(activeBattle()));
        given(commentRepository.save(any(Comment.class))).willReturn(comment);

        CommentResponse response = commentService.createComment(1L, 1L,
                CommentCreateRequest.builder().content("좋아요!").build());

        assertThat(response.getContent()).isEqualTo("테스트 댓글");
        verify(outboxEventCreator).createRewardEvent(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("댓글 작성 실패 - PENDING 상태 Battle → BATTLE_NOT_FOUND")
    void createComment_fail_pendingBattle() {
        given(battleRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(pendingBattle()));

        assertThatThrownBy(() -> commentService.createComment(1L, 1L,
                CommentCreateRequest.builder().content("내용").build()))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.BATTLE_NOT_FOUND));
    }

    @Test
    @DisplayName("댓글 작성 실패 - CLOSED 상태 Battle → BATTLE_CLOSED")
    void createComment_fail_closedBattle() {
        given(battleRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(closedBattle()));

        assertThatThrownBy(() -> commentService.createComment(1L, 1L,
                CommentCreateRequest.builder().content("내용").build()))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.BATTLE_CLOSED));
    }

    @Test
    @DisplayName("댓글 작성 실패 - 500자 초과 → BATTLE_COMMENT_TOO_LONG")
    void createComment_fail_tooLong() {
        given(battleRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(activeBattle()));

        assertThatThrownBy(() -> commentService.createComment(1L, 1L,
                CommentCreateRequest.builder().content("가".repeat(501)).build()))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.BATTLE_COMMENT_TOO_LONG));
    }

    @Test
    @DisplayName("댓글 작성 실패 - 정확히 500자는 성공")
    void createComment_success_exactly500chars() {
        Comment comment = comment(1L);
        given(battleRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(activeBattle()));
        given(commentRepository.save(any(Comment.class))).willReturn(comment);

        CommentResponse response = commentService.createComment(1L, 1L,
                CommentCreateRequest.builder().content("가".repeat(500)).build());

        assertThat(response).isNotNull();
    }

    // ===================== getComments =====================

    @Test
    @DisplayName("댓글 목록 조회 성공")
    void getComments_success() {
        given(battleRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(activeBattle()));
        given(commentRepository.findByBattleIdAndDeletedAtIsNull(eq(1L), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(comment(1L))));

        Page<CommentResponse> result = commentService.getComments(1L, 0, 10);

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    @DisplayName("댓글 목록 조회 실패 - Battle 없음")
    void getComments_fail_battleNotFound() {
        given(battleRepository.findByIdAndDeletedAtIsNull(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> commentService.getComments(999L, 0, 10))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.BATTLE_NOT_FOUND));
    }

    // ===================== deleteComment =====================

    @Test
    @DisplayName("댓글 삭제 성공 - soft delete 확인")
    void deleteComment_success() {
        Comment comment = comment(1L);
        given(commentRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(comment));

        commentService.deleteComment(1L, 1L, 1L);

        assertThat(comment.isDeleted()).isTrue();
    }

    @Test
    @DisplayName("댓글 삭제 실패 - 존재하지 않는 댓글")
    void deleteComment_fail_notFound() {
        given(commentRepository.findByIdAndDeletedAtIsNull(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> commentService.deleteComment(1L, 999L, 1L))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.BATTLE_COMMENT_NOT_FOUND));
    }

    @Test
    @DisplayName("댓글 삭제 실패 - 본인 댓글 아님 → BATTLE_COMMENT_FORBIDDEN")
    void deleteComment_fail_forbidden() {
        Comment comment = comment(1L);
        given(commentRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(comment));

        assertThatThrownBy(() -> commentService.deleteComment(1L, 1L, 2L))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.BATTLE_COMMENT_FORBIDDEN));
    }

    // ===================== getCommentInternal =====================

    @Test
    @DisplayName("댓글 내부 조회 성공")
    void getCommentInternal_success() {
        given(commentRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(comment(1L)));

        CommentInternalResponse response = commentService.getCommentInternal(1L);

        assertThat(response.getCommentId()).isEqualTo(1L);
        assertThat(response.getMemberId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("댓글 내부 조회 실패 - soft delete된 댓글 → BATTLE_COMMENT_NOT_FOUND")
    void getCommentInternal_fail_deleted() {
        given(commentRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> commentService.getCommentInternal(1L))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.BATTLE_COMMENT_NOT_FOUND));
    }

    // ===================== helpers =====================

    private Battle pendingBattle() {
        Battle battle = Battle.builder()
                .title("테스트").optionA("A").optionB("B").createdBy(1L)
                .startAt(LocalDateTime.now().minusDays(1))
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

    private Battle closedBattle() {
        Battle battle = pendingBattle();
        battle.approve();
        battle.close("A");
        return battle;
    }

    private Comment comment(Long memberId) {
        Comment comment = Comment.builder()
                .battleId(1L).memberId(memberId).content("테스트 댓글")
                .build();
        ReflectionTestUtils.setField(comment, "id", 1L);
        return comment;
    }
}
