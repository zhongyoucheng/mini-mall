---
AIGC:
  ContentProducer: '001191110102MAD55U9H0F10002'
  ContentPropagator: '001191110102MAD55U9H0F10002'
  Label: '1'
  ProduceID: 'd7ba43ab-3921-4640-bc78-421d55fb9e21'
  PropagateID: 'd7ba43ab-3921-4640-bc78-421d55fb9e21'
  ReservedCode1: '63819e2f-3a1d-4924-b433-66f75f52f634'
  ReservedCode2: '63819e2f-3a1d-4924-b433-66f75f52f634'
---

# 阶段九 Day 6：网关鉴权 + 限流

> mini-mall 电商订单系统 | 教学文档 v1.0 | 2026-09-17

---

## 一、今日目标

把 Gateway 从"纯路由转发"升级为"安全网关"：
1. **JWT 鉴权过滤器**：在 Gateway 层统一校验 Token，白名单放行，非白名单需带 Token
2. **用户信息透传**：Gateway 解析 Token 后把 userId/username/role 通过 Header 传给下游服务
3. **统一 401 JSON 响应**：未登录请求返回 JSON 而非空白页
4. **Sentinel 网关限流**：对 /orders/** 接口限制 QPS

---

## 二、概念讲解

### 2.1 为什么鉴权放在 Gateway 而不是每个服务各自做？

Day 3 在 user-service 做了 JWT 认证，但如果每个服务都各自校验 Token，存在两个问题：
- **重复代码**：每个服务都要写 JwtAuthenticationFilter + SecurityConfig
- **安全漏洞**：在微服务架构中，如果某个服务忘了配认证（如 Day 4 的 product-service 没配 Security），直接访问 8083 端口就绕过了鉴权

把鉴权统一放在 Gateway：所有外部请求先过 Gateway 的 JWT 过滤器，校验通过后才转发到下游服务。下游服务只处理业务逻辑，不关心认证。

类比：大楼大门保安统一查工牌（Gateway 鉴权），通过后进入各楼层不再重复检查（下游服务专注业务）。但 Day 3 user-service 的 Security 保留——防止有人绕过 Gateway 直连 8081 端口。

### 2.2 Gateway Filter 类型

Spring Cloud Gateway 有两种过滤器：
- **GlobalFilter**：全局过滤器，所有路由都经过。JWT 鉴权用这个
- **GatewayFilter**：路由级过滤器，只对特定路由生效。限流用这个

### 2.3 Gateway 与下游服务的认证信息传递

Gateway 校验 Token 后，把解析出的 userId/username/role 放入 HTTP Header 转发给下游服务。下游服务从 Header 读取即可，不需要再次解析 JWT。

```
客户端 → Gateway → user-service / product-service / order-service
         │
         ├─ 校验 JWT Token
         ├─ 解析 userId/username/role
         ├─ 放入 Header: X-User-Id / X-Username / X-Role
         └─ 转发请求（去掉 Authorization Header，不传 Token 给下游）
```

---

## 三、参考代码

### 3.1 JWT 鉴权全局过滤器（核心）

**文件：`gateway-service/src/main/java/com/minimall/gateway/filter/AuthGlobalFilter.java`**

```java
package com.minimall.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    @Value("${jwt.secret}")
    private String secret;

    private SecretKey key;

    // 白名单：不需要 Token 的路径
    private static final Set<String> WHITE_LIST = Set.of(
            "/users/register",
            "/users/login",
            "/users/check",
            "/users/health",
            "/products/health",
            "/orders/health"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 1. 白名单放行
        if (isWhiteListed(path)) {
            return chain.filter(exchange);
        }

        // 2. 从 Header 提取 Token
        String auth = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth == null || !auth.startsWith("Bearer ")) {
            return unauthorized(exchange, "未登录或 Token 无效");
        }

        String token = auth.substring(7);
        try {
            // 3. 验签 + 解析
            if (key == null) {
                key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
            }
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String userId = claims.getSubject();
            String username = claims.get("username", String.class);
            String role = claims.get("role", String.class);

            // 4. 把用户信息放入 Header 传给下游服务
            ServerHttpRequest mutated = request.mutate()
                    .header("X-User-Id", userId)
                    .header("X-Username", username)
                    .header("X-Role", role)
                    .build();

            // 去掉 Authorization Header（不把 Token 传给下游）
            mutated.getHeaders().remove(HttpHeaders.AUTHORIZATION);

            return chain.filter(exchange.mutate().request(mutated).build());
        } catch (Exception e) {
            log.warn("Token 验证失败: {}", e.getMessage());
            return unauthorized(exchange, "Token 无效或已过期");
        }
    }

    /** 判断是否白名单路径 */
    private boolean isWhiteListed(String path) {
        return WHITE_LIST.stream().anyMatch(path::startsWith);
    }

    /** 返回 401 JSON 响应 */
    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":401,\"message\":\"" + message + "\",\"data\":null}";
        DataBuffer buffer = response.bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        // 优先级最高（数字越小优先级越高）
        return -100;
    }
}
```

### 3.2 Sentinel 限流配置

**文件：`gateway-service/src/main/java/com/minimall/gateway/config/GatewayConfig.java`**

```java
package com.minimall.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.BlockRequestHandler;
import com.alibaba.csp.sentinel.adapter.gateway.sc.exception.SentinelGatewayExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodec;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;

import java.util.Collections;

@Configuration
public class GatewayConfig {

    /**
     * 自定义限流响应：被限流时返回 JSON 而非默认错误页
     */
    @Bean
    public BlockRequestHandler blockRequestHandler() {
        return (exchange, ex) -> ServerResponse
                .status(429)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(
                        "{\"code\":429,\"message\":\"请求过于频繁，请稍后再试\",\"data\":null}"));
    }
}
```

### 3.3 限流规则配置

限流规则通过 Sentinel Dashboard 动态配置（不需要写代码）。但为了验收方便，也可以在启动时用代码注册规则：

**文件：`gateway-service/src/main/java/com/minimall/gateway/config/SentinelRuleConfig.java`**

```java
package com.minimall.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.sc.SentinelGatewayFilter;
import com.alibaba.csp.sentinel.adapter.gateway.sc.route.RouteDefinitionContext;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

@Slf4j
@Component
public class SentinelRuleConfig {

    @PostConstruct
    public void initRules() {
        Set<GatewayFlowRule> rules = new HashSet<>();

        // 对 order-service 路由限流：QPS=2（方便测试）
        GatewayFlowRule orderRule = new GatewayFlowRule("order-service-route");
        orderRule.setCount(2);
        orderRule.setIntervalSec(1);
        rules.add(orderRule);

        GatewayRuleManager.loadRules(rules);
        log.info("Sentinel 网关限流规则已加载: order-service-route QPS=2");
    }
}
```

> 注意：`order-service-route` 必须与 application.yml 中路由的 `id` 一致。

### 3.4 修改 application.yml

需要加 JWT 密钥配置：

```yaml
server:
  port: 8080

spring:
  application:
    name: gateway-service
  cloud:
    nacos:
      discovery:
        server-addr: ${NACOS_ADDR:127.0.0.1:8848}
        username: ${NACOS_USERNAME:nacos}
        password: ${NACOS_PASSWORD:nacos}
      config:
        import-check:
          enabled: false
    gateway:
      routes:
        - id: user-service-route
          uri: lb://user-service
          predicates:
            - Path=/users/**
        - id: product-service-route
          uri: lb://product-service
          predicates:
            - Path=/products/**
        - id: order-service-route
          uri: lb://order-service
          predicates:
            - Path=/orders/**

jwt:
  secret: mini-mall-jwt-secret-key-must-be-at-least-32-bytes-long
```

### 3.5 Gateway POM 确认

Gateway 需要引入 JJWT 依赖来验签 Token。在 `gateway-service/pom.xml` 的 `<dependencies>` 中加：

```xml
<!-- JJWT（Gateway 验签 Token） -->
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
```

---

## 四、练习任务清单

### 任务 1：给 gateway-service 加 JJWT 依赖
- 修改 `gateway-service/pom.xml`，加 jjwt-api / jjwt-impl / jjwt-jackson 三个依赖

### 任务 2：创建 JWT 鉴权全局过滤器
- `filter/AuthGlobalFilter.java`（按 3.1）
- 实现白名单放行、Token 验签、用户信息透传 Header、401 JSON 响应

### 任务 3：创建 Sentinel 限流配置
- `config/GatewayConfig.java`（按 3.2）—— 自定义限流响应
- `config/SentinelRuleConfig.java`（按 3.3）—— 限流规则

### 任务 4：修改 application.yml
- 加 `jwt.secret` 配置（按 3.4）

### 任务 5：编译 + 启动 + 验证

```bash
cd ~/Documents/java_project/mini-mall
~/tools/apache-maven-3.9.16/bin/mvn clean compile

# 需要 user-service + order-service 也在运行
# 启动 user-service（终端 1）
cd user-service
env -u SERVER_PORT DB_PASSWORD=zyC191380!! ~/tools/apache-maven-3.9.16/bin/mvn spring-boot:run

# 启动 order-service（终端 2）
cd order-service
env -u SERVER_PORT DB_PASSWORD=zyC191380!! ~/tools/apache-maven-3.9.16/bin/mvn spring-boot:run

# 启动 gateway（终端 3）
cd gateway-service
env -u SERVER_PORT ~/tools/apache-maven-3.9.16/bin/mvn spring-boot:run
```

---

## 五、验收清单

| # | 验收项 | 验证方法 | 预期结果 |
|---|--------|----------|----------|
| 1 | 编译通过 | `mvn clean compile` | BUILD SUCCESS |
| 2 | Gateway 启动成功 | 控制台 | Started + 注册 Nacos |
| 3 | 白名单放行 | `GET /users/health`（无 Token） | 200 |
| 4 | 白名单放行 | `GET /products/health`（无 Token） | 200 |
| 5 | 非白名单无 Token 拦截 | `GET /products`（无 Token） | 401 + JSON |
| 6 | 带 Token 访问放行 | 先登录拿 Token，再 `GET /products`（带 Token） | 200 |
| 7 | Token 篡改拦截 | 改 Token 最后一位 | 401 + JSON |
| 8 | 经 Gateway 下单 | `POST /orders`（带 Token） | 200 + 订单信息 |
| 9 | 限流生效 | 快速连续调 `POST /orders` 3+ 次 | 第 3 次返回 429 |

---

## 六、踩坑预判

| # | 陷阱 | 症状 | 解决方案 |
|---|------|------|----------|
| 1 | **Gateway 引入 spring-boot-starter-web** | 启动报错 "Spring MVC found" | Gateway 基于 WebFlux，**不能**引入 spring-boot-starter-web |
| 2 | **JJWT 依赖缺失** | 编译报 `Jwts` 类找不到 | pom.xml 加 jjwt-api + jjwt-impl + jjwt-jackson |
| 3 | **白名单路径匹配不准** | `/users/login` 被拦截 401 | `isWhiteListed` 用 `startsWith` 匹配，注意 `/users/check` 能覆盖 `/users/check/xxx` |
| 4 | **限流规则路由 ID 不匹配** | 限流不生效 | `GatewayFlowRule("order-service-route")` 必须与 yml 中路由的 `id` 一致 |
| 5 | **Sentinel Dashboard 未启动** | 限流规则不生效 | SentinelRuleConfig 用 `@PostConstruct` 代码注册规则，不依赖 Dashboard |
| 6 | **WebFlux 异步返回** | 401 响应体为空或乱码 | 用 `DataBuffer` + `response.writeWith(Mono.just(buffer))` 返回 |
| 7 | **密钥不一致** | Gateway 验签失败但 user-service 签发正常 | Gateway 的 `jwt.secret` 必须与 user-service 的完全一致 |
| 8 | **mutate().request() 后丢失 Body** | POST 请求 Body 丢失 | Gateway 默认缓存 Body，`request.mutate().header(...)` 不影响 Body。如果遇到问题，加 `ModifyRequestBodyGatewayFilterFactory` |

---

## 七、核心理解点（写完自问）

1. **为什么把鉴权放在 Gateway 而不是每个服务各自做？各有什么优缺点？**
2. **Gateway 验证 Token 后为什么要把用户信息通过 Header 传给下游，而不是让下游再验一次 Token？**
3. **GlobalFilter 和 GatewayFilter 的区别是什么？什么场景用哪个？**
4. **Sentinel 限流的 QPS=2 意味着什么？超过 2 会怎样？**
5. **为什么 Gateway 去掉了 Authorization Header 再转发给下游？**

---

> 教学文档版本：v1.0 | 生成日期：2026-09-17

> AI生成