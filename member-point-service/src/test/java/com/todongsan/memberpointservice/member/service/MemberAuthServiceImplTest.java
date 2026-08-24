package com.todongsan.memberpointservice.member.service;

import com.todongsan.memberpointservice.global.security.JwtProvider;
import com.todongsan.memberpointservice.global.util.CryptoUtil;
import com.todongsan.memberpointservice.member.dto.KakaoUserInfo;
import com.todongsan.memberpointservice.member.dto.response.LoginResponse;
import com.todongsan.memberpointservice.member.entity.Member;
import com.todongsan.memberpointservice.member.entity.MemberRole;
import com.todongsan.memberpointservice.member.entity.OauthToken;
import com.todongsan.memberpointservice.member.repository.MemberRepository;
import com.todongsan.memberpointservice.member.repository.OauthTokenRepository;
import com.todongsan.memberpointservice.point.entity.PointHistory;
import com.todongsan.memberpointservice.point.repository.PointHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.todongsan.memberpointservice.member.entity.AgeGroup;
import com.todongsan.memberpointservice.member.entity.Gender;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MemberAuthServiceImplTest {

    @Mock KakaoOAuthService kakaoOAuthService;
    @Mock MemberRepository memberRepository;
    @Mock OauthTokenRepository oauthTokenRepository;
    @Mock PointHistoryRepository pointHistoryRepository;
    @Mock CryptoUtil cryptoUtil;
    @Mock JwtProvider jwtProvider;

    @InjectMocks
    MemberAuthServiceImpl memberAuthServiceImpl;

    private KakaoUserInfo mockKakaoUserInfo(String kakaoId, String nickname, String ageRange, String gender) {
        KakaoUserInfo info = mock(KakaoUserInfo.class);
        when(info.getKakaoId()).thenReturn(kakaoId);
        when(info.getNickname()).thenReturn(nickname);
        when(info.getEmail()).thenReturn(nickname + "@kakao.com");
        when(info.getAgeRange()).thenReturn(ageRange);
        when(info.getGender()).thenReturn(gender);
        return info;
    }

    private Member mockMember(Long id, String nickname) {
        Member member = mock(Member.class);
        when(member.getId()).thenReturn(id);
        when(member.getNickname()).thenReturn(nickname);
        when(member.getRole()).thenReturn(MemberRole.USER);
        when(member.getPointBalance()).thenReturn(BigDecimal.valueOf(50));
        return member;
    }

    @Test
    void kakaoLogin_신규회원_회원저장_토큰저장_포인트적립() {
        KakaoUserInfo info = mockKakaoUserInfo("12345", "새유저", "20~29", "male");
        when(kakaoOAuthService.getUserInfo("kakao-token")).thenReturn(info);
        when(memberRepository.findByOauthProviderAndOauthId("KAKAO", "12345"))
                .thenReturn(Optional.empty());

        Member saved = mockMember(1L, "새유저");
        when(memberRepository.save(any(Member.class))).thenReturn(saved);
        when(cryptoUtil.encrypt("kakao-token")).thenReturn("encrypted");
        when(pointHistoryRepository.findByIdempotencyKey("SIGNUP:1")).thenReturn(Optional.empty());
        when(memberRepository.earnPoint(1L, BigDecimal.valueOf(200))).thenReturn(1);
        when(memberRepository.findById(1L)).thenReturn(Optional.of(saved));
        when(jwtProvider.generateAccessToken(1L, MemberRole.USER)).thenReturn("access-jwt");
        when(jwtProvider.generateRefreshToken(1L)).thenReturn("refresh-jwt");

        LoginResponse response = memberAuthServiceImpl.kakaoLogin("kakao-token");

        assertThat(response.isNewMember()).isTrue();
        assertThat(response.getMemberId()).isEqualTo(1L);
        assertThat(response.getNickname()).isEqualTo("새유저");
        assertThat(response.getAccessToken()).isEqualTo("access-jwt");
        assertThat(response.getRefreshToken()).isEqualTo("refresh-jwt");

        verify(memberRepository).save(any(Member.class));
        verify(oauthTokenRepository).save(any(OauthToken.class));
        verify(memberRepository).earnPoint(1L, BigDecimal.valueOf(200));
        verify(pointHistoryRepository).save(any(PointHistory.class));
    }

    @Test
    void kakaoLogin_신규회원_가입보상_멱등성_중복처리_안함() {
        KakaoUserInfo info = mockKakaoUserInfo("12345", "새유저", "20~29", "male");
        when(kakaoOAuthService.getUserInfo("kakao-token")).thenReturn(info);
        when(memberRepository.findByOauthProviderAndOauthId("KAKAO", "12345"))
                .thenReturn(Optional.empty());

        Member saved = mockMember(1L, "새유저");
        when(memberRepository.save(any(Member.class))).thenReturn(saved);
        when(cryptoUtil.encrypt("kakao-token")).thenReturn("encrypted");
        // 이미 SIGNUP 포인트가 존재 → 중복 적립 방지
        when(pointHistoryRepository.findByIdempotencyKey("SIGNUP:1"))
                .thenReturn(Optional.of(mock(PointHistory.class)));
        when(jwtProvider.generateAccessToken(anyLong(), any())).thenReturn("access-jwt");
        when(jwtProvider.generateRefreshToken(anyLong())).thenReturn("refresh-jwt");

        memberAuthServiceImpl.kakaoLogin("kakao-token");

        verify(memberRepository, never()).earnPoint(anyLong(), any());
        verify(pointHistoryRepository, never()).save(any(PointHistory.class));
    }

    @Test
    void kakaoLogin_기존회원_카카오_액세스토큰만_갱신() {
        KakaoUserInfo info = mockKakaoUserInfo("12345", "기존유저", "30~39", "female");
        when(kakaoOAuthService.getUserInfo("new-kakao-token")).thenReturn(info);

        Member existing = mockMember(2L, "기존유저");
        when(memberRepository.findByOauthProviderAndOauthId("KAKAO", "12345"))
                .thenReturn(Optional.of(existing));

        OauthToken oauthToken = mock(OauthToken.class);
        when(oauthToken.getRefreshToken()).thenReturn("old-encrypted-refresh");
        when(oauthToken.getRefreshTokenExpiresAt()).thenReturn(LocalDateTime.now().plusDays(50));
        when(oauthTokenRepository.findByMemberId(2L)).thenReturn(Optional.of(oauthToken));

        when(cryptoUtil.encrypt("new-kakao-token")).thenReturn("new-encrypted-access");
        when(jwtProvider.generateAccessToken(2L, MemberRole.USER)).thenReturn("access-jwt-2");
        when(jwtProvider.generateRefreshToken(2L)).thenReturn("refresh-jwt-2");

        LoginResponse response = memberAuthServiceImpl.kakaoLogin("new-kakao-token");

        assertThat(response.isNewMember()).isFalse();
        assertThat(response.getMemberId()).isEqualTo(2L);

        verify(memberRepository, never()).save(any());
        verify(oauthToken).updateTokens(eq("new-encrypted-access"), eq("old-encrypted-refresh"),
                any(), any());
    }

    @Test
    void kakaoLogin_ageRange_null이면_AgeGroup_UNKNOWN() {
        KakaoUserInfo info = mockKakaoUserInfo("99999", "익명유저", null, null);
        when(kakaoOAuthService.getUserInfo("token")).thenReturn(info);
        when(memberRepository.findByOauthProviderAndOauthId("KAKAO", "99999"))
                .thenReturn(Optional.empty());

        Member saved = mockMember(3L, "익명유저");
        when(memberRepository.save(any(Member.class))).thenReturn(saved);
        when(cryptoUtil.encrypt("token")).thenReturn("enc");
        when(pointHistoryRepository.findByIdempotencyKey("SIGNUP:3")).thenReturn(Optional.empty());
        when(memberRepository.earnPoint(anyLong(), any())).thenReturn(1);
        when(memberRepository.findById(3L)).thenReturn(Optional.of(saved));
        when(jwtProvider.generateAccessToken(anyLong(), any())).thenReturn("jwt");
        when(jwtProvider.generateRefreshToken(anyLong())).thenReturn("refresh");

        // ageRange=null, gender=null → AgeGroup.UNKNOWN, Gender.UNKNOWN으로 Member가 생성돼야 함
        // 실제 Member 생성 인자는 save() 호출 시 검증 (ArgumentCaptor)
        org.mockito.ArgumentCaptor<Member> captor = org.mockito.ArgumentCaptor.forClass(Member.class);
        memberAuthServiceImpl.kakaoLogin("token");
        verify(memberRepository).save(captor.capture());

        // builder로 생성된 실제 Member 객체의 ageGroup, gender 검증
        Member createdMember = captor.getValue();
        assertThat(createdMember.getAgeGroup()).isEqualTo(AgeGroup.UNKNOWN);
        assertThat(createdMember.getGender()).isEqualTo(Gender.UNKNOWN);
    }

    @ParameterizedTest(name = "ageRange={0} → {1}, gender={2} → {3}")
    @CsvSource({
            "10~19, AGE_10S,      male,   MALE",
            "20~29, AGE_20S,      female, FEMALE",
            "30~39, AGE_30S,      male,   MALE",
            "40~49, AGE_40S,      female, FEMALE",
            "50~59, AGE_50S_ABOVE,male,   MALE"
    })
    void kakaoLogin_ageRange_gender_변환(String ageRange, AgeGroup expectedAge,
                                        String genderStr, Gender expectedGender) {
        KakaoUserInfo info = mockKakaoUserInfo("77777", "유저", ageRange, genderStr);
        when(kakaoOAuthService.getUserInfo("token")).thenReturn(info);
        when(memberRepository.findByOauthProviderAndOauthId("KAKAO", "77777"))
                .thenReturn(Optional.empty());

        Member saved = mockMember(5L, "유저");
        when(memberRepository.save(any(Member.class))).thenReturn(saved);
        when(cryptoUtil.encrypt("token")).thenReturn("enc");
        when(pointHistoryRepository.findByIdempotencyKey("SIGNUP:5")).thenReturn(Optional.empty());
        when(memberRepository.earnPoint(anyLong(), any())).thenReturn(1);
        when(memberRepository.findById(5L)).thenReturn(Optional.of(saved));
        when(jwtProvider.generateAccessToken(anyLong(), any())).thenReturn("jwt");
        when(jwtProvider.generateRefreshToken(anyLong())).thenReturn("refresh");

        org.mockito.ArgumentCaptor<Member> captor = org.mockito.ArgumentCaptor.forClass(Member.class);
        memberAuthServiceImpl.kakaoLogin("token");
        verify(memberRepository).save(captor.capture());

        Member created = captor.getValue();
        assertThat(created.getAgeGroup()).isEqualTo(expectedAge);
        assertThat(created.getGender()).isEqualTo(expectedGender);
    }
}
