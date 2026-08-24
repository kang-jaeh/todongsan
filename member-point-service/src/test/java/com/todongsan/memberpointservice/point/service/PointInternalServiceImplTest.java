package com.todongsan.memberpointservice.point.service;

import com.todongsan.memberpointservice.global.exception.CustomException;
import com.todongsan.memberpointservice.global.exception.ErrorCode;
import com.todongsan.memberpointservice.global.util.RequestHashUtil;
import com.todongsan.memberpointservice.member.entity.Member;
import com.todongsan.memberpointservice.member.repository.MemberRepository;
import com.todongsan.memberpointservice.point.dto.request.EarnRequest;
import com.todongsan.memberpointservice.point.dto.request.SpendRequest;
import com.todongsan.memberpointservice.point.dto.response.EarnResponse;
import com.todongsan.memberpointservice.point.dto.response.SpendResponse;
import com.todongsan.memberpointservice.point.entity.PointHistory;
import com.todongsan.memberpointservice.point.entity.PointHistoryType;
import com.todongsan.memberpointservice.point.entity.PointReferenceType;
import com.todongsan.memberpointservice.point.entity.PointTransactionStatus;
import com.todongsan.memberpointservice.point.repository.PointHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PointInternalServiceImplTest {

    @Mock MemberRepository memberRepository;
    @Mock PointHistoryRepository pointHistoryRepository;
    @Mock PlatformTransactionManager transactionManager;

    PointInternalServiceImpl service;

    private static final String KEY = "test-idempotency-key";
    private static final Long MEMBER_ID = 1L;
    private static final String TYPE = "EARN_VOTE";
    private static final BigDecimal AMOUNT = new BigDecimal("10.00");
    private static final String REF_TYPE = "BATTLE";
    private static final Long REF_ID = 42L;

    @BeforeEach
    void setUp() {
        // TransactionTemplate이 실제 트랜잭션 없이 callback을 실행하도록 설정
        lenient().when(transactionManager.getTransaction(any()))
                .thenReturn(new SimpleTransactionStatus());
        lenient().doNothing().when(transactionManager).commit(any());
        lenient().doNothing().when(transactionManager).rollback(any());

        // save()가 입력받은 엔티티를 그대로 반환하도록 설정
        lenient().when(pointHistoryRepository.save(any(PointHistory.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(pointHistoryRepository.saveAndFlush(any(PointHistory.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service = new PointInternalServiceImpl(memberRepository, pointHistoryRepository, transactionManager);
    }

    private Member createMemberMock(BigDecimal balance) {
        Member member = mock(Member.class);
        lenient().when(member.getId()).thenReturn(MEMBER_ID);
        lenient().when(member.getPointBalance()).thenReturn(balance);
        return member;
    }

    private Member createMember() {
        return createMemberMock(new BigDecimal("100.00"));
    }

    private EarnRequest createEarnRequest() {
        EarnRequest request = mock(EarnRequest.class);
        lenient().when(request.getMemberId()).thenReturn(MEMBER_ID);
        lenient().when(request.getType()).thenReturn(TYPE);
        lenient().when(request.getAmount()).thenReturn(AMOUNT);
        lenient().when(request.getReferenceType()).thenReturn(REF_TYPE);
        lenient().when(request.getReferenceId()).thenReturn(REF_ID);
        lenient().when(request.getReason()).thenReturn("테스트 이유");
        return request;
    }

    private PointHistory createHistory(PointTransactionStatus status, String requestHash) {
        return PointHistory.builder()
                .memberId(MEMBER_ID)
                .type(PointHistoryType.EARN_VOTE)
                .amount(AMOUNT)
                .balanceSnapshot(new BigDecimal("110.00"))
                .reason("테스트 이유")
                .referenceType(PointReferenceType.BATTLE)
                .referenceId(REF_ID)
                .idempotencyKey(KEY)
                .requestHash(requestHash)
                .status(status)
                .build();
    }

    private PointHistory createHistory(String requestHash) {
        return createHistory(PointTransactionStatus.SUCCEEDED, requestHash);
    }

    // ─── earn ─────────────────────────────────────────────────

    @Test
    void earn_정상_적립() {
        Member member = createMember();
        Member updatedMember = createMemberMock(new BigDecimal("110.00"));
        EarnRequest request = createEarnRequest();

        when(pointHistoryRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(memberRepository.findByIdAndDeletedAtIsNull(MEMBER_ID))
                .thenReturn(Optional.of(member))
                .thenReturn(Optional.of(updatedMember));
        when(memberRepository.earnPoint(eq(MEMBER_ID), any())).thenReturn(1);

        PointResult<EarnResponse> result = service.earn(KEY, request);

        assertThat(result.alreadyProcessed()).isFalse();
        assertThat(result.data().getMemberId()).isEqualTo(MEMBER_ID);
        assertThat(result.data().getType()).isEqualTo(TYPE);
        assertThat(result.data().getAmount()).isEqualTo("10.00");
        assertThat(result.data().getBalanceSnapshot()).isEqualTo("110.00");
    }

    @Test
    void earn_멱등성_동일요청_재시도() {
        EarnRequest request = createEarnRequest();
        String hash = RequestHashUtil.compute(MEMBER_ID, TYPE, AMOUNT, REF_TYPE, REF_ID);

        when(pointHistoryRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(createHistory(hash)));

        PointResult<EarnResponse> result = service.earn(KEY, request);

        assertThat(result.alreadyProcessed()).isTrue();
        assertThat(result.data().getMemberId()).isEqualTo(MEMBER_ID);
    }

    @Test
    void earn_멱등성_키충돌() {
        EarnRequest request = createEarnRequest();

        when(pointHistoryRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(createHistory("different-hash")));

        assertThatThrownBy(() -> service.earn(KEY, request))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
    }

    @Test
    void earn_키_없으면_예외() {
        assertThatThrownBy(() -> service.earn(null, createEarnRequest()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
    }

    @Test
    void earn_amount_0이하_예외() {
        EarnRequest request = mock(EarnRequest.class);
        when(request.getAmount()).thenReturn(BigDecimal.ZERO);

        assertThatThrownBy(() -> service.earn(KEY, request))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.POINT_INVALID_AMOUNT);
    }

    @Test
    void earn_잘못된_referenceType_예외() {
        EarnRequest request = mock(EarnRequest.class);
        when(request.getAmount()).thenReturn(AMOUNT);
        when(request.getReferenceType()).thenReturn("INVALID");

        assertThatThrownBy(() -> service.earn(KEY, request))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.POINT_INVALID_REFERENCE_TYPE);
    }

    @Test
    void earn_referenceType_null이면_허용() {
        Member member = createMember();
        Member updatedMember = createMemberMock(new BigDecimal("110.00"));
        EarnRequest request = mock(EarnRequest.class);
        when(request.getMemberId()).thenReturn(MEMBER_ID);
        when(request.getType()).thenReturn(TYPE);
        when(request.getAmount()).thenReturn(AMOUNT);
        when(request.getReferenceType()).thenReturn(null);
        when(request.getReferenceId()).thenReturn(null);
        when(pointHistoryRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(memberRepository.findByIdAndDeletedAtIsNull(MEMBER_ID))
                .thenReturn(Optional.of(member))
                .thenReturn(Optional.of(updatedMember));
        when(memberRepository.earnPoint(eq(MEMBER_ID), any())).thenReturn(1);

        PointResult<EarnResponse> result = service.earn(KEY, request);

        assertThat(result.alreadyProcessed()).isFalse();
    }

    @Test
    void earn_type_null이면_VALIDATION_FAILED() {
        EarnRequest request = mock(EarnRequest.class);
        when(request.getAmount()).thenReturn(AMOUNT);
        when(request.getReferenceType()).thenReturn(REF_TYPE);
        when(request.getType()).thenReturn(null);

        assertThatThrownBy(() -> service.earn(KEY, request))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void earn_회원_없으면_예외() {
        EarnRequest request = mock(EarnRequest.class);
        when(request.getAmount()).thenReturn(AMOUNT);
        when(request.getReferenceType()).thenReturn(REF_TYPE);
        when(request.getType()).thenReturn(TYPE);
        when(request.getMemberId()).thenReturn(999L);
        when(pointHistoryRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(memberRepository.findByIdAndDeletedAtIsNull(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.earn(KEY, request))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEMBER_NOT_FOUND);
    }

    @Test
    void earn_PENDING상태_이력_발견시_409() {
        EarnRequest request = createEarnRequest();
        String hash = RequestHashUtil.compute(MEMBER_ID, TYPE, AMOUNT, REF_TYPE, REF_ID);

        when(pointHistoryRepository.findByIdempotencyKey(KEY))
                .thenReturn(Optional.of(createHistory(PointTransactionStatus.PENDING, hash)));

        assertThatThrownBy(() -> service.earn(KEY, request))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
    }

    // ─── spend ────────────────────────────────────────────────

    private SpendRequest createSpendRequest() {
        SpendRequest request = mock(SpendRequest.class);
        lenient().when(request.getMemberId()).thenReturn(MEMBER_ID);
        lenient().when(request.getType()).thenReturn("SPEND_MARKET");
        lenient().when(request.getAmount()).thenReturn(AMOUNT);
        lenient().when(request.getReferenceType()).thenReturn(REF_TYPE);
        lenient().when(request.getReferenceId()).thenReturn(REF_ID);
        lenient().when(request.getReason()).thenReturn("테스트 차감");
        return request;
    }

    private PointHistory createSpendHistory(PointTransactionStatus status, String requestHash) {
        return PointHistory.builder()
                .memberId(MEMBER_ID)
                .type(PointHistoryType.SPEND_MARKET)
                .amount(AMOUNT)
                .balanceSnapshot(new BigDecimal("90.00"))
                .reason("테스트 차감")
                .referenceType(PointReferenceType.BATTLE)
                .referenceId(REF_ID)
                .idempotencyKey(KEY)
                .requestHash(requestHash)
                .status(status)
                .failReason(status == PointTransactionStatus.FAILED ? "POINT_INSUFFICIENT" : null)
                .build();
    }

    @Test
    void spend_정상_차감() {
        Member member = createMember();
        Member updatedMember = createMemberMock(new BigDecimal("90.00"));
        SpendRequest request = createSpendRequest();

        when(pointHistoryRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(memberRepository.findByIdAndDeletedAtIsNull(MEMBER_ID))
                .thenReturn(Optional.of(member))
                .thenReturn(Optional.of(updatedMember));
        when(memberRepository.spendPoint(eq(MEMBER_ID), any())).thenReturn(1);

        PointResult<SpendResponse> result = service.spend(KEY, request);

        assertThat(result.alreadyProcessed()).isFalse();
        assertThat(result.data().getMemberId()).isEqualTo(MEMBER_ID);
        assertThat(result.data().getType()).isEqualTo("SPEND_MARKET");
        assertThat(result.data().getAmount()).isEqualTo("10.00");
        assertThat(result.data().getBalanceSnapshot()).isEqualTo("90.00");
    }

    @Test
    void spend_잔액_부족() {
        Member member = createMemberMock(new BigDecimal("5.00"));
        SpendRequest request = createSpendRequest();

        when(pointHistoryRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(memberRepository.findByIdAndDeletedAtIsNull(MEMBER_ID)).thenReturn(Optional.of(member));
        when(memberRepository.spendPoint(eq(MEMBER_ID), any())).thenReturn(0);

        assertThatThrownBy(() -> service.spend(KEY, request))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.POINT_INSUFFICIENT);
    }

    @Test
    void spend_멱등성_동일요청_SUCCEEDED_재시도() {
        SpendRequest request = createSpendRequest();
        String hash = RequestHashUtil.compute(MEMBER_ID, "SPEND_MARKET", AMOUNT, REF_TYPE, REF_ID);

        when(pointHistoryRepository.findByIdempotencyKey(KEY))
                .thenReturn(Optional.of(createSpendHistory(PointTransactionStatus.SUCCEEDED, hash)));

        PointResult<SpendResponse> result = service.spend(KEY, request);

        assertThat(result.alreadyProcessed()).isTrue();
    }

    @Test
    void spend_멱등성_동일요청_FAILED_재시도_같은_실패_재현() {
        SpendRequest request = createSpendRequest();
        String hash = RequestHashUtil.compute(MEMBER_ID, "SPEND_MARKET", AMOUNT, REF_TYPE, REF_ID);

        when(pointHistoryRepository.findByIdempotencyKey(KEY))
                .thenReturn(Optional.of(createSpendHistory(PointTransactionStatus.FAILED, hash)));

        assertThatThrownBy(() -> service.spend(KEY, request))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.POINT_INSUFFICIENT);
    }

    @Test
    void spend_멱등성_키충돌() {
        SpendRequest request = createSpendRequest();

        when(pointHistoryRepository.findByIdempotencyKey(KEY))
                .thenReturn(Optional.of(createSpendHistory(PointTransactionStatus.SUCCEEDED, "different-hash")));

        assertThatThrownBy(() -> service.spend(KEY, request))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
    }

    @Test
    void spend_키_없으면_예외() {
        assertThatThrownBy(() -> service.spend(null, createSpendRequest()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
    }

    @Test
    void spend_amount_0이하_예외() {
        SpendRequest request = mock(SpendRequest.class);
        when(request.getAmount()).thenReturn(BigDecimal.ZERO);

        assertThatThrownBy(() -> service.spend(KEY, request))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.POINT_INVALID_AMOUNT);
    }

    @Test
    void spend_referenceType_null이면_허용() {
        Member member = createMember();
        Member updatedMember = createMemberMock(new BigDecimal("90.00"));
        SpendRequest request = mock(SpendRequest.class);
        when(request.getMemberId()).thenReturn(MEMBER_ID);
        when(request.getType()).thenReturn("SPEND_MARKET");
        when(request.getAmount()).thenReturn(AMOUNT);
        when(request.getReferenceType()).thenReturn(null);
        when(request.getReferenceId()).thenReturn(null);
        when(pointHistoryRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(memberRepository.findByIdAndDeletedAtIsNull(MEMBER_ID))
                .thenReturn(Optional.of(member))
                .thenReturn(Optional.of(updatedMember));
        when(memberRepository.spendPoint(eq(MEMBER_ID), any())).thenReturn(1);

        PointResult<SpendResponse> result = service.spend(KEY, request);

        assertThat(result.alreadyProcessed()).isFalse();
    }

    @Test
    void spend_type_null이면_VALIDATION_FAILED() {
        SpendRequest request = mock(SpendRequest.class);
        when(request.getAmount()).thenReturn(AMOUNT);
        when(request.getReferenceType()).thenReturn(REF_TYPE);
        when(request.getType()).thenReturn(null);

        assertThatThrownBy(() -> service.spend(KEY, request))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void spend_회원_없으면_예외() {
        SpendRequest request = mock(SpendRequest.class);
        when(request.getAmount()).thenReturn(AMOUNT);
        when(request.getReferenceType()).thenReturn(REF_TYPE);
        when(request.getType()).thenReturn("SPEND_MARKET");
        when(request.getMemberId()).thenReturn(999L);
        when(pointHistoryRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(memberRepository.findByIdAndDeletedAtIsNull(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.spend(KEY, request))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEMBER_NOT_FOUND);
    }

    @Test
    void spend_PENDING상태_이력_발견시_409() {
        SpendRequest request = createSpendRequest();
        String hash = RequestHashUtil.compute(MEMBER_ID, "SPEND_MARKET", AMOUNT, REF_TYPE, REF_ID);

        PointHistory pendingHistory = PointHistory.builder()
                .memberId(MEMBER_ID).type(PointHistoryType.SPEND_MARKET)
                .amount(AMOUNT).balanceSnapshot(BigDecimal.ZERO)
                .idempotencyKey(KEY).requestHash(hash)
                .status(PointTransactionStatus.PENDING).build();

        when(pointHistoryRepository.findByIdempotencyKey(KEY))
                .thenReturn(Optional.of(pendingHistory));

        assertThatThrownBy(() -> service.spend(KEY, request))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
    }
}
