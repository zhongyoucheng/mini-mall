---
AIGC:
  ContentProducer: '001191110102MAD55U9H0F10002'
  ContentPropagator: '001191110102MAD55U9H0F10002'
  Label: '1'
  ProduceID: 'dc4b1849-238c-4a00-9cf9-400efddfe5d9'
  PropagateID: 'dc4b1849-238c-4a00-9cf9-400efddfe5d9'
  ReservedCode1: '18dbed3b-f444-4aba-a543-4627bf275f36'
  ReservedCode2: '18dbed3b-f444-4aba-a543-4627bf275f36'
---

# 阶段九：综合实战——电商订单系统全栈开发

> 九阶段学习路径收官阶段。从零搭建一个生产级微服务电商系统，整合前八阶段全部知识。
> 项目代号：`mini-mall`
> 预计天数：10 天

---

## 一、项目概述

### 业务场景
一个简化版电商系统，包含用户管理、商品管理、订单管理三大核心模块。模拟真实开发流程：需求分析 → 架构设计 → 编码实现 → 测试验证 → 容器化部署 → 性能调优。

### 为什么选电商系统？
电商系统是 Java 后端最经典的业务场景，天然需要：
- **微服务拆分**：用户、商品、订单天然是独立服务
- **服务间调用**：下单需要查用户信息、查商品库存
- **缓存加速**：商品详情页高频读取
- **异步消息**：下单后异步发通知、扣库存
- **安全认证**：用户登录、权限控制
- **定时任务**：订单超时自动取消
- **网关统一入口**：路由、限流、鉴权
- **容器化部署**：Docker 一键启动全链路

### 前八阶段知识整合对照

| 阶段 | 知识点 | 在本项目中的应用 |
|------|--------|------------------|
| 阶段一 Java 核心 | 面向对象、集合、异常 | 实体类设计、统一异常处理 |
| 阶段二 工程化 | Maven 多模块、分层架构 | 父 POM + 子模块、Controller-Service-Mapper |
| 阶段三 数据库 | MyBatis、MySQL | 商品/订单 CRUD、多表关联查询 |
| 阶段四 中间件 | Redis、RabbitMQ、定时任务 | 商品缓存、下单异步消息、订单超时取消 |
| 阶段五 安全认证 | Spring Security、JWT、OAuth2 | 用户登录签发 JWT、接口权限控制 |
| 阶段六 微服务 | Nacos、OpenFeign、Gateway、Sentinel | 服务注册发现、跨服务调用、网关路由、熔断限流 |
| 阶段七 测试 | JUnit 5、Mockito、集成测试 | 为每个服务写单元测试 + 集成测试 |
| 阶段八 JVM 调优 | GC、监控、参数调优 | 生产级 JVM 参数配置、压测调优 |

---

## 二、系统架构

### 架构图（文字版）

```
                        ┌─────────────────┐
                        │   前端 / Postman  │
                        └────────┬────────┘
                                 │
                        ┌────────▼────────┐
                        │  Gateway Service │  ← 统一入口，JWT 鉴权，路由转发
                        │    (port 8080)   │
                        └────────┬────────┘
                                 │
              ┌──────────────────┼──────────────────┐
              │                  │                  │
     ┌────────▼────────┐ ┌──────▼───────┐ ┌────────▼────────┐
     │  User Service    │ │ Product Svc  │ │  Order Service   │
     │   (port 8081)   │ │  (port 8083) │ │   (port 8082)    │
     └────────┬────────┘ └──────┬───────┘ └────────┬────────┘
              │                  │                  │
              │           ┌──────▼───────┐          │
              │           │    Redis     │          │
              │           │  (商品缓存)   │          │
              │           └──────────────┘          │
              │                                      │
              │           ┌──────────────┐           │
              │           │  RabbitMQ    │           │
              │           │ (异步消息)    │           │
              │           └──────────────┘           │
              │                                      │
     ┌────────▼──────────────────────────────────────▼─────┐
     │                    MySQL                           │
     │     mini_mall_user / mini_mall_product / mini_mall_order │
     └────────────────────────────────────────────────────┘
              │
     ┌────────▼────────┐
     │     Nacos       │  ← 服务注册中心 + 配置中心
     │   (port 8848)   │
     └─────────────────┘
```

### 微服务拆分

| 服务名 | 端口 | 职责 | 数据库 |
|--------|------|------|--------|
| gateway-service | 8080 | 统一入口、JWT 鉴权、路由转发、限流 | 无 |
| user-service | 8081 | 用户注册/登录、用户信息管理 | mini_mall_user |
| product-service | 8083 | 商品 CRUD、商品详情缓存、库存管理 | mini_mall_product |
| order-service | 8082 | 下单、订单查询、订单超时取消、跨服务调用 | mini_mall_order |

### 技术栈版本基线

| 组件 | 版本 | 说明 |
|------|------|------|
| JDK | 17.0.20.1 | Homebrew arm64 |
| Spring Boot | 3.3.4 | 与 cloud-demo 一致 |
| Spring Cloud | 2023.0.3 | |
| Spring Cloud Alibaba | 2023.0.3.2 | Nacos + Sentinel |
| MySQL | 8.x | 本地已安装 |
| Redis | 7.x | 本地 6379 已运行 |
| RabbitMQ | 4.3.5 | 本地 5672 已运行 |
| Nacos | 2.4.3 | 需启动 |
| Docker | 28.1.1 | 容器化部署 |

---

## 三、10 天详细规划

### Day 1：项目初始化 + 架构搭建
- 创建 `mini-mall` 父项目（Maven 多模块）
- 创建 4 个子模块：gateway / user / product / order
- 父 POM 统一版本管理（BOM import + `-parameters`）
- 各模块基础依赖配置
- 各模块 application.yml（Nacos 注册）
- 启动验证：4 个服务注册到 Nacos

### Day 2：数据库设计 + 实体类 + MyBatis 整合
- 设计 3 个数据库 + 8 张表（用户表、商品表、订单表等）
- 创建实体类（User、Product、Order、OrderItem 等）
- MyBatis Mapper 接口 + XML（CRUD + 多表关联）
- 统一响应 Result<T> + 全局异常处理
- 启动验证：各服务能连上各自数据库，CRUD 可用

### Day 3：用户服务（注册 + 登录 + JWT）
- 用户注册接口（密码 BCrypt 加密）
- 用户登录接口（验证密码 → 签发 JWT）
- Spring Security 整合 JWT（JwtAuthenticationFilter）
- SecurityConfig 配置（白名单放行登录/注册，其余需认证）
- 统一 401/403 JSON 响应
- 启动验证：注册 → 登录获取 Token → 带 Token 访问用户信息

### Day 4：商品服务（CRUD + Redis 缓存）
- 商品 CRUD 接口（分页查询、详情、增删改）
- Redis 缓存商品详情（Cache Aside 模式）
- 缓存 key 设计：`product:{id}`，TTL 30 分钟
- 修改商品后删缓存（先更新 DB 再删缓存）
- 商品库存查询接口（供 order-service 调用）
- 启动验证：CRUD 正常、缓存命中/未命中、缓存一致性

### Day 5：订单服务（下单 + 跨服务调用 + RabbitMQ）
- 下单接口：校验用户 → 查商品（Feign 调 product-service）→ 扣库存 → 创建订单
- OpenFeign 跨服务调用（order-service → product-service）
- Sentinel 熔断降级（product-service 不可用时返回降级信息）
- 下单成功后发 RabbitMQ 消息（异步通知 + 异步扣库存）
- 订单查询接口（分页查询、订单详情含商品信息）
- 启动验证：完整下单流程、Feign 调用、MQ 消息消费

### Day 6：网关 + 鉴权 + 限流
- Gateway 路由配置（/users/** → user-service，/products/** → product-service，/orders/** → order-service）
- Gateway JWT 鉴权过滤器（校验 Token → 解析用户信息 → 传递给下游服务）
- Gateway 全局过滤器（白名单放行登录/注册）
- Sentinel 网关限流（QPS 限制）
- 启动验证：通过 Gateway 统一访问、未带 Token 被拦截、限流生效

### Day 7：定时任务 + 业务完善
- 订单超时自动取消（@Scheduled 扫描超时订单 → 更新状态 → 恢复库存 → 发 MQ 通知）
- 定时任务直接调 Mapper（绕过 Service 副作用）
- 订单状态机（0=待支付, 1=已支付, 2=已取消, 3=超时取消）
- 订单状态变更接口（支付/取消，含状态机校验）
- 启动验证：创建订单不支付 → 等待超时 → 自动取消

### Day 8：测试套件
- 为 user-service 写单元测试（JwtUtil、UserService Mock 测试）
- 为 product-service 写集成测试（@SpringBootTest + MockMvc + H2 内存库）
- 为 order-service 写测试（Mockito Mock Feign 调用）
- 测试覆盖率检查
- 启动验证：mvn test 全部通过

### Day 9：Docker 容器化部署
- 为每个服务编写多阶段 Dockerfile（maven 构建 + JRE 运行）
- docker-compose.yml 编排（MySQL + Redis + RabbitMQ + Nacos + 4 服务）
- 服务健康检查（healthcheck + depends_on service_healthy）
- 环境变量化配置（Nacos 地址、数据库连接等）
- 启动验证：docker compose up 一键启动全链路

### Day 10：JVM 调优 + 综合压测 + 项目总结
- 为每个服务配置生产级 JVM 参数（G1 GC、堆大小、OOM dump）
- 用 `ab` 或 `wrk` 对网关做压测
- jstat / jmap / jstack 监控运行状态
- 分析 GC 日志，调优参数对比
- 项目总结：架构图、知识点回顾、踩坑记录

---

## 四、Day 1 教学：项目初始化 + 架构搭建

### 4.1 今日目标

从零创建 `mini-mall` 项目骨架，包含 4 个微服务模块，全部注册到 Nacos。

### 4.2 前置准备

#### 启动 Nacos
```bash
# 如果 Nacos 没启动，先启动（standalone 模式）
# 假设 nacos 在 ~/tools/nacos-2.4.3
cd ~/tools/nacos-2.4.3/bin
sh startup.sh -m standalone

# 验证：浏览器访问 http://localhost:8848/nacos
# 账号/密码：nacos/nacos
```

#### 创建项目目录
```bash
mkdir -p ~/Documents/java_project/mini-mall
```

### 4.3 父项目 POM

类比说明：父 POM ≈ monorepo 的根 `package.json`，统一管理所有子包的依赖版本。BOM import ≈ 不继承父项目而是"引用"一份版本清单。

**文件：`mini-mall/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.minimall</groupId>
    <artifactId>mini-mall</artifactId>
    <version>1.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <modules>
        <module>gateway-service</module>
        <module>user-service</module>
        <module>product-service</module>
        <module>order-service</module>
    </modules>

    <properties>
        <java.version>17</java.version>
        <spring-boot.version>3.3.4</spring-boot.version>
        <spring-cloud.version>2023.0.3</spring-cloud.version>
        <spring-cloud-alibaba.version>2023.0.3.2</spring-cloud-alibaba.version>
    </properties>

    <!-- BOM import 方式管理版本（不继承 spring-boot-starter-parent） -->
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-dependencies</artifactId>
                <version>${spring-boot.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>org.springframework.cloud</groupId>
                <artifactId>spring-cloud-dependencies</artifactId>
                <version>${spring-cloud.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>com.alibaba.cloud</groupId>
                <artifactId>spring-cloud-alibaba-dependencies</artifactId>
                <version>${spring-cloud-alibaba.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <configuration>
                        <source>${java.version}</source>
                        <target>${java.version}</target>
                        <encoding>UTF-8</encoding>
                        <!-- 关键：BOM import 方式必须显式加 -parameters -->
                        <!-- 否则 @PathVariable/@RequestParam 拿不到参数名 -->
                        <parameters>true</parameters>
                    </configuration>
                </plugin>
                <plugin>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-maven-plugin</artifactId>
                    <version>${spring-boot.version}</version>
                </plugin>
            </plugins>
        </pluginManagement>
    </build>
</project>
```

### 4.4 各子模块 POM

#### gateway-service/pom.xml
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.minimall</groupId>
        <artifactId>mini-mall</artifactId>
        <version>1.0-SNAPSHOT</version>
    </parent>

    <artifactId>gateway-service</artifactId>

    <dependencies>
        <!-- Spring Cloud Gateway（基于 WebFlux，不是 Spring MVC） -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-gateway</artifactId>
        </dependency>
        <!-- Nacos 服务发现 -->
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
        </dependency>
        <!-- Nacos 配置中心 -->
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
        </dependency>
        <!-- Sentinel 限流 -->
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-sentinel</artifactId>
        </dependency>
        <!-- 负载均衡器（替代已退役的 Ribbon） -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-loadbalancer</artifactId>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

#### user-service/pom.xml
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.minimall</groupId>
        <artifactId>mini-mall</artifactId>
        <version>1.0-SNAPSHOT</version>
    </parent>

    <artifactId>user-service</artifactId>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>org.mybatis.spring.boot</groupId>
            <artifactId>mybatis-spring-boot-starter</artifactId>
            <version>3.0.3</version>
        </dependency>
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
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
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

#### product-service/pom.xml
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.minimall</groupId>
        <artifactId>mini-mall</artifactId>
        <version>1.0-SNAPSHOT</version>
    </parent>

    <artifactId>product-service</artifactId>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.mybatis.spring.boot</groupId>
            <artifactId>mybatis-spring-boot-starter</artifactId>
            <version>3.0.3</version>
        </dependency>
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

#### order-service/pom.xml
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.minimall</groupId>
        <artifactId>mini-mall</artifactId>
        <version>1.0-SNAPSHOT</version>
    </parent>

    <artifactId>order-service</artifactId>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.mybatis.spring.boot</groupId>
            <artifactId>mybatis-spring-boot-starter</artifactId>
            <version>3.0.3</version>
        </dependency>
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
        <!-- RabbitMQ -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-amqp</artifactId>
        </dependency>
        <!-- OpenFeign（跨服务调用 product-service） -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-openfeign</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-loadbalancer</artifactId>
        </dependency>
        <!-- Sentinel（熔断降级） -->
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-sentinel</artifactId>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

### 4.5 各模块 application.yml

#### gateway-service/src/main/resources/application.yml
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
```

#### user-service/src/main/resources/application.yml
```yaml
server:
  port: 8081

spring:
  application:
    name: user-service
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/mini_mall_user?useSSL=false&characterEncoding=utf8mb4&serverTimezone=Asia/Shanghai
    username: root
    password: ${DB_PASSWORD:}
    driver-class-name: com.mysql.cj.jdbc.Driver
  data:
    redis:
      host: 127.0.0.1
      port: 6379
  cloud:
    nacos:
      discovery:
        server-addr: ${NACOS_ADDR:127.0.0.1:8848}
        username: ${NACOS_USERNAME:nacos}
        password: ${NACOS_PASSWORD:nacos}

# JWT 密钥（Day 3 会用到）
jwt:
  secret: mini-mall-jwt-secret-key-must-be-at-least-32-bytes-long
  expiration: 86400000  # 24 小时（毫秒）
```

#### product-service/src/main/resources/application.yml
```yaml
server:
  port: 8083

spring:
  application:
    name: product-service
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/mini_mall_product?useSSL=false&characterEncoding=utf8mb4&serverTimezone=Asia/Shanghai
    username: root
    password: ${DB_PASSWORD:}
    driver-class-name: com.mysql.cj.jdbc.Driver
  data:
    redis:
      host: 127.0.0.1
      port: 6379
  cloud:
    nacos:
      discovery:
        server-addr: ${NACOS_ADDR:127.0.0.1:8848}
        username: ${NACOS_USERNAME:nacos}
        password: ${NACOS_PASSWORD:nacos}
```

#### order-service/src/main/resources/application.yml
```yaml
server:
  port: 8082

spring:
  application:
    name: order-service
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/mini_mall_order?useSSL=false&characterEncoding=utf8mb4&serverTimezone=Asia/Shanghai
    username: root
    password: ${DB_PASSWORD:}
    driver-class-name: com.mysql.cj.jdbc.Driver
  data:
    redis:
      host: 127.0.0.1
      port: 6379
  rabbitmq:
    host: 127.0.0.1
    port: 5672
    username: guest
    password: guest
  cloud:
    nacos:
      discovery:
        server-addr: ${NACOS_ADDR:127.0.0.1:8848}
        username: ${NACOS_USERNAME:nacos}
        password: ${NACOS_PASSWORD:nacos}
    sentinel:
      transport:
        dashboard: 127.0.0.1:8858
        port: 8719
      eager: true

feign:
  sentinel:
    enabled: true
```

### 4.6 各模块启动类

#### gateway-service
```java
package com.minimall.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
```

#### user-service
```java
package com.minimall.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class UserServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
```

#### product-service
```java
package com.minimall.product;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ProductServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ProductServiceApplication.class, args);
    }
}
```

#### order-service
```java
package com.minimall.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableFeignClients    // Day 5 会用到 Feign 跨服务调用
@EnableScheduling      // Day 7 会用到定时任务
public class OrderServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
```

### 4.7 各模块健康检查接口

为了验证服务启动成功并注册到 Nacos，每个服务先写一个简单的健康检查接口。

#### user-service
```java
package com.minimall.user.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/users")
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
            "service", "user-service",
            "status", "UP",
            "port", 8081
        );
    }
}
```

#### product-service
```java
package com.minimall.product.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/products")
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
            "service", "product-service",
            "status", "UP",
            "port", 8083
        );
    }
}
```

#### order-service
```java
package com.minimall.order.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/orders")
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
            "service", "order-service",
            "status", "UP",
            "port", 8082
        );
    }
}
```

### 4.8 练习任务清单

#### 任务 1：创建项目目录结构
```bash
mkdir -p ~/Documents/java_project/mini-mall
cd ~/Documents/java_project/mini-mall

# 创建 4 个子模块目录结构
mkdir -p gateway-service/src/main/java/com/minimall/gateway
mkdir -p gateway-service/src/main/resources

mkdir -p user-service/src/main/java/com/minimall/user/controller
mkdir -p user-service/src/main/java/com/minimall/user/model
mkdir -p user-service/src/main/java/com/minimall/user/service
mkdir -p user-service/src/main/java/com/minimall/user/mapper
mkdir -p user-service/src/main/java/com/minimall/user/util
mkdir -p user-service/src/main/resources/mapper

mkdir -p product-service/src/main/java/com/minimall/product/controller
mkdir -p product-service/src/main/java/com/minimall/product/model
mkdir -p product-service/src/main/java/com/minimall/product/service
mkdir -p product-service/src/main/java/com/minimall/product/mapper
mkdir -p product-service/src/main/resources/mapper

mkdir -p order-service/src/main/java/com/minimall/order/controller
mkdir -p order-service/src/main/java/com/minimall/order/model
mkdir -p order-service/src/main/java/com/minimall/order/service
mkdir -p order-service/src/main/java/com/minimall/order/mapper
mkdir -p order-service/src/main/java/com/minimall/order/client
mkdir -p order-service/src/main/resources/mapper
```

#### 任务 2：编写 POM 文件
- 父 POM（`mini-mall/pom.xml`）
- 4 个子模块 POM（按 4.4 节内容编写）

#### 任务 3：编写 application.yml
- 4 个模块的 `application.yml`（按 4.5 节内容编写）
- **注意**：MySQL 密码需改成你本地的 root 密码。如果 root 无密码，password 留空即可

#### 任务 4：编写启动类 + 健康检查接口
- 4 个启动类（按 4.6 节内容编写）
- 3 个健康检查接口（gateway 无需，按 4.7 节编写）

#### 任务 5：创建数据库
```sql
-- 用 mysql 客户端执行
CREATE DATABASE IF NOT EXISTS mini_mall_user DEFAULT CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS mini_mall_product DEFAULT CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS mini_mall_order DEFAULT CHARACTER SET utf8mb4;
```

#### 任务 6：编译 + 启动 + 验证
```bash
# 编译整个项目
cd ~/Documents/java_project/mini-mall
~/tools/apache-maven-3.9.16/bin/mvn clean compile

# 启动 Nacos（如果没启动）
# 确保 http://localhost:8848/nacos 可访问

# 分别启动 4 个服务（4 个终端窗口）
# 终端 1：gateway
cd gateway-service
~/tools/apache-maven-3.9.16/bin/mvn spring-boot:run

# 终端 2：user-service
cd user-service
~/tools/apache-maven-3.9.16/bin/mvn spring-boot:run

# 终端 3：product-service
cd product-service
~/tools/apache-maven-3.9.16/bin/mvn spring-boot:run

# 终端 4：order-service
cd order-service
~/tools/apache-maven-3.9.16/bin/mvn spring-boot:run
```

### 4.9 验收清单

| # | 验收项 | 验证方法 | 预期结果 |
|---|--------|----------|----------|
| 1 | 项目编译通过 | `mvn clean compile` | BUILD SUCCESS |
| 2 | gateway-service 启动成功 | 控制台无报错 | Started GatewayApplication |
| 3 | user-service 启动成功 | 控制台无报错 | Started UserServiceApplication |
| 4 | product-service 启动成功 | 控制台无报错 | Started ProductServiceApplication |
| 5 | order-service 启动成功 | 控制台无报错 | Started OrderServiceApplication |
| 6 | 4 服务注册到 Nacos | 浏览器 `http://localhost:8848/nacos` 服务列表 | 4 个服务均 healthy |
| 7 | Gateway 路由转发正常 | `curl http://localhost:8080/users/health` | 返回 user-service 健康信息 |
| 8 | Gateway 转发 product | `curl http://localhost:8080/products/health` | 返回 product-service 健康信息 |
| 9 | Gateway 转发 order | `curl http://localhost:8080/orders/health` | 返回 order-service 健康信息 |

### 4.10 踩坑预判

| # | 陷阱 | 症状 | 解决方案 |
|---|------|------|----------|
| 1 | **BOM import 忘加 `-parameters`** | @PathVariable 报 500 `Name for argument not specified` | 父 POM maven-compiler-plugin 加 `<parameters>true</parameters>` |
| 2 | **MySQL 密码不对** | 启动报 `Access denied for user 'root'` | 修改 application.yml 的 password 为本地实际密码 |
| 3 | **Nacos 未启动** | 服务启动报 `Connection refused: connect to 127.0.0.1:8848` | 先启动 Nacos |
| 4 | **端口冲突** | `Port 8080 already in use` | 检查并 kill 占用端口的进程 |
| 5 | **Gateway 依赖冲突** | 引入 `spring-boot-starter-web` 导致 Gateway 启动失败 | Gateway 用 WebFlux 不是 MVC，不要引入 spring-boot-starter-web |
| 6 | **Spring Security 默认拦截** | user-service 引入 security 后 /users/health 被 401 | Day 1 暂时在 SecurityConfig 放行所有路径，Day 3 再做精细配置 |
| 7 | **zsh 通配符** | `-Xlog:gc*` 等 JVM 参数中的 `*` 被 zsh 展开 | 用引号包裹参数 |

> 踩坑 #6 补充：user-service 引入了 `spring-boot-starter-security`，默认拦截所有接口。Day 1 先加一个临时 SecurityConfig 放行全部，Day 3 做正式认证配置。
> ```java
> @Configuration
> public class SecurityConfig {
>     @Bean
>     SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
>         http.csrf(c -> c.disable())
>             .authorizeHttpRequests(a -> a.anyRequest().permitAll());
>         return http.build();
>     }
> }
> ```

### 4.11 核心理解点（写完自问）

1. **BOM import vs 继承 parent 的区别是什么？为什么本项目选 BOM import？**
   - 继承 `spring-boot-starter-parent` 简单但只能继承一个父 POM；BOM import 灵活可同时管理多个 BOM（Spring Boot + Spring Cloud + SCA），但需手动配 `-parameters`。本项目需要同时整合 Spring Cloud Alibaba，选 BOM import 更合适。

2. **Gateway 为什么不用 `spring-boot-starter-web`？**
   - Spring Cloud Gateway 基于 WebFlux（响应式/非阻塞），spring-boot-starter-web 基于 Servlet（阻塞）。两者不能共存，引入 web 会导致 Gateway 启动失败。

3. **每个服务用独立数据库的好处是什么？**
   - 微服务核心原则之一"数据私有"。每个服务拥有自己的数据库，服务之间不直接访问对方的数据库表，只能通过 API/Feign 调用。避免数据库层耦合，未来可独立拆分部署。

4. **父 POM 的 `<packaging>pom</packaging>` 是什么意思？**
   - 父项目不打包成 jar/war，它只是一个"配置容器"，管理子模块的依赖版本和编译配置。类比 monorepo 根目录的 package.json 不产出包，只管理工作空间。

5. **环境变量 `${NACOS_ADDR:127.0.0.1:8848}` 的语法是什么？**
   - Spring Boot 的占位符语法：`${变量名:默认值}`。有环境变量 `NACOS_ADDR` 就用环境变量的值，没有就用默认值 `127.0.0.1:8848`。这样本地开发和 Docker 部署用同一份配置文件。

---

## 五、后续天数预告

| 天 | 主题 | 关键产出 |
|----|------|----------|
| Day 2 | 数据库设计 + MyBatis | 3 库 8 表 + 实体类 + Mapper CRUD |
| Day 3 | 用户服务 + JWT 认证 | 注册/登录/JWT 签发/Security 配置 |
| Day 4 | 商品服务 + Redis 缓存 | 商品 CRUD + Cache Aside |
| Day 5 | 订单服务 + Feign + MQ | 下单流程 + 跨服务调用 + 异步消息 |
| Day 6 | 网关鉴权 + 限流 | Gateway JWT 过滤器 + Sentinel 限流 |
| Day 7 | 定时任务 + 状态机 | 订单超时取消 + 库存恢复 |
| Day 8 | 测试套件 | 单元测试 + 集成测试 |
| Day 9 | Docker 容器化 | Dockerfile + docker-compose |
| Day 10 | JVM 调优 + 压测 + 总结 | JVM 参数 + 压测 + GC 分析 |

---

> 教学文档版本：v1.0 | 生成日期：2026-09-15

> AI生成