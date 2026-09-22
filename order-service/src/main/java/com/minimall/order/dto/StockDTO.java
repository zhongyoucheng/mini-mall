package com.minimall.order.dto;

import lombok.Data;

/** Feign 调 product-service 返回的库存信息 */
@Data
public class StockDTO {
    private Long productId;
    private Integer quantity;
    private Integer locked;
}
