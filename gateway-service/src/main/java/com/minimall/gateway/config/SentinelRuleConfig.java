package com.minimall.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

@Slf4j
@Component
public class SentinelRuleConfig {

    @PostConstruct
    public void initRules() {
        Set<GatewayFlowRule> rules = new HashSet<>();

        // 对 order-service 路由限流：QPS=2（方便测试）
        GatewayFlowRule orderRule = new GatewayFlowRule("order-service-route");
        orderRule.setCount(2);
        orderRule.setIntervalSec(1);
        rules.add(orderRule);

        GatewayRuleManager.loadRules(rules);
        log.info("Sentinel 网关限流规则已加载: order-service-route QPS=2");
    }
}
