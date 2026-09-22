package com.minimall.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.BlockRequestHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.ServerResponse;

@Configuration
public class GatewayConfig {

    /**
     * 自定义限流响应：被限流时返回 JSON 而非默认错误页
     */
    @Bean
    public BlockRequestHandler blockRequestHandler() {
        return (exchange, ex) -> ServerResponse
                .status(429)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(
                        "{\"code\":429,\"message\":\"请求过于频繁，请稍后再试\",\"data\":null}"));
    }
}
