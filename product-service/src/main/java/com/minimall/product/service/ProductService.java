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
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductMapper productMapper;
    private final StockMapper stockMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String CACHE_KEY_PREFIX = "product:";
    private static final String STOCK_KEY_PREFIX = "stock:";
    private static final long CACHE_TTL_BASE_MINUTES = 25;
    private static final long CACHE_TTL_RANDOM_MINUTES = 10;
    private static final long NULL_CACHE_TTL_MINUTES = 5;
    private static final long LOCK_TTL_SECONDS = 5;
    private static final long LOCK_WAIT_MILLIS = 50;
    private static final int MAX_RETRY = 3;

    private static final String LOCK_RELEASE_LUA =
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end";

    private static final String DEDUCT_STOCK_LUA =
            "local s = tonumber(redis.call('GET', KEYS[1])) " +
            "local c = tonumber(ARGV[1]) " +
            "if s and s >= c then " +
            "    redis.call('DECRBY', KEYS[1], c) " +
            "    return s - c " +
            "else " +
            "    return -1 " +
            "end";

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

    /** 查询商品详情（Cache Aside 读流程，带互斥锁防击穿 + 随机 TTL 防雪崩） */
    public Product getProductById(Long id) {
        return getProductByIdWithRetry(id, 0);
    }

    private Product getProductByIdWithRetry(Long id, int retry) {
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

        // 2. 未命中 → 抢互斥锁（SET NX EX）
        String lockKey = "lock:" + key;
        String lockOwner = UUID.randomUUID().toString();
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockOwner, LOCK_TTL_SECONDS, TimeUnit.SECONDS);

        try {
            if (Boolean.TRUE.equals(locked)) {
                // 3. 抢到锁 → 再查一次缓存（double check）
                cached = redisTemplate.opsForValue().get(key);
                if (cached != null) {
                    if ("".equals(cached)) {
                        return null;
                    }
                    return (Product) cached;
                }

                log.info("缓存未命中: {}, 查询 MySQL", key);
                Product product = productMapper.findById(id);

                if (product == null) {
                    // 4. 空值缓存防穿透（短 TTL，固定不变）
                    redisTemplate.opsForValue().set(key, "", NULL_CACHE_TTL_MINUTES, TimeUnit.MINUTES);
                    return null;
                }

                // 5. 写入 Redis（随机 TTL 防雪崩：25~35 分钟）
                long ttl = CACHE_TTL_BASE_MINUTES + ThreadLocalRandom.current().nextLong(CACHE_TTL_RANDOM_MINUTES);
                redisTemplate.opsForValue().set(key, product, ttl, TimeUnit.MINUTES);
                return product;
            } else {
                // 6. 没抢到锁 → 等待后重试
                if (retry >= MAX_RETRY) {
                    log.warn("获取缓存锁超过最大重试次数: {}, 降级直接查询 MySQL", key);
                    return productMapper.findById(id);
                }
                try {
                    Thread.sleep(LOCK_WAIT_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return getProductByIdWithRetry(id, retry + 1);
            }
        } finally {
            // 7. Lua 校验 owner 释放锁
            releaseLock(lockKey, lockOwner);
        }
    }

    private void releaseLock(String lockKey, String owner) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(LOCK_RELEASE_LUA, Long.class);
        redisTemplate.execute(script, List.of(lockKey), owner);
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

    /** Redis Lua 预扣库存（默认扣 1） */
    public boolean deductStockRedis(Long productId) {
        return deductStockRedis(productId, 1);
    }

    /** Redis Lua 预扣库存（支持一次扣 count 个） */
    public boolean deductStockRedis(Long productId, int count) {
        String key = STOCK_KEY_PREFIX + productId;
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(DEDUCT_STOCK_LUA, Long.class);
        Long result = redisTemplate.execute(script, List.of(key), String.valueOf(count));
        return result != null && result >= 0;
    }

    /** 库存预热：把 MySQL 库存同步到 Redis（启动时/活动前调用） */
    public void warmupStock(Long productId) {
        Stock stock = stockMapper.findByProductId(productId);
        if (stock == null) {
            throw new BusinessException(404, "库存记录不存在");
        }
        redisTemplate.opsForValue().set(STOCK_KEY_PREFIX + productId, stock.getQuantity());
    }

    /** 库存回滚：下游 MySQL 落库失败时，Redis INCR 补偿 */
    public void restoreStock(Long productId, int count) {
        String key = STOCK_KEY_PREFIX + productId;
        redisTemplate.opsForValue().increment(key, count);
    }
}
