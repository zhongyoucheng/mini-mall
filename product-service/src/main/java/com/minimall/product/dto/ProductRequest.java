package com.minimall.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ProductRequest {

    @NotBlank(message = "商品名称不能为空")
    private String name;

    private String description;

    @NotNull(message = "价格不能为空")
    @Positive(message = "价格必须大于 0")
    private BigDecimal price;

    private String category;

    private Integer status;  // 1上架 0下架，可不传默认上架

    private Integer quantity;  // 库存数量（创建商品时同时设库存）
}
