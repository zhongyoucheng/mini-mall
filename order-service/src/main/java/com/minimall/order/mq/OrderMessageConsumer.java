package com.minimall.order.mq;

import com.minimall.order.config.RabbitConfig;
import com.minimall.order.dto.OrderMessage;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** MQ 消费者：异步处理下单消息（手动 ACK + 重试 + 死信） */
@Slf4j
@Component
public class OrderMessageConsumer {

    /** 内存记录每个订单号的重试次数（练习用；生产建议用消息头或外部存储） */
    private final ConcurrentHashMap<String, AtomicInteger> retryCounts = new ConcurrentHashMap<>();

    @RabbitListener(queues = RabbitConfig.QUEUE)
    public void handleOrderCreated(OrderMessage message, Channel channel,
                                   @Header(AmqpHeaders.DELIVERY_TAG) long tag) throws IOException {
        String orderNo = message.getOrderNo();
        try {
            log.info("收到下单消息: orderId={}, orderNo={}, userId={}, amount={}",
                    message.getOrderId(),
                    orderNo,
                    message.getUserId(),
                    message.getTotalAmount());

            // 实际项目：发短信通知、推送 APP、记审计日志等
            // 这里只打印日志

            // 处理成功 → 手动 ACK
            channel.basicAck(tag, false);
            log.info("下单消息处理成功并 ACK: orderNo={}", orderNo);

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
