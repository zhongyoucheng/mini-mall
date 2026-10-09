package com.minimall.product.controller;

import com.minimall.product.common.Result;
import com.minimall.product.dto.ProductRequest;
import com.minimall.product.model.Product;
import com.minimall.product.model.Stock;
import com.minimall.product.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    /** 创建商品 */
    @PostMapping
    public Result<Long> create(@Valid @RequestBody ProductRequest req) {
        return Result.success(productService.createProduct(req));
    }

    /** 商品详情（走 Redis 缓存） */
    @GetMapping("/{id}")
    public Result<Product> getById(@PathVariable Long id) {
        Product product = productService.getProductById(id);
        return Result.success(product);
    }

    /** 商品列表 */
    @GetMapping
    public Result<List<Product>> list() {
        return Result.success(productService.listProducts());
    }

    /** 修改商品 */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody ProductRequest req) {
        productService.updateProduct(id, req);
        return Result.success(null);
    }

    /** 删除商品 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        productService.deleteProduct(id);
        return Result.success(null);
    }

    /** 查询库存（供 order-service Feign 调用） */
    @GetMapping("/{id}/stock")
    public Result<Stock> getStock(@PathVariable Long id) {
        return Result.success(productService.getStock(id));
    }

    /** 查询全部库存（供 order-service 对账调用） */
    @GetMapping("/stocks")
    public Result<List<Stock>> listStocks() {
        return Result.success(productService.listStocks());
    }
}
