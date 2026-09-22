package com.minimall.order.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String EXCHANGE = "order.exchange";
    public static final String QUEUE = "order.created.queue";
    public static final String ROUTING_KEY = "order.created";

    /** 交换机（DirectExchange 按路由键精准匹配） */
    @Bean
    public DirectExchange orderExchange() {
        return new DirectExchange(EXCHANGE, true, false);  // durable=true 持久化
    }

    /** 队列（durable=true 持久化，RabbitMQ 重启后不丢） */
    @Bean
    public Queue orderCreatedQueue() {
        return new Queue(QUEUE, true);
    }

    /** 绑定（把队列绑定到交换机，指定路由键） */
    @Bean
    public Binding orderBinding() {
        return BindingBuilder.bind(orderCreatedQueue())
                .to(orderExchange())
                .with(ROUTING_KEY);
    }

    /** JSON 消息转换器（默认 SimpleMessageConverter 只支持 Serializable） */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
