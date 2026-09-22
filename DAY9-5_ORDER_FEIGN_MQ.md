---
AIGC:
  ContentProducer: '001191110102MAD55U9H0F10002'
  ContentPropagator: '001191110102MAD55U9H0F10002'
  Label: '1'
  ProduceID: '0fdc4263-e3af-403d-a8f7-66799ebb7744'
  PropagateID: '0fdc4263-e3af-403d-a8f7-66799ebb7744'
  ReservedCode1: 'ae89cd66-7679-43a9-87a6-6d25449e7222'
  ReservedCode2: 'ae89cd66-7679-43a9-87a6-6d25449e7222'
---

# 阶段九 Day 5：订单服务（下单 + Feign 跨服务调用 + RabbitMQ）

> mini-mall 电商订单系统 | 教学文档 v1.0 | 2026-09-16

---

## 一、今日目标

完成 order-service 核心业务能力，实现完整下单链路：
1. **下单接口**：校验 → Feign 调 product-service 查商品/库存 → 创建订单 + 订单明细 → 发 MQ 消息
2. **OpenFeign 跨服务调用**：order-service → product-service（查商品详情、查库存）
3. **Sentinel 熔断降级**：product-service 不可用时返回降级信息
4. **RabbitMQ 异步消息**：下单成功后发消息，消费者异步处理通知
5. **订单查询接口**：订单列表、订单详情（含订单明细）

---

## 二、概念讲解

### 2.1 完整下单流程

```
用户下单（POST /orders）
    │
    ├─ 1. 校验请求参数
    ├─ 2. Feign 调 product-service 查商品详情（拿到名称、价格快照）
    ├─ 3. Feign 调 product-service 查库存（确认库存充足）
    ├─ 4. 生成订单号 → 写入 t_order
    ├─ 5. 写入 t_order_item（含商品名称/价格快照）
    ├─ 6. 写入 t_order_log（CREATE 日志）
    ├─ 7. 发 RabbitMQ 消息（异步通知）
    └─ 返回订单信息
```

### 2.2 OpenFeign 跨服务调用

OpenFeign 是声明式 HTTP 客户端：定义一个 Java 接口 + `@FeignClient` 注解，Spring 运行时自动生成实现类，把方法调用转为 HTTP 请求。

类比：打网约车——你只说目的地（服务名），平台自动派车（负载均衡选实例），你不用管路线（不用手写 URL）。

**关键配置**：
- `@FeignClient(name = "product-service")`：name 值必须与 product-service 在 Nacos 注册的 `spring.application.name` 一致
- Feign 接口路径与目标 Controller 路径完全一致
- 消费方定义本地 DTO（不依赖 product-service 的类）
- `@PathVariable` 必须显式指定参数名 `("id")`

### 2.3 Sentinel 熔断降级

当 product-service 不可用时，Feign 调用会超时/报错。Sentinel 提供 fallback 机制：调用失败时走降级逻辑而非直接报错。

类比：网约车平台派不到车时给你退"无法接单"的消息，而不是让你一直等。

### 2.4 RabbitMQ 异步消息

下单成功后发 MQ 消息，消费者异步处理（如发通知、记日志）。主流程不等待消费结果，实现异步解耦。

类比：审批通过后不当场打电话通知申请人，而是发个短信让申请人自己看。

---

## 三、参考代码

### 3.1 统一响应 + 异常处理

> 与 product-service 一致，复制改包名 `com.minimall.order`。

**文件：`order-service/src/main/java/com/minimall/order/common/Result.java`**

```java
package com.minimall.order.common;

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

**文件：`order-service/src/main/java/com/minimall/order/common/BusinessException.java`**

```java
package com.minimall.order.common;

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

**文件：`order-service/src/main/java/com/minimall/order/common/GlobalExceptionHandler.java`**

```java
package com.minimall.order.common;

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

### 3.2 本地 DTO（消费方定义，不依赖 product-service 的类）

**文件：`order-service/src/main/java/com/minimall/order/dto/ProductDTO.java`**

```java
package com.minimall.order.dto;

import lombok.Data;
import java.math.BigDecimal;

/** Feign 调 product-service 返回的商品信息（本地定义，不依赖 product-service 的类） */
@Data
public class ProductDTO {
    private Long id;
    private String name;
    private String description;
    private BigDecimal price;
    private String category;
    private Integer status;
}
```

**文件：`order-service/src/main/java/com/minimall/order/dto/StockDTO.java`**

```java
package com.minimall.order.dto;

import lombok.Data;

/** Feign 调 product-service 返回的库存信息 */
@Data
public class StockDTO {
    private Long productId;
    private Integer quantity;
    private Integer locked;
}
```

> 注意：product-service 的 Result 返回的是 `Result<Product>` 和 `Result<Stock>`，Feign 会自动反序列化 JSON。但消费方需要用包装类接收——因为 product-service 返回的 JSON 是 `{"code":200,"data":{...}}` 结构。

**文件：`order-service/src/main/java/com/minimall/order/dto/FeignResult.java`**

```java
package com.minimall.order.dto;

import lombok.Data;

/** 统一接收 product-service 返回的 Result 包装格式 */
@Data
public class FeignResult<T> {
    private Integer code;
    private String message;
    private T data;
}
```

### 3.3 下单请求 DTO

**文件：`order-service/src/main/java/com/minimall/order/dto/OrderRequest.java`**

```java
package com.minimall.order.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.util.List;

@Data
public class OrderRequest {

    @NotNull(message = "用户ID不能为空")
    private Long userId;

    @NotEmpty(message = "购买商品列表不能为空")
    private List<OrderItemRequest> items;

    @Data
    public static class OrderItemRequest {
        @NotNull(message = "商品ID不能为空")
        private Long productId;

        @NotNull(message = "购买数量不能为空")
        @Positive(message = "购买数量必须大于0")
        private Integer quantity;
    }
}
```

### 3.4 下单响应 DTO

**文件：`order-service/src/main/java/com/minimall/order/dto/OrderResponse.java`**

```java
package com.minimall.order.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class OrderResponse {
    private Long id;
    private String orderNo;
    private Long userId;
    private BigDecimal totalAmount;
    private Integer status;
    private LocalDateTime createTime;
    private List<OrderItem> items;

    @Data
    public static class OrderItem {
        private Long productId;
        private String productName;
        private BigDecimal price;
        private Integer quantity;
        private BigDecimal subTotal;
    }
}
```

### 3.5 Feign 客户端接口（核心）

**文件：`order-service/src/main/java/com/minimall/order/client/ProductClient.java`**

```java
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
        };
    }
}
```

> 注意：`FeignResult` 需要 Lombok `@Builder` 或加全参构造器。请在 `FeignResult` 上加 `@Builder` 注解和 `@NoArgsConstructor` + `@AllArgsConstructor`。

**修改 FeignResult.java**（补充注解）：

```java
package com.minimall.order.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeignResult<T> {
    private Integer code;
    private String message;
    private T data;
}
```

### 3.6 RabbitMQ 配置

**文件：`order-service/src/main/java/com/minimall/order/config/RabbitConfig.java`**

```java
package com.minimall.order.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String EXCHANGE = "order.exchange";
    public static final String QUEUE = "order.created.queue";
    public static final String ROUTING_KEY = "order.created";

    /** 交换机（DirectExchange 按路由键精准匹配） */
    @Bean
    public DirectExchange orderExchange() {
        return new DirectExchange(EXCHANGE, true, false);  // durable=true 持久化
    }

    /** 队列（durable=true 持久化，RabbitMQ 重启后不丢） */
    @Bean
    public Queue orderCreatedQueue() {
        return new Queue(QUEUE, true);
    }

    /** 绑定（把队列绑定到交换机，指定路由键） */
    @Bean
    public Binding orderBinding() {
        return BindingBuilder.bind(orderCreatedQueue())
                .to(orderExchange())
                .with(ROUTING_KEY);
    }
}
```

### 3.7 MQ 消息体

**文件：`order-service/src/main/java/com/minimall/order/dto/OrderMessage.java`**

```java
package com.minimall.order.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** MQ 消息体：下单成功后发送 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderMessage {
    private Long orderId;
    private String orderNo;
    private Long userId;
    private BigDecimal totalAmount;
}
```

### 3.8 MQ 消费者

**文件：`order-service/src/main/java/com/minimall/order/mq/OrderMessageConsumer.java`**

```java
package com.minimall.order.mq;

import com.minimall.order.config.RabbitConfig;
import com.minimall.order.dto.OrderMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** MQ 消费者：异步处理下单消息 */
@Slf4j
@Component
public class OrderMessageConsumer {

    @RabbitListener(queues = RabbitConfig.QUEUE)
    public void handleOrderCreated(OrderMessage message) {
        log.info("收到下单消息: orderId={}, orderNo={}, userId={}, amount={}",
                message.getOrderId(),
                message.getOrderNo(),
                message.getUserId(),
                message.getTotalAmount());
        // 实际项目：发短信通知、推送 APP、记审计日志等
        // 这里只打印日志
    }
}
```

### 3.9 OrderService（核心：下单全链路）

**文件：`order-service/src/main/java/com/minimall/order/service/OrderService.java`**

```java
package com.minimall.order.service;

import com.minimall.order.client.ProductClient;
import com.minimall.order.common.BusinessException;
import com.minimall.order.config.RabbitConfig;
import com.minimall.order.dto.*;
import com.minimall.order.mapper.*;
import com.minimall.order.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderLogMapper orderLogMapper;
    private final ProductClient productClient;
    private final RabbitTemplate rabbitTemplate;

    /**
     * 下单
     */
    public OrderResponse createOrder(OrderRequest req) {
        // 1. 遍历商品，Feign 调 product-service 查商品详情 + 库存
        List<OrderItem> orderItems = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;

        for (OrderRequest.OrderItemRequest itemReq : req.getItems()) {
            // 查商品
            FeignResult<ProductDTO> productResult = productClient.getProduct(itemReq.getProductId());
            if (productResult.getCode() != 200 || productResult.getData() == null) {
                throw new BusinessException(400, "商品不存在或商品服务不可用: " + itemReq.getProductId());
            }
            ProductDTO product = productResult.getData();

            // 查库存
            FeignResult<StockDTO> stockResult = productClient.getStock(itemReq.getProductId());
            if (stockResult.getCode() != 200 || stockResult.getData() == null) {
                throw new BusinessException(400, "库存查询失败: " + itemReq.getProductId());
            }
            StockDTO stock = stockResult.getData();

            // 校验库存
            int available = stock.getQuantity() - stock.getLocked();
            if (available < itemReq.getQuantity()) {
                throw new BusinessException(400, "库存不足: " + product.getName());
            }

            // 构造订单明细（商品名/价格快照）
            BigDecimal subTotal = product.getPrice().multiply(BigDecimal.valueOf(itemReq.getQuantity()));
            OrderItem item = new OrderItem();
            item.setProductId(product.getId());
            item.setProductName(product.getName());  // 快照
            item.setPrice(product.getPrice());        // 快照
            item.setQuantity(itemReq.getQuantity());
            item.setSubTotal(subTotal);
            orderItems.add(item);

            totalAmount = totalAmount.add(subTotal);
        }

        // 2. 生成订单号 + 写入 t_order
        String orderNo = generateOrderNo();
        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(req.getUserId());
        order.setTotalAmount(totalAmount);
        order.setStatus(0);  // 待支付
        orderMapper.insert(order);

        // 3. 写入 t_order_item（批量）
        for (OrderItem item : orderItems) {
            item.setOrderId(order.getId());
        }
        orderItemMapper.batchInsert(orderItems);

        // 4. 写入 t_order_log（CREATE 日志）
        OrderLog orderLog = new OrderLog();
        orderLog.setOrderId(order.getId());
        orderLog.setAction("CREATE");
        orderLog.setFromStatus(null);
        orderLog.setToStatus(0);
        orderLog.setRemark("用户下单");
        orderLogMapper.insert(orderLog);

        // 5. 发 RabbitMQ 消息（异步通知）
        OrderMessage message = new OrderMessage(
                order.getId(),
                order.getOrderNo(),
                order.getUserId(),
                order.getTotalAmount());
        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.ROUTING_KEY, message);
        log.info("下单成功，已发送 MQ 消息: orderNo={}", orderNo);

        // 6. 构造响应
        OrderResponse resp = new OrderResponse();
        resp.setId(order.getId());
        resp.setOrderNo(order.getOrderNo());
        resp.setUserId(order.getUserId());
        resp.setTotalAmount(order.getTotalAmount());
        resp.setStatus(order.getStatus());
        resp.setCreateTime(order.getCreateTime());
        List<OrderResponse.OrderItem> respItems = new ArrayList<>();
        for (OrderItem item : orderItems) {
            OrderResponse.OrderItem ri = new OrderResponse.OrderItem();
            ri.setProductId(item.getProductId());
            ri.setProductName(item.getProductName());
            ri.setPrice(item.getPrice());
            ri.setQuantity(item.getQuantity());
            ri.setSubTotal(item.getSubTotal());
            respItems.add(ri);
        }
        resp.setItems(respItems);
        return resp;
    }

    /**
     * 查询订单详情（含明细）
     */
    public OrderResponse getOrderById(Long id) {
        Order order = orderMapper.findById(id);
        if (order == null) {
            throw new BusinessException(404, "订单不存在");
        }
        List<OrderItem> items = orderItemMapper.findByOrderId(id);
        OrderResponse resp = new OrderResponse();
        resp.setId(order.getId());
        resp.setOrderNo(order.getOrderNo());
        resp.setUserId(order.getUserId());
        resp.setTotalAmount(order.getTotalAmount());
        resp.setStatus(order.getStatus());
        resp.setCreateTime(order.getCreateTime());
        List<OrderResponse.OrderItem> respItems = new ArrayList<>();
        for (OrderItem item : items) {
            OrderResponse.OrderItem ri = new OrderResponse.OrderItem();
            ri.setProductId(item.getProductId());
            ri.setProductName(item.getProductName());
            ri.setPrice(item.getPrice());
            ri.setQuantity(item.getQuantity());
            ri.setSubTotal(item.getSubTotal());
            respItems.add(ri);
        }
        resp.setItems(respItems);
        return resp;
    }

    /**
     * 查询用户订单列表
     */
    public List<Order> listByUserId(Long userId) {
        return orderMapper.findByUserId(userId);
    }

    /**
     * 生成订单号：日期 + UUID 片段
     */
    private String generateOrderNo() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
```

### 3.10 OrderController 改造

**文件：`order-service/src/main/java/com/minimall/order/controller/OrderController.java`**

```java
package com.minimall.order.controller;

import com.minimall.order.common.Result;
import com.minimall.order.dto.OrderRequest;
import com.minimall.order.dto.OrderResponse;
import com.minimall.order.model.Order;
import com.minimall.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /** 下单 */
    @PostMapping
    public Result<OrderResponse> createOrder(@Valid @RequestBody OrderRequest req) {
        return Result.success(orderService.createOrder(req));
    }

    /** 订单详情（含明细） */
    @GetMapping("/{id}")
    public Result<OrderResponse> getById(@PathVariable Long id) {
        return Result.success(orderService.getOrderById(id));
    }

    /** 用户订单列表 */
    @GetMapping("/user/{userId}")
    public Result<List<Order>> listByUser(@PathVariable Long userId) {
        return Result.success(orderService.listByUserId(userId));
    }
}
```

### 3.11 确认 application.yml

当前 yml 已配置 RabbitMQ 和 Sentinel，确认无需修改。Feign 的 `@EnableFeignClients` 已在 Day 1 的启动类上加好了。

---

## 四、练习任务清单

### 任务 1：创建统一响应 + 异常处理
- `common/Result.java`、`common/BusinessException.java`、`common/GlobalExceptionHandler.java`（按 3.1）

### 任务 2：创建 DTO
- `dto/ProductDTO.java`、`dto/StockDTO.java`、`dto/FeignResult.java`（按 3.2，注意 FeignResult 要加 @Builder）
- `dto/OrderRequest.java`（按 3.3，含嵌套 OrderItemRequest）
- `dto/OrderResponse.java`（按 3.4，含嵌套 OrderItem）
- `dto/OrderMessage.java`（按 3.7）

### 任务 3：创建 Feign 客户端 + 降级工厂
- `client/ProductClient.java`（按 3.5，含 FallbackFactory）

### 任务 4：创建 RabbitMQ 配置 + 消费者
- `config/RabbitConfig.java`（按 3.6）
- `mq/OrderMessageConsumer.java`（按 3.8）

### 任务 5：创建 OrderService
- `service/OrderService.java`（按 3.9，核心下单全链路）

### 任务 6：改造 OrderController
- 替换为 3.10 版本（下单 + 查询）

### 任务 7：编译 + 启动 + 验证

```bash
cd ~/Documents/java_project/mini-mall
~/tools/apache-maven-3.9.16/bin/mvn clean compile

# 需要 product-service 也在运行（Feign 要调用它）
# 启动 product-service（终端 1）
cd product-service
env -u SERVER_PORT DB_PASSWORD=zyC191380!! ~/tools/apache-maven-3.9.16/bin/mvn spring-boot:run

# 启动 order-service（终端 2）
cd order-service
env -u SERVER_PORT DB_PASSWORD=zyC191380!! ~/tools/apache-maven-3.9.16/bin/mvn spring-boot:run
```

---

## 五、验收清单

| # | 验收项 | 验证方法 | 预期结果 |
|---|--------|----------|----------|
| 1 | 编译通过 | `mvn clean compile` | BUILD SUCCESS |
| 2 | 启动成功 | 控制台 | Started + 注册 Nacos |
| 3 | 下单成功 | `POST /orders` | 返回订单号 + 明细 + 总金额 |
| 4 | 订单写入数据库 | 查 t_order + t_order_item | 有订单 + 明细记录 |
| 5 | 订单日志写入 | 查 t_order_log | 有 CREATE 记录 |
| 6 | Feign 调用 product-service | 下单时查商品/库存 | 返回商品名+价格快照 |
| 7 | 库存不足拦截 | 下单数量 > 库存 | code=400 "库存不足" |
| 8 | MQ 消息发送+消费 | 下单后查控制台日志 | "收到下单消息" |
| 9 | 订单详情查询 | `GET /orders/{id}` | 返回订单+明细列表 |
| 10 | 用户订单列表 | `GET /orders/user/{userId}` | 返回订单列表 |
| 11 | Sentinel 降级 | 停 product-service 后下单 | code=400 "商品服务不可用" |

---

## 六、踩坑预判

| # | 陷阱 | 症状 | 解决方案 |
|---|------|------|----------|
| 1 | **Feign 接口路径不对** | 调用报 404 | Feign 接口 `@GetMapping("/products/{id}")` 必须与 product-service 的 Controller 路径完全一致 |
| 2 | **@PathVariable 没写参数名** | Feign 调用报错 | `@PathVariable("id")` 显式指定，不依赖 `-parameters` |
| 3 | **FeignResult 泛型反序列化** | `data` 为 null | Feign 用 Jackson 自动反序列化，DTO 字段名与 JSON key 一致即可 |
| 4 | **MQ 队列未声明** | 消息发送报 `NOT_FOUND - no queue` | RabbitConfig 的 @Bean 声明 Queue/Exchange/Binding |
| 5 | **Sentinel Dashboard 未启动** | 降级不生效 | `feign.sentinel.enabled: true` 已配，即使 Dashboard 没启动降级也生效（客户端自带） |
| 6 | **product-service 没启动** | 下单 503/超时 | Day 5 验收需要 product-service 同时运行 |
| 7 | **批量插入参数名** | `batchInsert` 报错 | XML 中 `collection="list"` 配合 `@Param("list")` |
| 8 | **BigDecimal 运算** | 金额计算精度问题 | 用 `multiply` / `add`，不要用 `*` / `+` |

---

## 七、核心理解点（写完自问）

1. **Feign 为什么用 `FeignResult<ProductDTO>` 接收而不是直接 `ProductDTO`？**
2. **FallbackFactory 和直接 fallback 有什么区别？为什么推荐 FallbackFactory？**
3. **下单时为什么要存商品名称和价格的"快照"到 t_order_item？**
4. **RabbitMQ 的 DirectExchange 和 FanoutExchange 有什么区别？**
5. **Sentinel 降级和 Hystrix 熔断的核心思路有什么相同点？**

---

> 教学文档版本：v1.0 | 生成日期：2026-09-16

> AI生成