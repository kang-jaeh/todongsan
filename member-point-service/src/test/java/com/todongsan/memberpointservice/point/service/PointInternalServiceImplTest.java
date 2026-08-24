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
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

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
    @Mock EntityManager entityManager;
    @Mock IdempotencySupport idempotencySupport;

    @InjectMocks PointInternalServiceImpl pointInternalServiceImpl;

    private static final String KEY = "test-idempotency-key";
    private static final Long MEMBER_ID = 1L;
    private static final String TYPE = "EARN_VOTE";
    private static final BigDecimal AMOUNT = new BigDecimal("10.00");
    private static final String REF_TYPE = "BATTLE";
    private static final Long REF_ID = 42L;

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
                .thenReturn(Optional.of(member))        // 존재 확인용
                .thenReturn(Optional.of(updatedMember)); // balance snapshot용
        when(memberRepository.earnPoint(eq(MEMBER_ID), any())).thenReturn(1);

        PointResult<EarnResponse> result = pointInternalServiceImpl.earn(KEY, request);

        assertThat(result.alreadyProcessed()).isFalse();
        assertThat(result.data().getMemberId()).isEqualTo(MEMBER_ID);
        assertThat(result.data().getType()).isEqualTo(TYPE);
        assertThat(result.data().getAmount()).isEqualTo("10.00");
        assertThat(result.data().getBalanceSnapshot()).isEqualTo("110.00");

        verify(pointHistoryRepository).saveAndFlush(any(PointHistory.class));
    }

    @Test
    void earn_멱등성_동일요청_재시도() {
        EarnRequest request = createEarnRequest();
        String hash = RequestHashUtil.compute(MEMBER_ID, TYPE, AMOUNT, REF_TYPE, REF_ID);

        when(pointHistoryRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(createHistory(hash)));

        PointResult<EarnResponse> result = pointInternalServiceImpl.earn(KEY, request);

        assertThat(result.alreadyProcessed()).isTrue();
        assertThat(result.data().getMemberId()).isEqualTo(MEMBER_ID);
        verify(pointHistoryRepository, never()).saveAndFlush(any());
    }

    @Test
    void earn_멱등성_키충돌() {
        EarnRequest request = createEarnRequest();

        when(pointHistoryRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(createHistory("different-hash")));

        assertThatThrownBy(() -> pointInternalServiceImpl.earn(KEY, request))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
    }

    @Test
    void earn_UNIQUE_위반_catch후_재조회_SUCCEEDED() {
        EarnRequest request = createEarnRequest();
        String hash = RequestHashUtil.compute(MEMBER_ID, TYPE, AMOUNT, REF_TYPE, REF_ID);
        Member member = createMember();

        // 낙관적 검사: 비어있음 (race 발생)
        when(pointHistoryRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(memberRepository.findByIdAndDeletedAtIsNull(MEMBER_ID)).thenReturn(Optional.of(member));
        // PENDING INSERT: UNIQUE 위반
        when(pointHistoryRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("Duplicate"));
        // 새 트랜잭션에서 재조회: 선행 요청의 SUCCEEDED 이력
        when(idempotencySupport.findByKeyInNewTransaction(KEY))
                .thenReturn(Optional.of(createHistory(hash)));

        PointResult<EarnResponse> result = pointInternalServiceImpl.earn(KEY, request);

        assertThat(result.alreadyProcessed()).isTrue();
        verify(entityManager).clear();
        verify(idempotencySupport).findByKeyInNewTransaction(KEY);
    }

    @Test
    void earn_UNIQUE_위반_해시불일치_409() {
        EarnRequest request = createEarnRequest();
        Member member = createMember();

        when(pointHistoryRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(memberRepository.findByIdAndDeletedAtIsNull(MEMBER_ID)).thenReturn(Optional.of(member));
        when(pointHistoryRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("Duplicate"));
        when(idempotencySupport.findByKeyInNewTransaction(KEY))
                .thenReturn(Optional.of(createHistory("different-hash")));

        assertThatThrownBy(() -> pointInternalServiceImpl.earn(KEY, request))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
    }

    @Test
    void earn_UNIQUE_위반_키로_못찾으면_원래예외_전파() {
        EarnRequest request = createEarnRequest();
        Member member = createMember();

        when(pointHistoryRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(memberRepository.findByIdAndDeletedAtIsNull(MEMBER_ID)).thenReturn(Optional.of(member));
        when(pointHistoryRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("FK violation"));
        // 키로 재조회 실패 -> 다른 제약 위반
        when(idempotencySupport.findByKeyInNewTransaction(KEY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> pointInternalServiceImpl.earn(KEY, request))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void earn_키_없으면_예외() {
        EarnRequest request = createEarnRequest();

        assertThatThrownBy(() -> pointInternalServiceImpl.earn(null, request))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
    }

    @Test
    void earn_amount_0이하_예외() {
        EarnRequest request = mock(EarnRequest.class);
        when(request.getAmount()).thenReturn(BigDecimal.ZERO);

        assertThatThrownBy(() -> pointInternalServiceImpl.earn(KEY, request))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.POINT_INVALID_AMOUNT);
    }

    @Test
    void earn_잘못된_referenceType_예외() {
        EarnRequest request = mock(EarnRequest.class);
        when(request.getAmount()).thenReturn(AMOUNT);
        when(request.getReferenceType()).thenReturn("INVALID");

        assertThatThrownBy(() -> pointInternalServiceImpl.earn(KEY, request))
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

        PointResult<EarnResponse> result = pointInternalServiceImpl.earn(KEY, request);

        assertThat(result.alreadyProcessed()).isFalse();
        assertThat(result.data().getMemberId()).isEqualTo(MEMBER_ID);
    }

    @Test
    void earn_type_null이면_VALIDATION_FAILED() {
        EarnRequest request = mock(EarnRequest.class);
        when(request.getAmount()).thenReturn(AMOUNT);
        when(request.getReferenceType()).thenReturn(REF_TYPE);
        when(request.getType()).thenReturn(null);

        assertThatThrownBy(() -> pointInternalServiceImpl.earn(KEY, request))
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

        assertThatThrownBy(() -> pointInternalServiceImpl.earn(KEY, request))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEMBER_NOT_FOUND);
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

        PointResult<SpendResponse> result = pointInternalServiceImpl.spend(KEY, request);

        assertThat(result.alreadyProcessed()).isFalse();
        assertThat(result.data().getMemberId()).isEqualTo(MEMBER_ID);
        assertThat(result.data().getType()).isEqualTo("SPEND_MARKET");
        assertThat(result.data().getAmount()).isEqualTo("10.00");
        assertThat(result.data().getBalanceSnapshot()).isEqualTo("90.00");

        verify(pointHistoryRepository).saveAndFlush(any(PointHistory.class));
    }

    @Test
    void spend_잔액_부족() {
        Member member = createMemberMock(new BigDecimal("5.00"));
        SpendRequest request = createSpendRequest();

        when(pointHistoryRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(memberRepository.findByIdAndDeletedAtIsNull(MEMBER_ID)).thenReturn(Optional.of(member));
        when(memberRepository.spendPoint(eq(MEMBER_ID), any())).thenReturn(0);

        assertThatThrownBy(() -> pointInternalServiceImpl.spend(KEY, request))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.POINT_INSUFFICIENT);

        verify(pointHistoryRepository).saveAndFlush(any(PointHistory.class));
    }

    @Test
    void spend_멱등성_동일요청_SUCCEEDED_재시도() {
        SpendRequest request = createSpendRequest();
        String hash = RequestHashUtil.compute(MEMBER_ID, "SPEND_MARKET", AMOUNT, REF_TYPE, REF_ID);

        when(pointHistoryRepository.findByIdempotencyKey(KEY))
                .thenReturn(Optional.of(createSpendHistory(PointTransactionStatus.SUCCEEDED, hash)));

        PointResult<SpendResponse> result = pointInternalServiceImpl.spend(KEY, request);

        assertThat(result.alreadyProcessed()).isTrue();
        assertThat(result.data().getMemberId()).isEqualTo(MEMBER_ID);
    }

    @Test
    void spend_멱등성_동일요청_FAILED_재시도_같은_실패_재현() {
        SpendRequest request = createSpendRequest();
        String hash = RequestHashUtil.compute(MEMBER_ID, "SPEND_MARKET", AMOUNT, REF_TYPE, REF_ID);

        when(pointHistoryRepository.findByIdempotencyKey(KEY))
                .thenReturn(Optional.of(createSpendHistory(PointTransactionStatus.FAILED, hash)));

        // "같은 요청 -> 같은 응답" — 실패에도 적용
        assertThatThrownBy(() -> pointInternalServiceImpl.spend(KEY, request))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.POINT_INSUFFICIENT);
    }

    @Test
    void spend_멱등성_키충돌() {
        SpendRequest request = createSpendRequest();

        when(pointHistoryRepository.findByIdempotencyKey(KEY))
                .thenReturn(Optional.of(createSpendHistory(PointTransactionStatus.SUCCEEDED, "different-hash")));

        assertThatThrownBy(() -> pointInternalServiceImpl.spend(KEY, request))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
    }

    @Test
    void spend_UNIQUE_위반_catch후_재조회_FAILED_같은_실패_재현() {
        SpendRequest request = createSpendRequest();
        String hash = RequestHashUtil.compute(MEMBER_ID, "SPEND_MARKET", AMOUNT, REF_TYPE, REF_ID);
        Member member = createMember();

        when(pointHistoryRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(memberRepository.findByIdAndDeletedAtIsNull(MEMBER_ID)).thenReturn(Optional.of(member));
        when(pointHistoryRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("Duplicate"));
        when(idempotencySupport.findByKeyInNewTransaction(KEY))
                .thenReturn(Optional.of(createSpendHistory(PointTransactionStatus.FAILED, hash)));

        assertThatThrownBy(() -> pointInternalServiceImpl.spend(KEY, request))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.POINT_INSUFFICIENT);

        verify(entityManager).clear();
    }

    @Test
    void spend_키_없으면_예외() {
        SpendRequest request = createSpendRequest();

        assertThatThrownBy(() -> pointInternalServiceImpl.spend(null, request))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
    }

    @Test
    void spend_amount_0이하_예외() {
        SpendRequest request = mock(SpendRequest.class);
        when(request.getAmount()).thenReturn(BigDecimal.ZERO);

        assertThatThrownBy(() -> pointInternalServiceImpl.spend(KEY, request))
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

        PointResult<SpendResponse> result = pointInternalServiceImpl.spend(KEY, request);

        assertThat(result.alreadyProcessed()).isFalse();
        assertThat(result.data().getMemberId()).isEqualTo(MEMBER_ID);
    }

    @Test
    void spend_type_null이면_VALIDATION_FAILED() {
        SpendRequest request = mock(SpendRequest.class);
        when(request.getAmount()).thenReturn(AMOUNT);
        when(request.getReferenceType()).thenReturn(REF_TYPE);
        when(request.getType()).thenReturn(null);

        assertThatThrownBy(() -> pointInternalServiceImpl.spend(KEY, request))
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

        assertThatThrownBy(() -> pointInternalServiceImpl.spend(KEY, request))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEMBER_NOT_FOUND);
    }

    // ─── PENDING 방어 테스트 ──────────────────────────────────

    @Test
    void earn_PENDING상태_이력_발견시_409() {
        EarnRequest request = createEarnRequest();
        String hash = RequestHashUtil.compute(MEMBER_ID, TYPE, AMOUNT, REF_TYPE, REF_ID);

        when(pointHistoryRepository.findByIdempotencyKey(KEY))
                .thenReturn(Optional.of(createHistory(PointTransactionStatus.PENDING, hash)));

        assertThatThrownBy(() -> pointInternalServiceImpl.earn(KEY, request))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
    }

    @Test
    void spend_PENDING상태_이력_발견시_409() {
        SpendRequest request = createSpendRequest();
        String hash = RequestHashUtil.compute(MEMBER_ID, "SPEND_MARKET", AMOUNT, REF_TYPE, REF_ID);

        PointHistory pendingHistory = PointHistory.builder()
                .memberId(MEMBER_ID)
                .type(PointHistoryType.SPEND_MARKET)
                .amount(AMOUNT)
                .balanceSnapshot(BigDecimal.ZERO)
                .idempotencyKey(KEY)
                .requestHash(hash)
                .status(PointTransactionStatus.PENDING)
                .build();

        when(pointHistoryRepository.findByIdempotencyKey(KEY))
                .thenReturn(Optional.of(pendingHistory));

        assertThatThrownBy(() -> pointInternalServiceImpl.spend(KEY, request))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
    }
}
