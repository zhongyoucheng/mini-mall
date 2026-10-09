package com.minimall.order.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Sentinel 限流规则配置（代码版，无需控制台）
 */
@Configuration
@Slf4j
public class SentinelRuleConfig {

    @PostConstruct
    public void initFlowRules() {
        List<FlowRule> rules = new ArrayList<>();

        // 秒杀异步下单接口：QPS 限制为 100（练习时设低便于压测触发）
        FlowRule seckillRule = new FlowRule();
        seckillRule.setResource("seckill");
        seckillRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        seckillRule.setCount(100);
        seckillRule.setLimitApp("default");
        rules.add(seckillRule);

        FlowRuleManager.loadRules(rules);
        log.info("[Sentinel] 秒杀接口限流规则已加载，资源名=seckill，QPS=100");
    }
}
