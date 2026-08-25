package com.todongsan.marketservice.market.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.todongsan.marketservice.market.client.MemberPointClient;
import com.todongsan.marketservice.market.client.PointSpendCommand;
import com.todongsan.marketservice.market.client.exception.MemberPointExternalException;
import com.todongsan.marketservice.market.client.exception.MemberPointTimeoutException;
import com.todongsan.marketservice.market.client.exception.MemberPointUnavailableException;
import com.todongsan.marketservice.market.client.exception.PointInsufficientException;
import com.todongsan.marketservice.market.dto.request.CreatePredictionRequest;
import com.todongsan.marketservice.market.entity.MarketPrediction;
import com.todongsan.marketservice.market.service.MarketPredictionTransactionService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class MarketPredictionControllerTest {

    private static final long MARKET_ID = 1L;
    private static final long MEMBER_ID = 10L;
    private static final String IDEMPOTENCY_KEY = "MARKET_PREDICTION_SPEND:market:1:member:10";
    private static final String FIRST_ATTEMPT_KEY = IDEMPOTENCY_KEY + ":attempt:1";
    private static final String SECOND_ATTEMPT_KEY = IDEMPOTENCY_KEY + ":attempt:2";
    private static final LocalDateTime PREDICTION_CREATED_AT = LocalDateTime.of(2026, 6, 2, 15, 30, 0);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MarketPredictionTransactionService transactionService;

    @MockitoBean
    private MemberPointClient memberPointClient;

    @BeforeEach
    void setUp() {
        reset(memberPointClient);
        jdbcTemplate.update("DELETE FROM market_price_history");
        jdbcTemplate.update("DELETE FROM market_refund_detail");
        jdbcTemplate.update("DELETE FROM market_void");
        jdbcTemplate.update("DELETE FROM market_prediction");
        jdbcTemplate.update("DELETE FROM market_option");
        jdbcTemplate.update("DELETE FROM market");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM processed_event");
    }

    @Test
    void createPrediction_Saga_POINT_PENDING_반환_outbox_이벤트_생성() throws Exception {
        insertActiveMarketWithOptions();

        // Saga: API는 POINT_PENDING으로 즉시 반환. 가격 확정은 비동기.
        mockMvc.perform(predictionRequest(MARKET_ID, MEMBER_ID, IDEMPOTENCY_KEY, 1L, "100.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.predictionId").isNumber())
                .andExpect(jsonPath("$.data.marketId").value(MARKET_ID))
                .andExpect(jsonPath("$.data.selectedOptionId").value(1))
                .andExpect(jsonPath("$.data.pointAmount").value("100.00"))
                .andExpect(jsonPath("$.data.status").value("POINT_PENDING"));

        // DB: prediction은 POINT_PENDING, 가격 미확정
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM market_prediction", String.class))
                .isEqualTo("POINT_PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT point_spend_idempotency_key FROM market_prediction", String.class
        )).isEqualTo(FIRST_ATTEMPT_KEY);

        // Outbox: prediction.created 이벤트가 생성됨
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT event_type FROM outbox_event", String.class))
                .isEqualTo("PREDICTION_CREATED");

        // MemberPointClient는 호출되지 않음 (비동기 Saga)
        verifyNoInteractions(memberPointClient);
    }

    @Test
    void createPendingPredictionCommitsPointPendingBeforePointSpend() {
        insertActiveMarketWithOptions();
        CreatePredictionRequest request = new CreatePredictionRequest();
        request.setMarketOptionId(1L);
        request.setPointAmount(new BigDecimal("100.00"));

        MarketPrediction prediction = transactionService.createPendingPrediction(
                MARKET_ID,
                MEMBER_ID,
                IDEMPOTENCY_KEY,
                request
        );

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM market_prediction WHERE id = ?",
                String.class,
                prediction.getId()
        )).isEqualTo("POINT_PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT price_snapshot FROM market_prediction WHERE id = ?",
                String.class,
                prediction.getId()
        )).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT point_spend_idempotency_key FROM market_prediction WHERE id = ?",
                String.class,
                prediction.getId()
        )).isEqualTo(FIRST_ATTEMPT_KEY);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT attempt_no FROM market_prediction WHERE id = ?",
                Integer.class,
                prediction.getId()
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM market_price_history", Integer.class))
                .isZero();
    }

    // REST 동기 spend 분기 테스트 제거됨 — Saga 전환으로 API 시점에 spend가 발생하지 않음.
    // spend 성공/실패/타임아웃은 Kafka 컨슈머(PointResultConsumer)가 비동기로 처리.
    // 해당 시나리오는 Phase 2 Saga 통합 테스트로 검증 예정.

    @Test
    void createPredictionRejectsPendingMarket() throws Exception {
        insertMarket(MARKET_ID, "PENDING", LocalDateTime.now().plusDays(1));
        insertOptions(MARKET_ID);

        expectPredictionError(MARKET_ID, MEMBER_ID, IDEMPOTENCY_KEY, 1L, "100.00", 409, "MARKET_NOT_ACTIVE");
    }

    @Test
    void createPredictionRejectsClosedMarket() throws Exception {
        insertMarket(MARKET_ID, "CLOSED", LocalDateTime.now().plusDays(1));
        insertOptions(MARKET_ID);

        expectPredictionError(MARKET_ID, MEMBER_ID, IDEMPOTENCY_KEY, 1L, "100.00", 409, "MARKET_NOT_ACTIVE");
    }

    @Test
    void createPredictionRejectsActiveMarketAfterCloseAt() throws Exception {
        insertMarket(MARKET_ID, "ACTIVE", LocalDateTime.now().minusDays(1));
        insertOptions(MARKET_ID);

        expectPredictionError(MARKET_ID, MEMBER_ID, IDEMPOTENCY_KEY, 1L, "100.00", 409, "MARKET_CLOSED");

        // closeAt이 지난 ACTIVE Market에서는 차단 외에 어떤 부작용도 없어야 한다.
        verifyNoInteractions(memberPointClient);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM market_prediction",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM market_price_history",
                Integer.class
        )).isZero();
    }

    @Test
    void createPredictionRejectsMissingMarket() throws Exception {
        expectPredictionError(MARKET_ID, MEMBER_ID, IDEMPOTENCY_KEY, 1L, "100.00", 404, "MARKET_NOT_FOUND");
    }

    @Test
    void createPredictionRejectsOptionFromDifferentMarket() throws Exception {
        insertActiveMarketWithOptions();
        insertMarket(2L, "ACTIVE", LocalDateTime.now().plusDays(1));
        insertOption(3L, 2L, "OTHER", 1);

        expectPredictionError(MARKET_ID, MEMBER_ID, IDEMPOTENCY_KEY, 3L, "100.00", 404, "MARKET_OPTION_NOT_FOUND");
    }

    @Test
    void createPredictionRejectsPointAmountBelowMinimum() throws Exception {
        insertActiveMarketWithOptions();

        expectPredictionError(MARKET_ID, MEMBER_ID, IDEMPOTENCY_KEY, 1L, "9.99", 400, "MARKET_INVALID_BET_AMOUNT");
    }

    @Test
    void createPredictionRejectsPointAmountAboveMaximum() throws Exception {
        insertActiveMarketWithOptions();

        expectPredictionError(MARKET_ID, MEMBER_ID, IDEMPOTENCY_KEY, 1L, "500.01", 400, "MARKET_INVALID_BET_AMOUNT");
    }

    @Test
    void createPredictionRejectsPointAmountWithMoreThanTwoDecimalPlaces() throws Exception {
        insertActiveMarketWithOptions();

        expectPredictionError(MARKET_ID, MEMBER_ID, IDEMPOTENCY_KEY, 1L, "100.001", 400, "MARKET_INVALID_BET_AMOUNT");
    }

    @Test
    void createPredictionRejectsDuplicatedMemberForSameMarket() throws Exception {
        insertActiveMarketWithOptions();
        mockMvc.perform(predictionRequest(MARKET_ID, MEMBER_ID, IDEMPOTENCY_KEY, 1L, "100.00"))
                .andExpect(status().isOk());

        expectPredictionError(MARKET_ID, MEMBER_ID, IDEMPOTENCY_KEY, 2L, "100.00", 409, "MARKET_ALREADY_PREDICTED");
    }

    @Test
    void createPredictionRejectsMissingIdempotencyKey() throws Exception {
        insertActiveMarketWithOptions();

        mockMvc.perform(post("/api/v1/markets/{marketId}/predictions", MARKET_ID)
                        .header("X-Member-Id", MEMBER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(1L, "100.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void createPredictionRejectsMissingMemberId() throws Exception {
        insertActiveMarketWithOptions();

        mockMvc.perform(post("/api/v1/markets/{marketId}/predictions", MARKET_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(1L, "100.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void createPredictionRejectsIdempotencyKeyWithDifferentMarketId() throws Exception {
        insertActiveMarketWithOptions();

        expectPredictionError(
                MARKET_ID,
                MEMBER_ID,
                "MARKET_PREDICTION_SPEND:market:999:member:10",
                1L,
                "100.00",
                400,
                "VALIDATION_FAILED"
        );
    }

    @Test
    void createPredictionRejectsIdempotencyKeyWithDifferentMemberId() throws Exception {
        insertActiveMarketWithOptions();

        expectPredictionError(
                MARKET_ID,
                MEMBER_ID,
                "MARKET_PREDICTION_SPEND:market:1:member:999",
                1L,
                "100.00",
                400,
                "VALIDATION_FAILED"
        );
    }

    @Test
    void createPredictionRejectsMalformedIdempotencyKey() throws Exception {
        insertActiveMarketWithOptions();

        expectPredictionError(MARKET_ID, MEMBER_ID, "WRONG_KEY", 1L, "100.00", 400, "VALIDATION_FAILED");
    }

    @Test
    void getMyPredictionReturnsConfirmedPrediction() throws Exception {
        insertActiveMarketWithOptions();
        insertPrediction(
                100L,
                MEMBER_ID,
                "10.00",
                "0.40000000",
                "25.00000000",
                "CONFIRMED",
                PREDICTION_CREATED_AT,
                PREDICTION_CREATED_AT.plusSeconds(1)
        );
        insertPrediction(101L, 11L, "40.00", "0.22857143", "175.00000000", "CONFIRMED",
                PREDICTION_CREATED_AT, PREDICTION_CREATED_AT);
        insertPredictionForMarket(102L, MARKET_ID, 2L, 12L, "50.00", "0.50000000", "100.00000000",
                "CONFIRMED", PREDICTION_CREATED_AT, PREDICTION_CREATED_AT, null, null);

        mockMvc.perform(get("/api/v1/markets/{marketId}/predictions/me", MARKET_ID)
                        .header("X-Member-Id", MEMBER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.predictionId").value(100))
                .andExpect(jsonPath("$.data.marketId").value(MARKET_ID))
                .andExpect(jsonPath("$.data.selectedOptionId").value(1))
                .andExpect(jsonPath("$.data.pointAmount").value("10.00"))
                .andExpect(jsonPath("$.data.priceSnapshot").value("0.40000000"))
                .andExpect(jsonPath("$.data.contractQuantity").value("25.00000000"))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.createdAt").value("2026-06-02T15:30:00"))
                .andExpect(jsonPath("$.data.updatedAt").value("2026-06-02T15:30:01"))
                .andExpect(jsonPath("$.data.estimatedPayoutIfWin").value("15.93"))
                .andExpect(jsonPath("$.data.estimatedProfitIfWin").value("5.93"))
                .andExpect(jsonPath("$.data.estimatedProfitRateIfWin").value("59.30"))
                .andExpect(jsonPath("$.data.currentPayoutPerContract").value("0.23750000"))
                .andExpect(jsonPath("$.data.estimateBaseTotalPool").value("100.00"))
                .andExpect(jsonPath("$.data.estimateBaseSettlementPool").value("47.50"))
                .andExpect(jsonPath("$.data.estimateBaseOptionContractQuantity").value("200.00000000"));
    }

    @Test
    void getMyPredictionReturnsPointPendingWithNullCalculatedValues() throws Exception {
        insertActiveMarketWithOptions();
        insertPrediction(
                100L,
                MEMBER_ID,
                "100.00",
                null,
                null,
                "POINT_PENDING",
                PREDICTION_CREATED_AT,
                PREDICTION_CREATED_AT
        );

        mockMvc.perform(get("/api/v1/markets/{marketId}/predictions/me", MARKET_ID)
                        .header("X-Member-Id", MEMBER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.priceSnapshot").value(nullValue()))
                .andExpect(jsonPath("$.data.contractQuantity").value(nullValue()))
                .andExpect(jsonPath("$.data.status").value("POINT_PENDING"))
                .andExpect(jsonPath("$.data.estimatedPayoutIfWin").value(nullValue()))
                .andExpect(jsonPath("$.data.estimatedProfitIfWin").value(nullValue()))
                .andExpect(jsonPath("$.data.estimatedProfitRateIfWin").value(nullValue()))
                .andExpect(jsonPath("$.data.currentPayoutPerContract").value(nullValue()))
                .andExpect(jsonPath("$.data.estimateBaseTotalPool").value(nullValue()))
                .andExpect(jsonPath("$.data.estimateBaseSettlementPool").value(nullValue()))
                .andExpect(jsonPath("$.data.estimateBaseOptionContractQuantity").value(nullValue()));
    }

    @Test
    void getMyPredictionReturnsNullEstimateForNonConfirmedStatuses() throws Exception {
        String[] statuses = {"POINT_UNKNOWN", "FAILED", "SETTLED", "REFUND_PENDING", "REFUND_UNKNOWN", "REFUNDED"};

        for (int index = 0; index < statuses.length; index++) {
            long marketId = index + 1L;
            insertMarket(marketId, "ACTIVE", LocalDateTime.now().plusDays(1));
            insertOption(marketId, marketId, "A", 1);
            insertPredictionForMarket(100L + index, marketId, marketId, MEMBER_ID, "10.00", "0.40000000",
                    "25.00000000", statuses[index], PREDICTION_CREATED_AT, PREDICTION_CREATED_AT, null, null);

            mockMvc.perform(get("/api/v1/markets/{marketId}/predictions/me", marketId)
                            .header("X-Member-Id", MEMBER_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.estimatedPayoutIfWin").value(nullValue()))
                    .andExpect(jsonPath("$.data.estimatedProfitIfWin").value(nullValue()))
                    .andExpect(jsonPath("$.data.estimatedProfitRateIfWin").value(nullValue()))
                    .andExpect(jsonPath("$.data.currentPayoutPerContract").value(nullValue()))
                    .andExpect(jsonPath("$.data.estimateBaseTotalPool").value(nullValue()))
                    .andExpect(jsonPath("$.data.estimateBaseSettlementPool").value(nullValue()))
                    .andExpect(jsonPath("$.data.estimateBaseOptionContractQuantity").value(nullValue()));
        }
    }

    @Test
    void getMyPredictionReturnsNullEstimateWhenOptionContractQuantityIsZero() throws Exception {
        insertActiveMarketWithOptions();
        insertPrediction(100L, MEMBER_ID, "10.00", "0.40000000", "0.00000000", "CONFIRMED",
                PREDICTION_CREATED_AT, PREDICTION_CREATED_AT);

        mockMvc.perform(get("/api/v1/markets/{marketId}/predictions/me", MARKET_ID)
                        .header("X-Member-Id", MEMBER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentPayoutPerContract").value(nullValue()))
                .andExpect(jsonPath("$.data.estimatedPayoutIfWin").value(nullValue()))
                .andExpect(jsonPath("$.data.estimatedProfitIfWin").value(nullValue()));
    }

    @Test
    void getMyPredictionRejectsMissingMemberId() throws Exception {
        insertActiveMarketWithOptions();

        mockMvc.perform(get("/api/v1/markets/{marketId}/predictions/me", MARKET_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void getMyPredictionRejectsMissingMarket() throws Exception {
        mockMvc.perform(get("/api/v1/markets/{marketId}/predictions/me", MARKET_ID)
                        .header("X-Member-Id", MEMBER_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("MARKET_NOT_FOUND"));
    }

    @Test
    void getMyPredictionRejectsMissingPrediction() throws Exception {
        insertActiveMarketWithOptions();

        expectMyPredictionNotFound(MEMBER_ID);
    }

    @Test
    void getMyPredictionDoesNotReturnAnotherMembersPrediction() throws Exception {
        insertActiveMarketWithOptions();
        insertPrediction(
                100L,
                MEMBER_ID,
                "100.00",
                "0.50000000",
                "200.00000000",
                "CONFIRMED",
                PREDICTION_CREATED_AT,
                PREDICTION_CREATED_AT.plusSeconds(1)
        );

        expectMyPredictionNotFound(99L);
    }

    @Test
    void getMyPredictionsReturnsOnlyCurrentMemberPredictionsInLatestOrder() throws Exception {
        insertMarket(1L, "ACTIVE", LocalDateTime.now().plusDays(1));
        insertOption(1L, 1L, "A", 1);
        insertMarket(2L, "ACTIVE", LocalDateTime.now().plusDays(1));
        insertOption(2L, 2L, "B", 1);
        insertMarket(3L, "ACTIVE", LocalDateTime.now().plusDays(1));
        insertOption(3L, 3L, "C", 1);

        insertPredictionForMarket(100L, 1L, 1L, MEMBER_ID, "100.00", "0.50000000", "200.00000000",
                "CONFIRMED", PREDICTION_CREATED_AT, PREDICTION_CREATED_AT.plusSeconds(1), null, null);
        insertPredictionForMarket(101L, 2L, 2L, MEMBER_ID, "200.00", "0.25000000", "800.00000000",
                "SETTLED", PREDICTION_CREATED_AT.plusDays(1), PREDICTION_CREATED_AT.plusDays(1).plusSeconds(1),
                "350.00", null);
        insertPredictionForMarket(102L, 3L, 3L, 2L, "300.00", "0.75000000", "400.00000000",
                "CONFIRMED", PREDICTION_CREATED_AT.plusDays(2), PREDICTION_CREATED_AT.plusDays(2), null, null);

        mockMvc.perform(get("/api/v1/markets/predictions/me")
                        .header("X-Member-Id", MEMBER_ID)
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].predictionId").value(101))
                .andExpect(jsonPath("$.data.content[0].marketId").value(2))
                .andExpect(jsonPath("$.data.content[0].marketTitle").value("Prediction Test Market"))
                .andExpect(jsonPath("$.data.content[0].marketStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.content[0].marketDisplayStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.content[0].canPredict").value(true))
                .andExpect(jsonPath("$.data.content[0].selectedOptionId").value(2))
                .andExpect(jsonPath("$.data.content[0].selectedOptionContent").value("B"))
                .andExpect(jsonPath("$.data.content[0].pointAmount").value("200.00"))
                .andExpect(jsonPath("$.data.content[0].priceSnapshot").value("0.25000000"))
                .andExpect(jsonPath("$.data.content[0].contractQuantity").value("800.00000000"))
                .andExpect(jsonPath("$.data.content[0].predictionStatus").value("SETTLED"))
                .andExpect(jsonPath("$.data.content[0].settledAmount").value("350.00"))
                .andExpect(jsonPath("$.data.content[0].refundAmount").value(nullValue()))
                .andExpect(jsonPath("$.data.content[1].predictionId").value(100))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.last").value(true));
    }

    @Test
    void getMyPredictionsReturnsCurrentPayoutEstimateWithoutPerItemLookup() throws Exception {
        insertActiveMarketWithOptions();
        insertPrediction(100L, MEMBER_ID, "10.00", "0.40000000", "25.00000000", "CONFIRMED",
                PREDICTION_CREATED_AT, PREDICTION_CREATED_AT);
        insertPrediction(101L, 11L, "40.00", "0.22857143", "175.00000000", "CONFIRMED",
                PREDICTION_CREATED_AT, PREDICTION_CREATED_AT);
        insertPredictionForMarket(102L, MARKET_ID, 2L, 12L, "50.00", "0.50000000", "100.00000000",
                "CONFIRMED", PREDICTION_CREATED_AT, PREDICTION_CREATED_AT, null, null);

        mockMvc.perform(get("/api/v1/markets/predictions/me")
                        .header("X-Member-Id", MEMBER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].estimatedPayoutIfWin").value("15.93"))
                .andExpect(jsonPath("$.data.content[0].estimatedProfitIfWin").value("5.93"))
                .andExpect(jsonPath("$.data.content[0].estimatedProfitRateIfWin").value("59.30"))
                .andExpect(jsonPath("$.data.content[0].currentPayoutPerContract").value("0.23750000"))
                .andExpect(jsonPath("$.data.content[0].estimateBaseTotalPool").value("100.00"))
                .andExpect(jsonPath("$.data.content[0].estimateBaseSettlementPool").value("47.50"))
                .andExpect(jsonPath("$.data.content[0].estimateBaseOptionContractQuantity")
                        .value("200.00000000"));
    }

    @Test
    void getMyPredictionsReturnsEmptyPageWhenMemberHasNoPredictions() throws Exception {
        insertActiveMarketWithOptions();

        mockMvc.perform(get("/api/v1/markets/predictions/me")
                        .header("X-Member-Id", MEMBER_ID)
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.totalPages").value(0))
                .andExpect(jsonPath("$.data.last").value(true));
    }

    @Test
    void getMyPredictionsShowsClosedByTimeForActiveMarketAfterCloseAt() throws Exception {
        insertMarket(MARKET_ID, "ACTIVE", LocalDateTime.now().minusSeconds(1));
        insertOptions(MARKET_ID);
        insertPrediction(
                100L,
                MEMBER_ID,
                "100.00",
                "0.50000000",
                "200.00000000",
                "CONFIRMED",
                PREDICTION_CREATED_AT,
                PREDICTION_CREATED_AT
        );

        mockMvc.perform(get("/api/v1/markets/predictions/me")
                        .header("X-Member-Id", MEMBER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].marketStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.content[0].canPredict").value(false))
                .andExpect(jsonPath("$.data.content[0].marketDisplayStatus").value("CLOSED_BY_TIME"));
    }

    @Test
    void getMyPredictionsShowsActiveDisplayStatusForFutureActiveMarket() throws Exception {
        insertActiveMarketWithOptions();
        insertPrediction(
                100L,
                MEMBER_ID,
                "100.00",
                "0.50000000",
                "200.00000000",
                "CONFIRMED",
                PREDICTION_CREATED_AT,
                PREDICTION_CREATED_AT
        );

        mockMvc.perform(get("/api/v1/markets/predictions/me")
                        .header("X-Member-Id", MEMBER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].canPredict").value(true))
                .andExpect(jsonPath("$.data.content[0].marketDisplayStatus").value("ACTIVE"));
    }

    @Test
    void getMyPredictionsReturnsNullableSettlementAndRefundAmounts() throws Exception {
        insertMarket(MARKET_ID, "VOIDED", LocalDateTime.now().minusDays(1));
        insertOptions(MARKET_ID);
        insertPredictionForMarket(100L, MARKET_ID, 1L, MEMBER_ID, "100.00", "0.50000000", "200.00000000",
                "REFUNDED", PREDICTION_CREATED_AT, PREDICTION_CREATED_AT.plusSeconds(1), null, "100.00");

        mockMvc.perform(get("/api/v1/markets/predictions/me")
                        .header("X-Member-Id", MEMBER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].settledAmount").value(nullValue()))
                .andExpect(jsonPath("$.data.content[0].refundAmount").value("100.00"));
    }

    @Test
    void getMyPredictionsFiltersByMarketDisplayStatusActive() throws Exception {
        insertMarket(1L, "ACTIVE", LocalDateTime.now().plusDays(1));
        insertOption(1L, 1L, "A", 1);
        insertMarket(2L, "ACTIVE", LocalDateTime.now().minusSeconds(1));
        insertOption(2L, 2L, "B", 1);
        insertMarket(3L, "SETTLED", LocalDateTime.now().minusDays(1));
        insertOption(3L, 3L, "C", 1);
        insertPredictionForMarket(100L, 1L, 1L, MEMBER_ID, "100.00", "0.50000000", "200.00000000",
                "CONFIRMED", PREDICTION_CREATED_AT, PREDICTION_CREATED_AT, null, null);
        insertPredictionForMarket(101L, 2L, 2L, MEMBER_ID, "100.00", "0.50000000", "200.00000000",
                "CONFIRMED", PREDICTION_CREATED_AT.plusSeconds(1), PREDICTION_CREATED_AT.plusSeconds(1), null, null);
        insertPredictionForMarket(102L, 3L, 3L, MEMBER_ID, "100.00", "0.50000000", "200.00000000",
                "SETTLED", PREDICTION_CREATED_AT.plusSeconds(2), PREDICTION_CREATED_AT.plusSeconds(2), "100.00", null);

        mockMvc.perform(get("/api/v1/markets/predictions/me")
                        .header("X-Member-Id", MEMBER_ID)
                        .param("marketDisplayStatus", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].predictionId").value(100))
                .andExpect(jsonPath("$.data.content[0].marketDisplayStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void getMyPredictionsFiltersByMarketDisplayStatusClosedByTime() throws Exception {
        insertMarket(1L, "ACTIVE", LocalDateTime.now().plusDays(1));
        insertOption(1L, 1L, "A", 1);
        insertMarket(2L, "ACTIVE", LocalDateTime.now().minusSeconds(1));
        insertOption(2L, 2L, "B", 1);
        insertPredictionForMarket(100L, 1L, 1L, MEMBER_ID, "100.00", "0.50000000", "200.00000000",
                "CONFIRMED", PREDICTION_CREATED_AT, PREDICTION_CREATED_AT, null, null);
        insertPredictionForMarket(101L, 2L, 2L, MEMBER_ID, "100.00", "0.50000000", "200.00000000",
                "CONFIRMED", PREDICTION_CREATED_AT.plusSeconds(1), PREDICTION_CREATED_AT.plusSeconds(1), null, null);

        mockMvc.perform(get("/api/v1/markets/predictions/me")
                        .header("X-Member-Id", MEMBER_ID)
                        .param("marketDisplayStatus", "CLOSED_BY_TIME"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].predictionId").value(101))
                .andExpect(jsonPath("$.data.content[0].marketDisplayStatus").value("CLOSED_BY_TIME"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void getMyPredictionsFiltersByPredictionStatusConfirmed() throws Exception {
        insertMarket(1L, "ACTIVE", LocalDateTime.now().plusDays(1));
        insertOption(1L, 1L, "A", 1);
        insertMarket(2L, "ACTIVE", LocalDateTime.now().plusDays(1));
        insertOption(2L, 2L, "B", 1);
        insertPredictionForMarket(100L, 1L, 1L, MEMBER_ID, "100.00", "0.50000000", "200.00000000",
                "CONFIRMED", PREDICTION_CREATED_AT, PREDICTION_CREATED_AT, null, null);
        insertPredictionForMarket(101L, 2L, 2L, MEMBER_ID, "100.00", "0.50000000", "200.00000000",
                "POINT_PENDING", PREDICTION_CREATED_AT.plusSeconds(1), PREDICTION_CREATED_AT.plusSeconds(1), null, null);

        mockMvc.perform(get("/api/v1/markets/predictions/me")
                        .header("X-Member-Id", MEMBER_ID)
                        .param("predictionStatus", "CONFIRMED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].predictionStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void getMyPredictionsFiltersByRepeatedPredictionStatuses() throws Exception {
        insertMarket(1L, "ACTIVE", LocalDateTime.now().plusDays(1));
        insertOption(1L, 1L, "A", 1);
        insertMarket(2L, "ACTIVE", LocalDateTime.now().plusDays(1));
        insertOption(2L, 2L, "B", 1);
        insertMarket(3L, "ACTIVE", LocalDateTime.now().plusDays(1));
        insertOption(3L, 3L, "C", 1);
        insertPredictionForMarket(100L, 1L, 1L, MEMBER_ID, "100.00", null, null,
                "POINT_PENDING", PREDICTION_CREATED_AT, PREDICTION_CREATED_AT, null, null);
        insertPredictionForMarket(101L, 2L, 2L, MEMBER_ID, "100.00", null, null,
                "POINT_UNKNOWN", PREDICTION_CREATED_AT.plusSeconds(1), PREDICTION_CREATED_AT.plusSeconds(1), null, null);
        insertPredictionForMarket(102L, 3L, 3L, MEMBER_ID, "100.00", "0.50000000", "200.00000000",
                "CONFIRMED", PREDICTION_CREATED_AT.plusSeconds(2), PREDICTION_CREATED_AT.plusSeconds(2), null, null);

        mockMvc.perform(get("/api/v1/markets/predictions/me")
                        .header("X-Member-Id", MEMBER_ID)
                        .param("predictionStatus", "POINT_PENDING", "POINT_UNKNOWN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].predictionId").value(101))
                .andExpect(jsonPath("$.data.content[1].predictionId").value(100))
                .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    @Test
    void getMyPredictionsFiltersByCommaSeparatedPredictionStatuses() throws Exception {
        insertMarket(1L, "ACTIVE", LocalDateTime.now().plusDays(1));
        insertOption(1L, 1L, "A", 1);
        insertMarket(2L, "ACTIVE", LocalDateTime.now().plusDays(1));
        insertOption(2L, 2L, "B", 1);
        insertPredictionForMarket(100L, 1L, 1L, MEMBER_ID, "100.00", null, null,
                "POINT_PENDING", PREDICTION_CREATED_AT, PREDICTION_CREATED_AT, null, null);
        insertPredictionForMarket(101L, 2L, 2L, MEMBER_ID, "100.00", null, null,
                "POINT_UNKNOWN", PREDICTION_CREATED_AT.plusSeconds(1), PREDICTION_CREATED_AT.plusSeconds(1), null, null);

        mockMvc.perform(get("/api/v1/markets/predictions/me")
                        .header("X-Member-Id", MEMBER_ID)
                        .param("predictionStatus", "POINT_PENDING,POINT_UNKNOWN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    @Test
    void getMyPredictionsReturnsEmptyPageWhenFilterHasNoMatches() throws Exception {
        insertMarket(1L, "ACTIVE", LocalDateTime.now().plusDays(1));
        insertOption(1L, 1L, "A", 1);
        insertPredictionForMarket(100L, 1L, 1L, MEMBER_ID, "100.00", "0.50000000", "200.00000000",
                "CONFIRMED", PREDICTION_CREATED_AT, PREDICTION_CREATED_AT, null, null);

        mockMvc.perform(get("/api/v1/markets/predictions/me")
                        .header("X-Member-Id", MEMBER_ID)
                        .param("marketDisplayStatus", "VOIDED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void getMyPredictionsCombinesMarketAndPredictionStatusFilters() throws Exception {
        insertMarket(1L, "ACTIVE", LocalDateTime.now().minusSeconds(1));
        insertOption(1L, 1L, "A", 1);
        insertMarket(2L, "ACTIVE", LocalDateTime.now().minusSeconds(1));
        insertOption(2L, 2L, "B", 1);
        insertMarket(3L, "ACTIVE", LocalDateTime.now().plusDays(1));
        insertOption(3L, 3L, "C", 1);
        insertPredictionForMarket(100L, 1L, 1L, MEMBER_ID, "100.00", "0.50000000", "200.00000000",
                "CONFIRMED", PREDICTION_CREATED_AT, PREDICTION_CREATED_AT, null, null);
        insertPredictionForMarket(101L, 2L, 2L, MEMBER_ID, "100.00", "0.50000000", "200.00000000",
                "SETTLED", PREDICTION_CREATED_AT.plusSeconds(1), PREDICTION_CREATED_AT.plusSeconds(1), "100.00", null);
        insertPredictionForMarket(102L, 3L, 3L, MEMBER_ID, "100.00", "0.50000000", "200.00000000",
                "CONFIRMED", PREDICTION_CREATED_AT.plusSeconds(2), PREDICTION_CREATED_AT.plusSeconds(2), null, null);

        mockMvc.perform(get("/api/v1/markets/predictions/me")
                        .header("X-Member-Id", MEMBER_ID)
                        .param("marketDisplayStatus", "CLOSED_BY_TIME")
                        .param("predictionStatus", "CONFIRMED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].predictionId").value(100))
                .andExpect(jsonPath("$.data.content[0].marketDisplayStatus").value("CLOSED_BY_TIME"))
                .andExpect(jsonPath("$.data.content[0].predictionStatus").value("CONFIRMED"));
    }

    @Test
    void getMyPredictionsDoesNotReturnAnotherMembersPredictionWhenFiltered() throws Exception {
        insertMarket(1L, "ACTIVE", LocalDateTime.now().plusDays(1));
        insertOption(1L, 1L, "A", 1);
        insertPredictionForMarket(100L, 1L, 1L, 99L, "100.00", "0.50000000", "200.00000000",
                "CONFIRMED", PREDICTION_CREATED_AT, PREDICTION_CREATED_AT, null, null);

        mockMvc.perform(get("/api/v1/markets/predictions/me")
                        .header("X-Member-Id", MEMBER_ID)
                        .param("marketDisplayStatus", "ACTIVE")
                        .param("predictionStatus", "CONFIRMED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void getMyPredictionsRejectsInvalidFilterEnum() throws Exception {
        mockMvc.perform(get("/api/v1/markets/predictions/me")
                        .header("X-Member-Id", MEMBER_ID)
                        .param("marketDisplayStatus", "UNKNOWN_STATUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void getMyPredictionsRejectsInvalidPredictionStatusFilter() throws Exception {
        mockMvc.perform(get("/api/v1/markets/predictions/me")
                        .header("X-Member-Id", MEMBER_ID)
                        .param("predictionStatus", "UNKNOWN_STATUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    private void insertActiveMarketWithOptions() {
        insertMarket(MARKET_ID, "ACTIVE", LocalDateTime.now().plusDays(1));
        insertOptions(MARKET_ID);
    }

    private void insertMarket(long marketId, String status, LocalDateTime closeAt) {
        jdbcTemplate.update("""
                INSERT INTO market (
                    id, title, category, answer_type, judge_data_source, judge_criteria, judge_date,
                    status, close_at, total_pool, initial_virtual_liquidity, created_by, created_at, updated_at
                ) VALUES (?, 'Prediction Test Market', 'PRICE_INDEX', 'YES_NO', 'TEST', 'TEST',
                          ?, ?, ?, 0.00, 200.00, 1, ?, ?)
                """,
                marketId,
                LocalDate.now().plusDays(2),
                status,
                closeAt,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    private void insertOptions(long marketId) {
        insertOption(1L, marketId, "A", 1);
        insertOption(2L, marketId, "B", 2);
    }

    private void insertOption(long optionId, long marketId, String optionCode, int displayOrder) {
        jdbcTemplate.update("""
                INSERT INTO market_option (
                    id, market_id, option_code, option_text, display_order,
                    virtual_pool_amount, real_pool_amount, total_contract_quantity,
                    current_price, prediction_count, is_result, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 100.00, 0.00, 0.00000000, 0.50000000, 0, FALSE, ?, ?)
                """,
                optionId,
                marketId,
                optionCode,
                optionCode,
                displayOrder,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    private void insertPrediction(
            long predictionId,
            long memberId,
            String pointAmount,
            String priceSnapshot,
            String contractQuantity,
            String predictionStatus,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        jdbcTemplate.update("""
                INSERT INTO market_prediction (
                    id, market_id, option_id, member_id, point_amount, price_snapshot, contract_quantity,
                    status, point_spend_idempotency_key, created_at, updated_at
                ) VALUES (?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                predictionId,
                MARKET_ID,
                memberId,
                pointAmount,
                priceSnapshot,
                contractQuantity,
                predictionStatus,
                "MARKET_PREDICTION_SPEND:market:%d:member:%d:attempt:1".formatted(MARKET_ID, memberId),
                createdAt,
                updatedAt
        );
    }

    private void insertPredictionForMarket(
            long predictionId,
            long marketId,
            long optionId,
            long memberId,
            String pointAmount,
            String priceSnapshot,
            String contractQuantity,
            String predictionStatus,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            String settledAmount,
            String refundAmount
    ) {
        jdbcTemplate.update("""
                INSERT INTO market_prediction (
                    id, market_id, option_id, member_id, point_amount, price_snapshot, contract_quantity,
                    status, point_spend_idempotency_key, settled_amount, refund_amount, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                predictionId,
                marketId,
                optionId,
                memberId,
                pointAmount,
                priceSnapshot,
                contractQuantity,
                predictionStatus,
                "MARKET_PREDICTION_SPEND:market:%d:member:%d:attempt:1".formatted(marketId, memberId),
                settledAmount,
                refundAmount,
                createdAt,
                updatedAt
        );
    }

    private void expectMyPredictionNotFound(long memberId) throws Exception {
        mockMvc.perform(get("/api/v1/markets/{marketId}/predictions/me", MARKET_ID)
                        .header("X-Member-Id", memberId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("MARKET_PREDICTION_NOT_FOUND"));
    }

    private void assertPredictionStateWithoutPriceConfirmation(String predictionStatus, String failReason) {
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM market_prediction", String.class))
                .isEqualTo(predictionStatus);
        assertThat(jdbcTemplate.queryForObject("SELECT fail_reason FROM market_prediction", String.class))
                .isEqualTo(failReason);
        assertThat(jdbcTemplate.queryForObject("SELECT price_snapshot FROM market_prediction", String.class))
                .isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT contract_quantity FROM market_prediction", String.class))
                .isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT point_spend_idempotency_key FROM market_prediction",
                String.class
        )).isEqualTo(FIRST_ATTEMPT_KEY);
        assertThat(jdbcTemplate.queryForObject("SELECT attempt_no FROM market_prediction", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT total_pool FROM market", String.class)).isEqualTo("0.00");
        assertThat(jdbcTemplate.queryForList(
                "SELECT real_pool_amount FROM market_option ORDER BY id",
                String.class
        )).containsExactly("0.00", "0.00");
        assertThat(jdbcTemplate.queryForList(
                "SELECT total_contract_quantity FROM market_option ORDER BY id",
                String.class
        )).containsExactly("0.00000000", "0.00000000");
        assertThat(jdbcTemplate.queryForList(
                "SELECT current_price FROM market_option ORDER BY id",
                String.class
        )).containsExactly("0.50000000", "0.50000000");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM market_price_history", Integer.class))
                .isZero();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder predictionRequest(
            long marketId,
            long memberId,
            String idempotencyKey,
            long optionId,
            String pointAmount
    ) {
        return post("/api/v1/markets/{marketId}/predictions", marketId)
                .header("X-Member-Id", memberId)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(optionId, pointAmount));
    }

    private void expectPredictionError(
            long marketId,
            long memberId,
            String idempotencyKey,
            long optionId,
            String pointAmount,
            int statusCode,
            String errorCode
    ) throws Exception {
        mockMvc.perform(predictionRequest(marketId, memberId, idempotencyKey, optionId, pointAmount))
                .andExpect(status().is(statusCode))
                .andExpect(jsonPath("$.errorCode").value(errorCode));
    }

    private String requestBody(long optionId, String pointAmount) {
        return """
                {
                  "marketOptionId": %d,
                  "pointAmount": "%s"
                }
                """.formatted(optionId, pointAmount);
    }

}
