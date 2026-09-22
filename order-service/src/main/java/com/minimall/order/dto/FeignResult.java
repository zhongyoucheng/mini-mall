package com.minimall.order.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 统一接收 product-service 返回的 Result 包装格式 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeignResult<T> {
    private Integer code;
    private String message;
    private T data;
}
