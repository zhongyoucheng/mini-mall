package com.minimall.product.runner;

import com.minimall.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 服务启动完成后自动预热库存
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockWarmupRunner implements ApplicationRunner {

    private final ProductService productService;

    @Override
    public void run(ApplicationArguments args) {
        log.info("服务启动完成，开始预热库存...");
        productService.warmupAllStocks();
    }
}
