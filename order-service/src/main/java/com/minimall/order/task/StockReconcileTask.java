package com.minimall.order.task;

import com.minimall.order.client.ProductClient;
import com.minimall.order.dto.FeignResult;
import com.minimall.order.dto.StockDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 定时对账：检查 Redis 缓存库存与 MySQL 真实库存的一致性
 * 通过 Feign 从 product-service 拉取全量库存（真相在 MySQL）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockReconcileTask {

    private final ProductClient productClient;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String STOCK_KEY_PREFIX = "stock:";

    /** 每天凌晨 2 点对账一次 */
    @Scheduled(cron = "0 0 2 * * ?")
    public void reconcile() {
        log.info("[对账] 开始检查 Redis 与 MySQL 库存一致性...");

        FeignResult<List<StockDTO>> result = productClient.listStocks();
        if (result == null || result.getData() == null || result.getCode() != 200) {
            log.error("[对账] 从 product-service 拉取库存失败: {}", result == null ? "null" : result.getMessage());
            return;
        }

        List<StockDTO> stocks = result.getData();
        for (StockDTO stock : stocks) {
            String key = STOCK_KEY_PREFIX + stock.getProductId();
            try {
                Object cached = redisTemplate.opsForValue().get(key);

                // 情况1：Redis 没有该 key（丢了）→ 用 MySQL 覆盖
                if (cached == null) {
                    log.warn("[对账] Redis 缺少库存 key: {}，用 MySQL 库存 {} 覆盖", key, stock.getQuantity());
                    redisTemplate.opsForValue().set(key, stock.getQuantity());
                    continue;
                }

                int redisValue;
                try {
                    redisValue = Integer.parseInt(cached.toString());
                } catch (NumberFormatException e) {
                    log.error("[对账] Redis 库存值非数字: {}={}，用 MySQL 库存 {} 覆盖", key, cached, stock.getQuantity());
                    redisTemplate.opsForValue().set(key, stock.getQuantity());
                    continue;
                }

                // 情况2：Redis 值为负（理论不应出现，Lua 已防超卖）→ 覆盖
                if (redisValue < 0) {
                    log.error("[对账] Redis 库存为负: {}={}，用 MySQL 库存 {} 覆盖", key, redisValue, stock.getQuantity());
                    redisTemplate.opsForValue().set(key, stock.getQuantity());
                    continue;
                }

                // 情况3：Redis 与 MySQL 差异过大（超过 50%）→ 告警并覆盖
                int mysqlQuantity = stock.getQuantity();
                if (mysqlQuantity > 0 && Math.abs(redisValue - mysqlQuantity) > mysqlQuantity * 0.5) {
                    log.warn("[对账] 库存差异过大: {} Redis={} MySQL={}，用 MySQL 覆盖", key, redisValue, mysqlQuantity);
                    redisTemplate.opsForValue().set(key, mysqlQuantity);
                    continue;
                }

                log.debug("[对账] 库存一致: {} Redis={} MySQL={}", key, redisValue, mysqlQuantity);
            } catch (Exception e) {
                log.error("[对账] 处理商品 {} 时发生异常: {}", stock.getProductId(), e.getMessage(), e);
            }
        }
        log.info("[对账] 检查完成");
    }
}
