package com.todongsan.apigateway.global.fallback;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Circuit Breaker fallback 컨트롤러.
 *
 * 다운스트림 서비스가 장애이거나 응답 지연(5초 초과)일 때,
 * Circuit Breaker가 요청을 여기로 리다이렉트하여 503을 반환한다.
 *
 * 왜 필요한가:
 * - Circuit Breaker 없으면 장애 서비스에 계속 요청 → 커넥션 고갈 → 연쇄 장애
 * - OPEN 상태에서 빠르게 503 반환 → 클라이언트가 즉시 재시도 판단 가능
 * - 30초 후 HALF_OPEN → 3건 시도 → 복구되면 CLOSED로 전환
 */
@RestController
@RequestMapping("/fallback")
public class FallbackController {

    @GetMapping("/unavailable")
    public Mono<Map<String, Object>> unavailable() {
        return Mono.just(Map.of(
                "success", false,
                "errorCode", "SERVICE_UNAVAILABLE",
                "message", "서비스가 일시적으로 불가합니다. 잠시 후 다시 시도해주세요.",
                "timestamp", LocalDateTime.now().toString()
        ));
    }
}
