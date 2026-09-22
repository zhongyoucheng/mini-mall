---
AIGC:
  ContentProducer: '001191110102MAD55U9H0F10002'
  ContentPropagator: '001191110102MAD55U9H0F10002'
  Label: '1'
  ProduceID: '1a4c2ce7-b7ed-419b-9fda-d9ed30144c24'
  PropagateID: '1a4c2ce7-b7ed-419b-9fda-d9ed30144c24'
  ReservedCode1: 'f49674a9-1c27-4bfd-916a-85262ad8c702'
  ReservedCode2: 'f49674a9-1c27-4bfd-916a-85262ad8c702'
---

# 阶段九 Day 4：商品服务（CRUD + Redis 缓存）

> mini-mall 电商订单系统 | 教学文档 v1.0 | 2026-09-16

---

## 一、今日目标

完成 product-service 从"测试 Controller"到"正式业务服务"的升级：
1. **商品 CRUD**：增/删/改/查（分页查询、详情）
2. **Redis 缓存商品详情**：Cache Aside 旁路缓存模式
3. **缓存一致性**：修改/删除商品后删缓存
4. **库存查询接口**：供 Day 5 order-service 通过 Feign 调用

---

## 二、概念讲解

### 2.1 Cache Aside 旁路缓存模式

电商商品详情页访问频率高但修改频率低，是 Redis 缓存的经典场景。

**读流程**：
```
请求 → 查 Redis（key: product:{id}）
        ├─ HIT（命中）→ 直接返回，不查 MySQL
        └─ MISS（未命中）→ 查 MySQL → 结果写入 Redis（TTL 30分钟）→ 返回
```

**写流程**（修改/删除商品时）：
```
请求 → 更新 MySQL → 删除 Redis 缓存（不是更新缓存）
```

**为什么删缓存而不是更新缓存？**
- 更新缓存：并发写时两个线程同时更新可能导致缓存存入旧数据（先写的反而后存）
- 删缓存：下次读取时自然从 MySQL 重新加载最新数据，简单可靠

类比：Redis 是书桌上的便利贴（快但容易丢/过期），MySQL 是文件柜（慢但持久）。改了文件柜里的文件后撕掉旧便利贴（删缓存），下次看时重新抄一张（Cache Miss 回填）。

### 2.2 Spring Boot 整合 Redis

Spring Boot 自动注入 `RedisTemplate`，但默认用 `JdkSerializationRedisSerializer` 存二进制乱码。需要自定义序列化器让 Redis 存干净 JSON。

**RedisTemplate vs StringRedisTemplate**：
- `RedisTemplate<String, Object>`：value 存对象，需配 JSON 序列化器
- `StringRedisTemplate`：key 和 value 都是 String，适合简单场景

本项目用 `RedisTemplate<String, Object>` + `GenericJackson2JsonRedisSerializer`。

### 2.3 缓存穿透与空值缓存

**缓存穿透**：查询不存在的商品，Redis 没有 → MySQL 也没有 → 每次都打到 MySQL。恶意攻击可压垮数据库。

**解法**：MySQL 查不到也往 Redis 存一个空值（如 `product:{id}` → `null`，短 TTL 如 5 分钟），下次同样的请求直接从 Redis 返回 null。

---

## 三、参考代码

### 3.1 RedisConfig（自定义序列化器）

**文件：`product-service/src/main/java/com/minimall/product/config/RedisConfig.java`**

```java
package com.minimall.product.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        // key 用 String 序列化
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        // value 用 JSON 序列化（存干净 JSON 而非二进制乱码）
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}
```

### 3.2 统一响应 Result + 异常处理（与 user-service 一致）

> 提示：product-service 也需要 Result/BusinessException/GlobalExceptionHandler，可以把 user-service 的代码复制过来改包名。

**文件：`product-service/src/main/java/com/minimall/product/common/Result.java`**

```java
package com.minimall.product.common;

import lombok.Data;

@Data
public class Result<T> {
    private Integer code;
    private String message;
    private T data;

    public static <T> Result<T> success(T data) {
        Result<T> r = new Result<>();
        r.code = 200;
        r.message = "success";
        r.data = data;
        return r;
    }

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> error(Integer code, String message) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = message;
        return r;
    }
}
```

**文件：`product-service/src/main/java/com/minimall/product/common/BusinessException.java`**

```java
package com.minimall.product.common;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private final Integer code;

    public BusinessException(String message) {
        super(message);
        this.code = 400;
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }
}
```

**文件：`product-service/src/main/java/com/minimall/product/common/GlobalExceptionHandler.java`**

```java
package com.minimall.product.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        log.warn("业务异常: {}", e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldError() != null
                ? e.getBindingResult().getFieldError().getDefaultMessage()
                : "参数校验失败";
        return Result.error(400, msg);
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.error(500, "服务器内部错误: " + e.getMessage());
    }
}
```

### 3.3 DTO

**文件：`product-service/src/main/java/com/minimall/product/dto/ProductRequest.java`**

```java
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
```

### 3.4 ProductService（核心：CRUD + Cache Aside）

**文件：`product-service/src/main/java/com/minimall/product/service/ProductService.java`**

```java
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

import java.math.BigDecimal;
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
    public void deleteProduct(Long id) {
        Product product = productMapper.findById(id);
        if (product == null) {
            throw new BusinessException(404, "商品不存在");
        }
        productMapper.deleteById(id);
        redisTemplate.delete(CACHE_KEY_PREFIX + id);
    }

    /** 查询库存（供 Day 5 order-service Feign 调用） */
    public Stock getStock(Long productId) {
        return stockMapper.findByProductId(productId);
    }
}
```

### 3.5 ProductMapper 新增方法 + XML

在 `ProductMapper.java` 中无需新增方法（Day 2 已有 insert/update/deleteById/findById/findAll/findByCategory）。

### 3.6 ProductController 改造

**文件：`product-service/src/main/java/com/minimall/product/controller/ProductController.java`**

```java
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
}
```

### 3.7 StockMapper 新增方法 + XML

需要给 `StockMapper` 加 `updateLocked` 方法（为 Day 5 预留，但今天先加上）：

**修改：`product-service/src/main/java/com/minimall/product/mapper/StockMapper.java`**

在接口中新加一行：
```java
int updateLocked(@Param("productId") Long productId, @Param("locked") Integer locked);
```

**修改：`product-service/src/main/resources/mapper/StockMapper.xml`**

在 `</mapper>` 之前新加：
```xml
<update id="updateLocked">
    UPDATE t_stock SET locked = #{locked} WHERE product_id = #{productId}
</update>
```

---

## 四、练习任务清单

### 任务 1：创建 RedisConfig
- `config/RedisConfig.java`（按 3.1），自定义 RedisTemplate 序列化器

### 任务 2：创建统一响应类
- `common/Result.java`、`common/BusinessException.java`、`common/GlobalExceptionHandler.java`（按 3.2，可从 user-service 复制改包名）

### 任务 3：创建 DTO
- `dto/ProductRequest.java`（按 3.3）

### 任务 4：创建 ProductService
- `service/ProductService.java`（按 3.4），核心是 Cache Aside 读写流程

### 任务 5：改造 ProductController
- 替换为 3.6 版本（CRUD + 库存查询）

### 任务 6：StockMapper 加方法 + XML
- 加 `updateLocked`（按 3.7）

### 任务 7：编译 + 启动 + 验证

```bash
cd ~/Documents/java_project/mini-mall
~/tools/apache-maven-3.9.16/bin/mvn clean compile

cd product-service
env -u SERVER_PORT DB_PASSWORD=zyC191380!! ~/tools/apache-maven-3.9.16/bin/mvn spring-boot:run
```

---

## 五、验收清单

| # | 验收项 | 验证方法 | 预期结果 |
|---|--------|----------|----------|
| 1 | 编译通过 | `mvn clean compile` | BUILD SUCCESS |
| 2 | 启动成功 | 控制台 | Started + 注册 Nacos |
| 3 | 创建商品 | `POST /products` | 返回商品 ID |
| 4 | 查详情-缓存未命中 | `GET /products/{id}`（首次） | 返回商品 JSON |
| 5 | 查详情-缓存命中 | 再次 `GET /products/{id}` | 返回相同数据 |
| 6 | Redis 验证缓存写入 | `redis-cli GET product:{id}` | 非 null，JSON 格式 |
| 7 | 修改商品后缓存删除 | `PUT /products/{id}` 再查 Redis | Redis 中 key 被删除 |
| 8 | 修改后重新查询回填 | 再次 `GET /products/{id}` | 返回更新后数据，Redis 重新写入 |
| 9 | 删除商品后缓存删除 | `DELETE /products/{id}` 再查 Redis | Redis 中 key 被删除 |
| 10 | 商品列表 | `GET /products` | 返回列表 |
| 11 | 库存查询 | `GET /products/{id}/stock` | 返回库存 JSON |
| 12 | 不存在商品-空值缓存 | `GET /products/99999` 后查 Redis | Redis 有 `product:99999` = 空值 |

---

## 六、踩坑预判

| # | 陷阱 | 症状 | 解决方案 |
|---|------|------|----------|
| 1 | **RedisTemplate 存乱码** | `redis-cli GET` 返回二进制不可读 | RedisConfig 用 `GenericJackson2JsonRedisSerializer` + `StringRedisSerializer` |
| 2 | **缓存中 Product 类型丢失** | 从 Redis 取出强转 Product 报 ClassCastException | `GenericJackson2JsonRedisSerializer` 序列化时存了 `@class` 类型信息，反序列化自动还原。确认 RedisConfig 配置正确 |
| 3 | **@PathVariable 拿不到参数名** | 报 `Name for argument not specified` | 父 POM 已配 `<parameters>true</parameters>`，勿删 |
| 4 | **Redis 连接失败** | 启动报 `Unable to connect to Redis` | 确认 `redis-cli ping` 返回 PONG |
| 5 | **创建商品没初始化库存** | getStock 返回 null | ProductService.createProduct 中同时 insert Stock 记录 |
| 6 | **@Valid 校验失败返回 500** | 字段为空报 500 | GlobalExceptionHandler 加 `MethodArgumentNotValidException` 处理 |
| 7 | **删缓存 key 拼错** | 改了数据但缓存仍返回旧数据 | key 前缀统一用 `CACHE_KEY_PREFIX = "product:"` + id |
| 8 | **空值缓存与正常缓存混淆** | 缓存命中时强转空字符串为 Product 报错 | 先判断 `"",equals(cached)` 再强转 |

---

## 七、核心理解点（写完自问）

1. **Cache Aside 模式的读流程和写流程分别是什么？为什么写流程是"先更新 DB 再删缓存"而不是"先删缓存再更新 DB"？**
2. **为什么修改商品后是"删缓存"而不是"更新缓存"？**
3. **空值缓存解决什么问题？TTL 为什么比正常缓存短？**
4. **`GenericJackson2JsonRedisSerializer` 和 `JdkSerializationRedisSerializer` 的区别是什么？**
5. **缓存命中时从 Redis 取出的 Object 为什么能安全强转为 Product？**

---

> 教学文档版本：v1.0 | 生成日期：2026-09-16

> AI生成