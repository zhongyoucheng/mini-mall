package com.minimall.order.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** MQ 消息体：下单成功后发送 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderMessage {
    private Long orderId;
    private String orderNo;
    private Long userId;
    private BigDecimal totalAmount;
}
