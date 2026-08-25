package com.todongsan.apigateway.global.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Rate Limiter Key Resolver.
 *
 * IP 기반으로 요청을 제한한다.
 * - replenishRate: 20 (초당 20건 허용)
 * - burstCapacity: 40 (순간 최대 40건)
 *
 * Redis의 Token Bucket 알고리즘으로 동작.
 * 프로덕션에서는 사용자별(memberId) 제한도 추가 가능.
 */
@Configuration
public class RateLimiterConfig {

    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> Mono.just(
                exchange.getRequest().getRemoteAddress() != null
                        ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                        : "unknown"
        );
    }
}
