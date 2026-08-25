package com.todongsan.apigateway.global.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Gateway 보안 필터 — JwtAuthenticationFilter보다 먼저 실행 (order = -10).
 *
 * 1. /internal/** 요청을 명시적으로 차단 (403).
 *    내부 API는 서비스 간 직접 호출 전용이며, Gateway를 통해 외부에서 접근할 수 없어야 한다.
 *
 * 2. 모든 외부 요청에서 X-Member-Id, X-Member-Role 헤더를 제거한다.
 *    이 헤더는 Gateway가 JWT 검증 후 설정하는 것이므로,
 *    클라이언트가 직접 보내는 값은 신뢰할 수 없다.
 *    공개 경로(JWT 검증 없이 통과하는 경로)에서도 제거해야
 *    다운스트림 서비스가 인증된 사용자로 오인하는 것을 방지한다.
 */
@Slf4j
@Component
public class SecurityHeaderFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 1. /internal/** 외부 접근 차단
        if (path.startsWith("/internal/") || path.equals("/internal")) {
            log.warn("External access to internal API blocked: {} {}", request.getMethod(), path);
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return exchange.getResponse().setComplete();
        }

        // 2. 외부 요청의 X-Member-Id, X-Member-Role 제거 (위조 방어)
        //    JwtAuthenticationFilter가 JWT 검증 후 올바른 값으로 다시 설정한다.
        ServerHttpRequest sanitized = request.mutate()
                .headers(headers -> {
                    headers.remove("X-Member-Id");
                    headers.remove("X-Member-Role");
                })
                .build();

        return chain.filter(exchange.mutate().request(sanitized).build());
    }

    @Override
    public int getOrder() {
        return -10; // JwtAuthenticationFilter(-1)보다 먼저 실행
    }
}
