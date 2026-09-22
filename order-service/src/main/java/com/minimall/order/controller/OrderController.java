package com.minimall.order.controller;

import com.minimall.order.common.Result;
import com.minimall.order.dto.OrderRequest;
import com.minimall.order.dto.OrderResponse;
import com.minimall.order.model.Order;
import com.minimall.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /** 下单 */
    @PostMapping
    public Result<OrderResponse> createOrder(@Valid @RequestBody OrderRequest req) {
        return Result.success(orderService.createOrder(req));
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
