package com.minimall.order.controller;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.minimall.order.common.Result;
import com.minimall.order.dto.OrderRequest;
import com.minimall.order.dto.OrderResponse;
import com.minimall.order.model.Order;
import com.minimall.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
@Slf4j
public class OrderController {

    private final OrderService orderService;

    /** 下单 */
    @PostMapping
    public Result<OrderResponse> createOrder(@Valid @RequestBody OrderRequest req) {
        return Result.success(orderService.createOrder(req));
    }

    /** 异步下单（Redis 预扣 + MQ 削峰），受 Sentinel 入口限流保护 */
    @PostMapping("/async")
    @SentinelResource(value = "seckill", blockHandler = "seckillBlocked")
    public Result<OrderResponse> asyncCreateOrder(@Valid @RequestBody OrderRequest req) {
        return Result.success(orderService.asyncCreateOrder(req));
    }

    /** 限流后的降级方法（签名必须与原方法一致，并多一个 BlockException 参数） */
    public Result<OrderResponse> seckillBlocked(@Valid @RequestBody OrderRequest req, BlockException e) {
        log.warn("[Sentinel] 秒杀接口触发限流: rule={}", e.getRule());
        return Result.error(429, "系统繁忙，请稍后再试");
    }

    /** 订单详情（含明细） */
    @GetMapping("/{id}")
    public Result<OrderResponse> getById(@PathVariable Long id) {
        return Result.success(orderService.getOrderById(id));
    }

    /** 用户订单列表 */
    @GetMapping("/user/{userId}")
    public Result<List<Order>> listByUser(@PathVariable Long userId) {
        return Result.success(orderService.listByUserId(userId));
    }

    /** 支付订单 */
    @PutMapping("/{id}/pay")
    public Result<Void> pay(@PathVariable Long id) {
        orderService.payOrder(id);
        return Result.success(null);
    }

    /** 取消订单 */
    @PutMapping("/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id) {
        orderService.cancelOrder(id);
        return Result.success(null);
    }
}
