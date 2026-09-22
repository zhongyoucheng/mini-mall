package com.minimall.order.dto;

import lombok.Data;
import java.math.BigDecimal;

/** Feign 调 product-service 返回的商品信息（本地定义，不依赖 product-service 的类） */
@Data
public class ProductDTO {
    private Long id;
    private String name;
    private String description;
    private BigDecimal price;
    private String category;
    private Integer status;
}
