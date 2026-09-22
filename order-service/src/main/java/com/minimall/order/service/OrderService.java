package com.minimall.order.service;

import com.minimall.order.client.ProductClient;
import com.minimall.order.common.BusinessException;
import com.minimall.order.config.RabbitConfig;
import com.minimall.order.dto.*;
import com.minimall.order.mapper.*;
import com.minimall.order.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderLogMapper orderLogMapper;
    private final ProductClient productClient;
    private final RabbitTemplate rabbitTemplate;

    /**
     * 下单
     */
    @Transactional
    public OrderResponse createOrder(OrderRequest req) {
        // 1. 遍历商品，Feign 调 product-service 查商品详情 + 库存
        List<OrderItem> orderItems = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;

        for (OrderRequest.OrderItemRequest itemReq : req.getItems()) {
            // 查商品
            FeignResult<ProductDTO> productResult = productClient.getProduct(itemReq.getProductId());
            if (productResult.getCode() != 200 || productResult.getData() == null) {
                throw new BusinessException(400, "商品不存在或商品服务不可用: " + itemReq.getProductId());
            }
            ProductDTO product = productResult.getData();

            // 查库存
            FeignResult<StockDTO> stockResult = productClient.getStock(itemReq.getProductId());
            if (stockResult.getCode() != 200 || stockResult.getData() == null) {
                throw new BusinessException(400, "库存查询失败: " + itemReq.getProductId());
            }
            StockDTO stock = stockResult.getData();

            // 校验库存
            int available = stock.getQuantity() - stock.getLocked();
            if (available < itemReq.getQuantity()) {
                throw new BusinessException(400, "库存不足: " + product.getName());
            }

            // 构造订单明细（商品名/价格快照）
            BigDecimal subTotal = product.getPrice().multiply(BigDecimal.valueOf(itemReq.getQuantity()));
            OrderItem item = new OrderItem();
            item.setProductId(product.getId());
            item.setProductName(product.getName());  // 快照
            item.setPrice(product.getPrice());        // 快照
            item.setQuantity(itemReq.getQuantity());
            item.setSubTotal(subTotal);
            orderItems.add(item);

            totalAmount = totalAmount.add(subTotal);
        }

        // 2. 生成订单号 + 写入 t_order
        String orderNo = generateOrderNo();
        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(req.getUserId());
        order.setTotalAmount(totalAmount);
        order.setStatus(0);  // 待支付
        orderMapper.insert(order);
        // 重新查询以获取 create_time/update_time
        order = orderMapper.findById(order.getId());

        // 3. 写入 t_order_item（批量）
        for (OrderItem item : orderItems) {
            item.setOrderId(order.getId());
        }
        orderItemMapper.batchInsert(orderItems);

        // 4. 写入 t_order_log（CREATE 日志）
        OrderLog orderLog = new OrderLog();
        orderLog.setOrderId(order.getId());
        orderLog.setAction("CREATE");
        orderLog.setFromStatus(null);
        orderLog.setToStatus(0);
        orderLog.setRemark("用户下单");
        orderLogMapper.insert(orderLog);

        // 5. 发 RabbitMQ 消息（异步通知）
        OrderMessage message = new OrderMessage(
                order.getId(),
                order.getOrderNo(),
                order.getUserId(),
                order.getTotalAmount());
        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.ROUTING_KEY, message);
        log.info("下单成功，已发送 MQ 消息: orderNo={}", orderNo);

        // 6. 构造响应
        OrderResponse resp = new OrderResponse();
        resp.setId(order.getId());
        resp.setOrderNo(order.getOrderNo());
        resp.setUserId(order.getUserId());
        resp.setTotalAmount(order.getTotalAmount());
        resp.setStatus(order.getStatus());
        resp.setCreateTime(order.getCreateTime());
        List<OrderResponse.OrderItem> respItems = new ArrayList<>();
        for (OrderItem item : orderItems) {
            OrderResponse.OrderItem ri = new OrderResponse.OrderItem();
            ri.setProductId(item.getProductId());
            ri.setProductName(item.getProductName());
            ri.setPrice(item.getPrice());
            ri.setQuantity(item.getQuantity());
            ri.setSubTotal(item.getSubTotal());
            respItems.add(ri);
        }
        resp.setItems(respItems);
        return resp;
    }

    /**
     * 查询订单详情（含明细）
     */
    public OrderResponse getOrderById(Long id) {
        Order order = orderMapper.findById(id);
        if (order == null) {
            throw new BusinessException(404, "订单不存在");
        }
        List<OrderItem> items = orderItemMapper.findByOrderId(id);
        OrderResponse resp = new OrderResponse();
        resp.setId(order.getId());
        resp.setOrderNo(order.getOrderNo());
        resp.setUserId(order.getUserId());
        resp.setTotalAmount(order.getTotalAmount());
        resp.setStatus(order.getStatus());
        resp.setCreateTime(order.getCreateTime());
        List<OrderResponse.OrderItem> respItems = new ArrayList<>();
        for (OrderItem item : items) {
            OrderResponse.OrderItem ri = new OrderResponse.OrderItem();
            ri.setProductId(item.getProductId());
            ri.setProductName(item.getProductName());
            ri.setPrice(item.getPrice());
            ri.setQuantity(item.getQuantity());
            ri.setSubTotal(item.getSubTotal());
            respItems.add(ri);
        }
        resp.setItems(respItems);
        return resp;
    }

    /**
     * 查询用户订单列表
     */
    public List<Order> listByUserId(Long userId) {
        return orderMapper.findByUserId(userId);
    }

    /**
     * 生成订单号：日期 + UUID 片段
     */
    private String generateOrderNo() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    /**
     * 支付订单（状态机：0→1）
     */
    @Transactional
    public void payOrder(Long id) {
        Order order = orderMapper.findById(id);
        if (order == null) {
            throw new BusinessException(404, "订单不存在");
        }
        if (order.getStatus() != 0) {
            throw new BusinessException(400, "订单状态不允许支付，当前状态: " + statusName(order.getStatus()));
        }
        if (orderMapper.updateStatusWhen(id, 1, 0) == 0) {
            throw new BusinessException(409, "订单状态已变更，请刷新后重试");
        }

        OrderLog log = new OrderLog();
        log.setOrderId(id);
        log.setAction("PAY");
        log.setFromStatus(0);
        log.setToStatus(1);
        log.setRemark("用户支付");
        orderLogMapper.insert(log);
    }

    /**
     * 取消订单（状态机：0→2）
     */
    @Transactional
    public void cancelOrder(Long id) {
        Order order = orderMapper.findById(id);
        if (order == null) {
            throw new BusinessException(404, "订单不存在");
        }
        if (order.getStatus() != 0) {
            throw new BusinessException(400, "订单状态不允许取消，当前状态: " + statusName(order.getStatus()));
        }
        if (orderMapper.updateStatusWhen(id, 2, 0) == 0) {
            throw new BusinessException(409, "订单状态已变更，请刷新后重试");
        }

        OrderLog log = new OrderLog();
        log.setOrderId(id);
        log.setAction("CANCEL");
        log.setFromStatus(0);
        log.setToStatus(2);
        log.setRemark("用户取消");
        orderLogMapper.insert(log);
    }

    /**
     * 超时取消（状态机：0→3），由定时任务调用
     */
    @Transactional
    public void timeoutCancelOrder(Order order) {
        Order freshOrder = orderMapper.findById(order.getId());
        if (freshOrder == null || freshOrder.getStatus() != 0) {
            log.warn("超时取消跳过: orderNo={}, 当前状态={}", order.getOrderNo(),
                    freshOrder == null ? "订单不存在" : statusName(freshOrder.getStatus()));
            return;
        }
        if (orderMapper.updateStatusWhen(order.getId(), 3, 0) == 0) {
            log.warn("超时取消并发冲突: orderNo={}", order.getOrderNo());
            return;
        }

        OrderLog log = new OrderLog();
        log.setOrderId(order.getId());
        log.setAction("TIMEOUT");
        log.setFromStatus(0);
        log.setToStatus(3);
        log.setRemark("超时自动取消");
        orderLogMapper.insert(log);

        // 发 MQ 通知
        OrderMessage message = new OrderMessage(
                order.getId(), order.getOrderNo(), order.getUserId(), order.getTotalAmount());
        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.ROUTING_KEY, message);
    }

    /** 状态码转中文名 */
    private String statusName(Integer status) {
        return switch (status) {
            case 0 -> "待支付";
            case 1 -> "已支付";
            case 2 -> "已取消";
            case 3 -> "超时取消";
            default -> "未知";
        };
    }
}
