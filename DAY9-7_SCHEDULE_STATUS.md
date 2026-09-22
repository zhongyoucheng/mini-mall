---
AIGC:
  ContentProducer: '001191110102MAD55U9H0F10002'
  ContentPropagator: '001191110102MAD55U9H0F10002'
  Label: '1'
  ProduceID: '387e22d5-43da-4c3f-b026-746ef8a97cf7'
  PropagateID: '387e22d5-43da-4c3f-b026-746ef8a97cf7'
  ReservedCode1: 'ae0664ea-0731-4e97-98ff-db82f284dfcb'
  ReservedCode2: 'ae0664ea-0731-4e97-98ff-db82f284dfcb'
---

# 阶段九 Day 7：定时任务 + 业务完善（订单超时自动取消 + 状态机）

> mini-mall 电商订单系统 | 教学文档 v1.0 | 2026-09-17

---

## 一、今日目标

完善订单业务，实现生产级电商核心功能：
1. **订单状态机**：0=待支付 → 1=已支付 / 2=已取消 / 3=超时取消
2. **支付/取消接口**：含状态机校验（只有待支付才能支付/取消）
3. **定时任务**：每 60 秒扫描超时未支付订单，自动取消 + 恢复库存 + 发 MQ 通知
4. **事务保护**：给 createOrder 加 @Transactional（Day 5 遗留问题）

---

## 二、概念讲解

### 2.1 订单状态机

```
                    ┌─────────┐
        下单  ───▶  │ 0 待支付  │
                    └────┬────┘
                         │
              ┌──────────┼──────────┐
              │          │          │
         用户支付    用户取消    定时任务超时取消
              │          │          │
              ▼          ▼          ▼
          ┌──────┐  ┌──────┐  ┌──────┐
          │1已支付│  │2已取消│  │3超时  │
          └──────┘  └──────┘  └──────┘
```

**核心规则**：只有 `status=0（待支付）` 的订单才能执行支付/取消操作。已支付/已取消的订单不能再变更状态。后端必须校验，前端不可信。

类比：购物车已支付不能再点支付按钮，已取消的订单不能再取消。

### 2.2 定时任务超时自动取消

```
@Scheduled(fixedDelay = 60000)  // 每 60 秒执行一次
void cancelTimeoutOrders() {
    1. 查询 status=0 且 create_time < 15 分钟前的订单
    2. 逐条更新 status → 3（超时取消）
    3. 写 t_order_log（TIMEOUT 日志）
    4. 恢复库存（调 ProductClient 或直接发 MQ）
    5. 发 MQ 通知用户
}
```

定时任务直接调 Mapper（绕过 Service 副作用），这是阶段四验证过的模式。

### 2.3 @Scheduled 三种调度模式

| 模式 | 参数 | 计时起点 | Node 类比 |
|------|------|----------|-----------|
| fixedRate | `fixedRate=60000` | 上次**开始**执行 | `setInterval(fn, 60000)` |
| fixedDelay | `fixedDelay=60000` | 上次**结束**执行 | `setTimeout` 递归 |
| cron | `cron="0 */2 * * * ?"` | Cron 表达式 | node-cron |

本项目用 `fixedDelay=60000`（上次跑完等 60 秒再跑），因为超时扫描任务不宜并发执行。

---

## 三、参考代码

### 3.1 OrderMapper 新增方法

**修改：`order-service/src/main/java/com/minimall/order/mapper/OrderMapper.java`**

在接口中新加：
```java
/** 查询超时未支付订单（status=0 且创建时间早于指定分钟前） */
List<Order> findTimeoutOrders(@Param("minutes") int minutes);
```

**修改：`order-service/src/main/resources/mapper/OrderMapper.xml`**

在 `</mapper>` 之前新加：
```xml
<select id="findTimeoutOrders" resultMap="OrderMap">
    SELECT * FROM t_order
    WHERE status = 0
      AND create_time &lt; DATE_SUB(NOW(), INTERVAL #{minutes} MINUTE)
    ORDER BY id ASC
</select>
```

> 注意：XML 中 `<` 必须写成 `&lt;`（XML 转义），否则报语法错误。

### 3.2 OrderService 新增支付/取消接口 + 事务

**修改：`order-service/src/main/java/com/minimall/order/service/OrderService.java`**

在现有 OrderService 中新增以下方法，并给 createOrder 加 @Transactional：

```java
// === 在类顶部的 import 中加 ===
import org.springframework.transaction.annotation.Transactional;

// === 在现有方法之后新增 ===

/**
 * 支付订单（状态机：0→1）
 */
@Transactional
public void payOrder(Long id) {
    Order order = orderMapper.findById(id);
    if (order == null) {
        throw new BusinessException(404, "订单不存在");
    }
    if (order.getStatus() != 0) {
        throw new BusinessException(400, "订单状态不允许支付，当前状态: " + statusName(order.getStatus()));
    }
    orderMapper.updateStatus(id, 1);

    OrderLog log = new OrderLog();
    log.setOrderId(id);
    log.setAction("PAY");
    log.setFromStatus(0);
    log.setToStatus(1);
    log.setRemark("用户支付");
    orderLogMapper.insert(log);
}

/**
 * 取消订单（状态机：0→2）
 */
@Transactional
public void cancelOrder(Long id) {
    Order order = orderMapper.findById(id);
    if (order == null) {
        throw new BusinessException(404, "订单不存在");
    }
    if (order.getStatus() != 0) {
        throw new BusinessException(400, "订单状态不允许取消，当前状态: " + statusName(order.getStatus()));
    }
    orderMapper.updateStatus(id, 2);

    OrderLog log = new OrderLog();
    log.setOrderId(id);
    log.setAction("CANCEL");
    log.setFromStatus(0);
    log.setToStatus(2);
    log.setRemark("用户取消");
    orderLogMapper.insert(log);
}

/**
 * 超时取消（状态机：0→3），由定时任务调用
 */
@Transactional
public void timeoutCancelOrder(Order order) {
    orderMapper.updateStatus(order.getId(), 3);

    OrderLog log = new OrderLog();
    log.setOrderId(order.getId());
    log.setAction("TIMEOUT");
    log.setFromStatus(0);
    log.setToStatus(3);
    log.setRemark("超时自动取消");
    orderLogMapper.insert(log);

    // 发 MQ 通知
    OrderMessage message = new OrderMessage(
            order.getId(), order.getOrderNo(), order.getUserId(), order.getTotalAmount());
    rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.ROUTING_KEY, message);
}

/** 状态码转中文名 */
private String statusName(Integer status) {
    return switch (status) {
        case 0 -> "待支付";
        case 1 -> "已支付";
        case 2 -> "已取消";
        case 3 -> "超时取消";
        default -> "未知";
    };
}
```

同时，给现有的 `createOrder` 方法加上 `@Transactional`：

```java
@Transactional
public OrderResponse createOrder(OrderRequest req) {
    // ... 原有代码不变
}
```

### 3.3 定时任务

**文件：`order-service/src/main/java/com/minimall/order/task/OrderTimeoutTask.java`**

```java
package com.minimall.order.task;

import com.minimall.order.mapper.OrderMapper;
import com.minimall.order.model.Order;
import com.minimall.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 定时任务：扫描超时未支付订单，自动取消
 * 定时任务直接调 Mapper 查询，调 Service 执行状态变更（Service 有事务保护）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutTask {

    private final OrderMapper orderMapper;
    private final OrderService orderService;

    /** 超时阈值：15 分钟 */
    private static final int TIMEOUT_MINUTES = 15;

    /**
     * 每 60 秒扫描一次（fixedDelay：上次跑完等 60 秒再跑）
     */
    @Scheduled(fixedDelay = 60000)
    public void cancelTimeoutOrders() {
        // 1. 直接调 Mapper 查询超时订单（绕过 Service 副作用）
        List<Order> timeoutOrders = orderMapper.findTimeoutOrders(TIMEOUT_MINUTES);
        if (timeoutOrders.isEmpty()) {
            return;
        }

        log.info("发现 {} 笔超时订单，开始自动取消", timeoutOrders.size());

        // 2. 逐条取消（调 Service，有事务保护）
        for (Order order : timeoutOrders) {
            try {
                orderService.timeoutCancelOrder(order);
                log.info("超时取消成功: orderNo={}", order.getOrderNo());
            } catch (Exception e) {
                log.error("超时取消失败: orderNo={}, error={}", order.getOrderNo(), e.getMessage());
            }
        }
    }
}
```

### 3.4 OrderController 新增支付/取消接口

**修改：`order-service/src/main/java/com/minimall/order/controller/OrderController.java`**

在现有方法之后新增：

```java
/** 支付订单 */
@PutMapping("/{id}/pay")
public Result<Void> pay(@PathVariable Long id) {
    orderService.payOrder(id);
    return Result.success(null);
}

/** 取消订单 */
@PutMapping("/{id}/cancel")
public Result<Void> cancel(@PathVariable Long id) {
    orderService.cancelOrder(id);
    return Result.success(null);
}
```

### 3.5 确认 @EnableScheduling

Day 1 已在 `OrderServiceApplication` 加了 `@EnableScheduling`，确认无需修改。

---

## 四、练习任务清单

### 任务 1：OrderMapper 新增 findTimeoutOrders + XML
- 接口加 `findTimeoutOrders`（按 3.1）
- XML 加对应 SQL（注意 `&lt;` 转义）

### 任务 2：OrderService 新增支付/取消/超时取消 + 事务
- 加 `payOrder` / `cancelOrder` / `timeoutCancelOrder`（按 3.2）
- 给 `createOrder` 加 `@Transactional`
- 加 `statusName` 辅助方法

### 任务 3：创建定时任务
- `task/OrderTimeoutTask.java`（按 3.3）

### 任务 4：OrderController 新增支付/取消接口
- 加 `PUT /{id}/pay` 和 `PUT /{id}/cancel`（按 3.4）

### 任务 5：编译 + 启动 + 验证

```bash
cd ~/Documents/java_project/mini-mall
~/tools/apache-maven-3.9.16/bin/mvn clean compile

# 需要 product-service + order-service 运行
cd order-service
env -u SERVER_PORT DB_PASSWORD=zyC191380!! ~/tools/apache-maven-3.9.16/bin/mvn spring-boot:run
```

---

## 五、验收清单

| # | 验收项 | 验证方法 | 预期结果 |
|---|--------|----------|----------|
| 1 | 编译通过 | `mvn clean compile` | BUILD SUCCESS |
| 2 | 启动成功 | 控制台 | Started + 注册 Nacos |
| 3 | 支付订单 | `PUT /orders/{id}/pay` | code=200，status 变为 1 |
| 4 | 重复支付拦截 | 再 pay 同一订单 | code=400 "状态不允许支付" |
| 5 | 取消订单 | `PUT /orders/{id}/cancel` | code=200，status 变为 2 |
| 6 | 重复取消拦截 | 再 cancel 同一订单 | code=400 "状态不允许取消" |
| 7 | 超时订单查询 | SQL 查 status=0 且超时 | 返回超时订单列表 |
| 8 | 定时任务自动取消 | 创建订单→改 create_time→等 60s | status 变为 3，日志有 TIMEOUT |
| 9 | 订单日志记录 | 查 t_order_log | 有 PAY/CANCEL/TIMEOUT 记录 |

---

## 六、踩坑预判

| # | 陷阱 | 症状 | 解决方案 |
|---|------|------|----------|
| 1 | **XML 中 `<` 报错** | `findTimeoutOrders` SQL 语法错误 | `<` 写成 `&lt;`（XML 转义） |
| 2 | **@Scheduled 不生效** | 定时任务不执行 | 确认启动类有 `@EnableScheduling`（Day 1 已加） |
| 3 | **定时任务日志看不到** | System.out 无法读取 | 用 `log.info()`，查启动日志或 /tmp/order-day7.log |
| 4 | **状态机校验漏了** | 已支付订单还能再取消 | 每个状态变更前必须校验 `status == 0` |
| 5 | **定时任务测试时间太长** | 等待 15 分钟超时不现实 | 临时把 TIMEOUT_MINUTES 改为 1，用 SQL 改 create_time 为 2 分钟前 |
| 6 | **事务回滚不生效** | createOrder 部分失败但数据已写入 | 确认 `@Transactional` 在方法上，且 Spring 管理的 Bean（@Service） |
| 7 | **switch 语法** | JDK 17 switch 表达式语法报错 | 用 `switch (status) { case 0 -> "待支付"; ... }`，JDK 14+ 支持箭头语法 |

---

## 七、核心理解点（写完自问）

1. **为什么定时任务直接调 Mapper 查询、但调 Service 执行状态变更？**
2. **fixedDelay 和 fixedRate 的区别是什么？超时扫描任务为什么用 fixedDelay？**
3. **状态机校验为什么必须放在后端，不能只靠前端控制？**
4. **@Transactional 给 createOrder 带来了什么保护？不加会怎样？**
5. **DATE_SUB(NOW(), INTERVAL N MINUTE) 的作用是什么？类比 JS 怎么写？**

---

> 教学文档版本：v1.0 | 生成日期：2026-09-17

> AI生成