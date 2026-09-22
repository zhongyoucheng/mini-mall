package com.minimall.order.task;

import com.minimall.order.mapper.OrderMapper;
import com.minimall.order.model.Order;
import com.minimall.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 定时任务：扫描超时未支付订单，自动取消
 * 定时任务直接调 Mapper 查询，调 Service 执行状态变更（Service 有事务保护）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutTask {

    private final OrderMapper orderMapper;
    private final OrderService orderService;

    /** 超时阈值：15 分钟 */
    private static final int TIMEOUT_MINUTES = 15;

    /**
     * 每 60 秒扫描一次（fixedDelay：上次跑完等 60 秒再跑）
     */
    @Scheduled(fixedDelay = 60000)
    public void cancelTimeoutOrders() {
        // 1. 直接调 Mapper 查询超时订单（绕过 Service 副作用）
        List<Order> timeoutOrders = orderMapper.findTimeoutOrders(TIMEOUT_MINUTES);
        if (timeoutOrders.isEmpty()) {
            return;
        }

        log.info("发现 {} 笔超时订单，开始自动取消", timeoutOrders.size());

        // 2. 逐条取消（调 Service，有事务保护）
        for (Order order : timeoutOrders) {
            try {
                orderService.timeoutCancelOrder(order);
                log.info("超时取消成功: orderNo={}", order.getOrderNo());
            } catch (Exception e) {
                log.error("超时取消失败: orderNo={}, error={}", order.getOrderNo(), e.getMessage());
            }
        }
    }
}
