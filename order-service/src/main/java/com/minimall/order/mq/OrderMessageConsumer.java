package com.minimall.order.mq;

import com.minimall.order.config.RabbitConfig;
import com.minimall.order.dto.OrderMessage;
import com.minimall.order.mapper.OrderItemMapper;
import com.minimall.order.mapper.OrderLogMapper;
import com.minimall.order.mapper.OrderMapper;
import com.minimall.order.model.Order;
import com.minimall.order.model.OrderItem;
import com.minimall.order.model.OrderLog;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** MQ 消费者：异步处理下单消息（写 MySQL + 手动 ACK + 重试 + 死信） */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderMessageConsumer {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderLogMapper orderLogMapper;

    /** 内存记录每个订单号的重试次数（练习用；生产建议用消息头或外部存储） */
    private final ConcurrentHashMap<String, AtomicInteger> retryCounts = new ConcurrentHashMap<>();

    @RabbitListener(queues = RabbitConfig.QUEUE)
    @Transactional
    public void handleOrderCreated(OrderMessage message, Channel channel,
                                   @Header(AmqpHeaders.DELIVERY_TAG) long tag) throws IOException {
        String orderNo = message.getOrderNo();
        try {
            log.info("收到下单消息: orderNo={}, userId={}, amount={}",
                    orderNo,
                    message.getUserId(),
                    message.getTotalAmount());

            // 幂等：按 orderNo 查是否已落库
            Order existed = orderMapper.findByOrderNo(orderNo);
            if (existed != null) {
                log.warn("订单已存在，跳过重复消费: orderNo={}", orderNo);
                channel.basicAck(tag, false);
                retryCounts.remove(orderNo);
                return;
            }

            // 1. 写 t_order
            Order order = new Order();
            order.setOrderNo(orderNo);
            order.setUserId(message.getUserId());
            order.setTotalAmount(message.getTotalAmount());
            order.setStatus(0);  // 待支付
            orderMapper.insert(order);

            // 2. 写 t_order_item
            List<OrderItem> orderItems = new ArrayList<>();
            if (message.getItems() != null) {
                for (OrderMessage.OrderMessageItem msgItem : message.getItems()) {
                    OrderItem item = new OrderItem();
                    item.setOrderId(order.getId());
                    item.setProductId(msgItem.getProductId());
                    item.setProductName(msgItem.getProductName());
                    item.setPrice(msgItem.getPrice());
                    item.setQuantity(msgItem.getQuantity());
                    item.setSubTotal(msgItem.getSubTotal());
                    orderItems.add(item);
                }
                if (!orderItems.isEmpty()) {
                    orderItemMapper.batchInsert(orderItems);
                }
            }

            // 3. 写 t_order_log（CREATE 日志）
            OrderLog orderLog = new OrderLog();
            orderLog.setOrderId(order.getId());
            orderLog.setAction("CREATE");
            orderLog.setFromStatus(null);
            orderLog.setToStatus(0);
            orderLog.setRemark("异步下单落库");
            orderLogMapper.insert(orderLog);

            // 4. 处理成功 → 手动 ACK
            channel.basicAck(tag, false);
            log.info("异步下单落库成功并 ACK: orderNo={}", orderNo);

            // 成功后清理重试计数
            retryCounts.remove(orderNo);
        } catch (Exception e) {
            log.error("处理消息失败: orderNo={}, error={}", orderNo, e.getMessage(), e);

            int count = retryCounts.computeIfAbsent(orderNo, k -> new AtomicInteger(0))
                    .incrementAndGet();

            if (count >= 3) {
                // 重试 3 次后仍失败 → 不重回队列，进入死信队列
                log.warn("消息重试 {} 次后仍失败，进入死信队列: orderNo={}", count, orderNo);
                channel.basicNack(tag, false, false);
                retryCounts.remove(orderNo);
            } else {
                // 处理失败 → NACK 并重回队列重试
                log.warn("消息处理失败，第 {} 次重试: orderNo={}", count, orderNo);
                channel.basicNack(tag, false, true);
            }
        }
    }
}
