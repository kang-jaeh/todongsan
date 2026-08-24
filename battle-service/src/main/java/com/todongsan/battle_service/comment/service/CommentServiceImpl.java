package com.todongsan.battle_service.comment.service;

import com.todongsan.battle_service.battle.entity.Battle;
import com.todongsan.battle_service.battle.entity.BattleStatus;
import com.todongsan.battle_service.battle.repository.BattleRepository;
import com.todongsan.battle_service.comment.dto.request.CommentCreateRequest;
import com.todongsan.battle_service.comment.dto.response.CommentInternalResponse;
import com.todongsan.battle_service.comment.dto.response.CommentResponse;
import com.todongsan.battle_service.comment.entity.Comment;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class CommentServiceImpl implements CommentService {

    private static final int MAX_CONTENT_LENGTH = 500;
    private static final BigDecimal COMMENT_REWARD = BigDecimal.valueOf(2);

    private final BattleRepository battleRepository;
    private final CommentRepository commentRepository;
    private final OutboxEventCreator outboxEventCreator;
    private final TransactionTemplate txTemplate;

    @Override
    public CommentResponse createComment(Long battleId, Long memberId, CommentCreateRequest request) {
        // 댓글 저장 + 보상 이벤트를 한 트랜잭션에서 처리
        Comment comment = txTemplate.execute(status -> {
            findBattleForActivity(battleId);

            if (request.getContent().length() > MAX_CONTENT_LENGTH) {
                throw new CustomException(ErrorCode.BATTLE_COMMENT_TOO_LONG);
            }

            Comment saved = commentRepository.save(Comment.builder()
                    .battleId(battleId)
                    .memberId(memberId)
                    .content(request.getContent())
                    .build());

            // 보상 이벤트 outbox INSERT (같은 트랜잭션)
            outboxEventCreator.createRewardEvent(
                    battleId, memberId, "EARN_COMMENT", COMMENT_REWARD,
                    "Battle 댓글 작성 보상",
                    "battle:comment:battle:" + battleId + ":member:" + memberId);

            return saved;
        });

        return CommentResponse.from(comment);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CommentResponse> getComments(Long battleId, int page, int size) {
        battleRepository.findByIdAndDeletedAtIsNull(battleId)
                .orElseThrow(() -> new CustomException(ErrorCode.BATTLE_NOT_FOUND));

        PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt").ascending());
        return commentRepository.findByBattleIdAndDeletedAtIsNull(battleId, pageable)
                .map(CommentResponse::from);
    }

    @Override
    @Transactional
    public void deleteComment(Long battleId, Long commentId, Long memberId) {
        Comment comment = commentRepository.findByIdAndDeletedAtIsNull(commentId)
                .orElseThrow(() -> new CustomException(ErrorCode.BATTLE_COMMENT_NOT_FOUND));

        if (!comment.getMemberId().equals(memberId)) {
            throw new CustomException(ErrorCode.BATTLE_COMMENT_FORBIDDEN);
        }

        comment.softDelete();
    }

    @Override
    @Transactional(readOnly = true)
    public CommentInternalResponse getCommentInternal(Long commentId) {
        Comment comment = commentRepository.findByIdAndDeletedAtIsNull(commentId)
                .orElseThrow(() -> new CustomException(ErrorCode.BATTLE_COMMENT_NOT_FOUND));
        return CommentInternalResponse.from(comment);
    }

    private Battle findBattleForActivity(Long battleId) {
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
