package com.minimall.order.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Configuration;

/** RabbitMQ 生产者确认与路由失败回调配置 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RabbitConfirmConfig {

    private final RabbitTemplate rabbitTemplate;

    @PostConstruct
    public void init() {
        // 路由失败时触发 Return 回调（而非静默丢弃）
        rabbitTemplate.setMandatory(true);

        // Confirm 回调：消息是否成功到达 Exchange
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (ack) {
                log.info("消息成功到达 Exchange, correlationData={}", correlationData);
            } else {
                log.error("消息未到达 Exchange! correlationData={}, cause={}", correlationData, cause);
                // 生产环境：重发 / 记日志 / 告警
            }
        });

        // Return 回调：消息到达 Exchange 但无匹配 Queue 时被退回
        rabbitTemplate.setReturnsCallback(returned -> {
            log.error("消息路由失败! exchange={}, routingKey={}, replyCode={}, replyText={}",
                    returned.getExchange(),
                    returned.getRoutingKey(),
                    returned.getReplyCode(),
                    returned.getReplyText());
            // 生产环境：重发到正确队列 / 记日志
        });
    }
}
