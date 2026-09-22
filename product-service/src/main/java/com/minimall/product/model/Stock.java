package com.minimall.product.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class Stock {
    private Long id;
    private Long productId;
    private Integer quantity;
    private Integer locked;
    private LocalDateTime updateTime;
}
