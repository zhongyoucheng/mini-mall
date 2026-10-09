package com.minimall.order.client;

import com.minimall.order.dto.FeignResult;
import com.minimall.order.dto.ProductDTO;
import com.minimall.order.dto.StockDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Feign 客户端：调用 product-service
 * name = "product-service" 必须与 Nacos 注册名一致
 * fallbackFactory = ProductClientFallback.class 提供降级逻辑
 */
@FeignClient(name = "product-service", fallbackFactory = ProductClientFallbackFactory.class)
public interface ProductClient {

    /** 查商品详情 */
    @GetMapping("/products/{id}")
    FeignResult<ProductDTO> getProduct(@PathVariable("id") Long id);

    /** 查库存 */
    @GetMapping("/products/{id}/stock")
    FeignResult<StockDTO> getStock(@PathVariable("id") Long id);

    /** 查全部库存（对账用） */
    @GetMapping("/products/stocks")
    FeignResult<List<StockDTO>> listStocks();
}

/**
 * 降级工厂：product-service 不可用时的兜底逻辑
 * 用 FallbackFactory 而非直接 fallback，可拿到异常原因
 */
@Slf4j
@Component
class ProductClientFallbackFactory implements FallbackFactory<ProductClient> {

    @Override
    public ProductClient create(Throwable cause) {
        log.warn("product-service 调用失败，触发降级: {}", cause.getMessage());
        return new ProductClient() {
            @Override
            public FeignResult<ProductDTO> getProduct(Long id) {
                return FeignResult.<ProductDTO>builder()
                        .code(503)
                        .message("商品服务不可用")
                        .data(null)
                        .build();
            }

            @Override
            public FeignResult<StockDTO> getStock(Long id) {
                return FeignResult.<StockDTO>builder()
                        .code(503)
                        .message("商品服务不可用")
                        .data(null)
                        .build();
            }

            @Override
            public FeignResult<List<StockDTO>> listStocks() {
                return FeignResult.<List<StockDTO>>builder()
                        .code(503)
                        .message("商品服务不可用")
                        .data(null)
                        .build();
            }
        };
    }
}
