package com.minimall.product.service;

import com.minimall.product.common.BusinessException;
import com.minimall.product.dto.ProductRequest;
import com.minimall.product.mapper.ProductMapper;
import com.minimall.product.mapper.StockMapper;
import com.minimall.product.model.Product;
import com.minimall.product.model.Stock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductMapper productMapper;
    private final StockMapper stockMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String CACHE_KEY_PREFIX = "product:";
    private static final long CACHE_TTL_MINUTES = 30;
    private static final long NULL_CACHE_TTL_MINUTES = 5;

    /** 创建商品 */
    public Long createProduct(ProductRequest req) {
        Product product = new Product();
        product.setName(req.getName());
        product.setDescription(req.getDescription());
        product.setPrice(req.getPrice());
        product.setCategory(req.getCategory());
        product.setStatus(req.getStatus() != null ? req.getStatus() : 1);
        productMapper.insert(product);

        // 同时初始化库存记录
        Stock stock = new Stock();
        stock.setProductId(product.getId());
        stock.setQuantity(req.getQuantity() != null ? req.getQuantity() : 0);
        stock.setLocked(0);
        stockMapper.insert(stock);

        return product.getId();
    }

    /** 查询商品详情（Cache Aside 读流程） */
    public Product getProductById(Long id) {
        String key = CACHE_KEY_PREFIX + id;

        // 1. 查 Redis
        Object cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            // 命中缓存（含空值缓存 ""）
            if ("".equals(cached)) {
                return null;  // 空值缓存，防穿透
            }
            log.info("缓存命中: {}", key);
            return (Product) cached;
        }

        // 2. 未命中 → 查 MySQL
        log.info("缓存未命中: {}, 查询 MySQL", key);
        Product product = productMapper.findById(id);

        if (product == null) {
            // 3. 空值缓存防穿透（短 TTL）
            redisTemplate.opsForValue().set(key, "", NULL_CACHE_TTL_MINUTES, TimeUnit.MINUTES);
            return null;
        }

        // 4. 写入 Redis（正常 TTL）
        redisTemplate.opsForValue().set(key, product, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
        return product;
    }

    /** 商品列表 */
    public List<Product> listProducts() {
        return productMapper.findAll();
    }

    /** 修改商品（Cache Aside 写流程：先更新 DB 再删缓存） */
    public void updateProduct(Long id, ProductRequest req) {
        Product product = productMapper.findById(id);
        if (product == null) {
            throw new BusinessException(404, "商品不存在");
        }
        product.setName(req.getName());
        product.setDescription(req.getDescription());
        product.setPrice(req.getPrice());
        product.setCategory(req.getCategory());
        product.setStatus(req.getStatus());
        productMapper.update(product);

        // 先更新 DB 再删缓存（不是更新缓存）
        redisTemplate.delete(CACHE_KEY_PREFIX + id);
    }

    /** 删除商品 */
    @Transactional
    public void deleteProduct(Long id) {
        Product product = productMapper.findById(id);
        if (product == null) {
            throw new BusinessException(404, "商品不存在");
        }
        productMapper.deleteById(id);
        stockMapper.deleteByProductId(id);
        redisTemplate.delete(CACHE_KEY_PREFIX + id);
    }

    /** 查询库存（供 Day 5 order-service Feign 调用） */
    public Stock getStock(Long productId) {
        return stockMapper.findByProductId(productId);
    }
}
