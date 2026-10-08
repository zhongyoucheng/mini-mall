package com.minimall.order.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/** MQ 消息体：异步下单消息（含订单基础信息 + 商品明细快照） */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderMessage {
    private Long orderId;
    private String orderNo;
    private Long userId;
    private BigDecimal totalAmount;
    private List<OrderMessageItem> items;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderMessageItem {
        private Long productId;
        private String productName;
        private BigDecimal price;
        private Integer quantity;
        private BigDecimal subTotal;
    }
}
