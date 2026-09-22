package com.minimall.order.mq;

import com.minimall.order.config.RabbitConfig;
import com.minimall.order.dto.OrderMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** MQ 消费者：异步处理下单消息 */
@Slf4j
@Component
public class OrderMessageConsumer {

    @RabbitListener(queues = RabbitConfig.QUEUE)
    public void handleOrderCreated(OrderMessage message) {
        log.info("收到下单消息: orderId={}, orderNo={}, userId={}, amount={}",
                message.getOrderId(),
                message.getOrderNo(),
                message.getUserId(),
                message.getTotalAmount());
        // 实际项目：发短信通知、推送 APP、记审计日志等
        // 这里只打印日志
    }
}
