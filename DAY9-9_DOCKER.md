---
AIGC:
  ContentProducer: '001191110102MAD55U9H0F10002'
  ContentPropagator: '001191110102MAD55U9H0F10002'
  Label: '1'
  ProduceID: 'f9263d9b-e252-4de1-8fc2-a35968033896'
  PropagateID: 'f9263d9b-e252-4de1-8fc2-a35968033896'
  ReservedCode1: '0e4609be-4cbf-428a-90d1-46606aeaf768'
  ReservedCode2: '0e4609be-4cbf-428a-90d1-46606aeaf768'
---

# 阶段九 Day 9：Docker 容器化部署

> mini-mall 电商订单系统 | 教学文档 v1.0 | 2026-09-17

---

## 一、今日目标

把 mini-mall 全链路容器化，实现 `docker compose up` 一键启动：
1. **多阶段 Dockerfile**：maven 构建 + JRE 运行（镜像小、构建快）
2. **docker-compose.yml**：编排 MySQL + Redis + RabbitMQ + Nacos + 4 个微服务
3. **健康检查**：服务间依赖等待（Nacos 就绪后才启动微服务）
4. **环境变量化**：Nacos 地址、数据库密码、JWT 密钥全部用环境变量注入

```
docker compose up -d
    │
    ├─ mysql (3306)
    ├─ redis (6379)
    ├─ rabbitmq (5672/15672)
    ├─ nacos (8848)
    ├─ gateway-service (8080)
    ├─ user-service (8081)
    ├─ product-service (8083)
    └─ order-service (8082)
```

---

## 二、概念讲解

### 2.1 为什么用多阶段 Dockerfile？

**单阶段**（不用多阶段）的问题：构建需要 Maven + JDK，运行只需要 JRE。如果只有一个阶段，最终镜像要包含构建工具，体积大（几百 MB）。

**多阶段**：第一阶段用 `maven:3.9-eclipse-temurin-17` 构建 jar，第二阶段用 `eclipse-temurin:17-jre` 只拷贝 jar 运行。最终镜像只含 JRE + jar（约 200MB）。

类比：你在公司电脑（maven 镜像）开发完，把成品 jar 拷到干净的服务器（jre 镜像）上跑——服务器不需要装 Maven。

### 2.2 docker-compose 依赖等待

服务间有依赖关系：微服务需要先连上 Nacos 才能注册。如果 Nacos 还没起来微服务就启动，会注册失败。

解决方案：
- `depends_on`：控制启动顺序（但默认不等待"就绪"）
- `healthcheck` + `depends_on: condition: service_healthy`：等 Nacos 健康检查通过后再启动微服务

### 2.3 环境变量 vs 硬编码

Day 1 就在 application.yml 中用了 `${NACOS_ADDR:127.0.0.1:8848}` 占位符——默认值本地开发用，容器里用环境变量覆盖。Docker 里通过 `environment:` 注入。

---

## 三、参考代码

### 3.1 各服务 Dockerfile（多阶段构建）

**文件：`gateway-service/Dockerfile`**

```dockerfile
# 阶段一：构建（maven 镜像，包含 JDK + Maven）
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build

# 先拷贝父 POM 和本模块 POM（利用 Docker 层缓存，POM 不变则跳过依赖下载）
COPY pom.xml .
COPY gateway-service/pom.xml gateway-service/pom.xml
RUN mvn -pl gateway-service dependency:go-offline

# 拷贝源码并打包
COPY . .
RUN mvn -pl gateway-service package -DskipTests

# 阶段二：运行（jre 镜像，只有运行时）
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /build/gateway-service/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**文件：`user-service/Dockerfile`**（同构，改包名）

```dockerfile
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build

COPY pom.xml .
COPY user-service/pom.xml user-service/pom.xml
RUN mvn -q -pl user-service dependency:go-offline

COPY . .
RUN mvn -pl user-service package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /build/user-service/target/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**product-service/Dockerfile**（端口 8083）、**order-service/Dockerfile**（端口 8082）同上，改模块名和端口。

### 3.2 docker-compose.yml（核心）

**文件：`docker-compose.yml`**

```yaml
version: "3.8"

services:
  # ========== 基础设施 ==========
  mysql:
    image: mysql:8.0
    container_name: mini-mall-mysql
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_PASSWORD:-zyC191380!!}
      MYSQL_DATABASE: mini_mall_order
    ports:
      - "3306:3306"
    volumes:
      - ./sql:/docker-entrypoint-initdb.d   # 启动时自动执行建表 SQL
      - mysql-data:/var/lib/mysql
    command: --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost", "-p${MYSQL_PASSWORD:-zyC191380!!}"]
      interval: 10s
      timeout: 5s
      retries: 10

  redis:
    image: redis:7-alpine
    container_name: mini-mall-redis
    ports:
      - "6379:6379"
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 3s
      retries: 10

  rabbitmq:
    image: rabbitmq:3.13-management
    container_name: mini-mall-rabbitmq
    ports:
      - "5672:5672"      # AMQP 协议端口
      - "15672:15672"    # 管理界面
    environment:
      RABBITMQ_DEFAULT_USER: guest
      RABBITMQ_DEFAULT_PASS: guest
    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "-q", "ping"]
      interval: 10s
      timeout: 5s
      retries: 10

  nacos:
    image: nacos/nacos-server:v2.4.3
    container_name: mini-mall-nacos
    ports:
      - "8848:8848"
      - "9848:9848"   # gRPC 端口
    environment:
      MODE: standalone
      NACOS_AUTH_ENABLE: "false"
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8848/nacos"]
      interval: 10s
      timeout: 5s
      retries: 15

  # ============ 微服务 ============
  gateway-service:
    build:
      context: .
      dockerfile: gateway-service/Dockerfile
    container_name: mini-mall-gateway
    ports:
      - "8080:8080"
    environment:
      NACOS_ADDR: nacos:8848
      JWT_SECRET: ${JWT_SECRET:-mini-mall-jwt-secret-key-must-be-at-least-32-bytes-long}
    depends_on:
      nacos:
        condition: service_healthy

  user-service:
    build:
      context: .
      dockerfile: user-service/Dockerfile
    container_name: mini-mall-user
    ports:
      - "8081:8081"
    environment:
      NACOS_ADDR: nacos:8848
      DB_HOST: mysql
      DB_PASSWORD: ${MYSQL_PASSWORD:-zyC191380!!}
      JWT_SECRET: ${JWT_SECRET:-mini-mall-jwt-secret-key-must-be-at-least-32-bytes-long}
      JWT_EXPIRATION: 86400000
    depends_on:
      nacos:
        condition: service_healthy
      mysql:
        condition: service_healthy

  product-service:
    build:
      context: .
      dockerfile: product-service/Dockerfile
    container_name: mini-mall-product
    ports:
      - "8083:8083"
    environment:
      NACOS_ADDR: nacos:8848
      DB_HOST: mysql
      DB_PASSWORD: ${MYSQL_PASSWORD:-zyC191380!!}
    depends_on:
      nacos:
        condition: service_healthy
      mysql:
        condition: service_healthy

  order-service:
    build:
      context: .
      dockerfile: order-service/Dockerfile
    container_name: mini-mall-order
    ports:
      - "8082:8082"
    environment:
      NACOS_ADDR: nacos:8848
      DB_HOST: mysql
      DB_PASSWORD: ${MYSQL_PASSWORD:-zyC191380!!}
      RABBITMQ_HOST: rabbitmq
    depends_on:
      nacos:
        condition: service_healthy
      mysql:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy

volumes:
  mysql-data:
```

### 3.3 配置环境变量化改造

为了让 application.yml 支持容器内环境变量注入，需要把连接地址改成 `${DB_HOST:127.0.0.1}` 格式（Day 1 的 NACOS_ADDR 已经是这个模式）：

**user-service / product-service / order-service 的 application.yml**：

```yaml
spring:
  datasource:
    url: jdbc:mysql://${DB_HOST:127.0.0.1}:3306/mini_mall_user?useSSL=false&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai
    password: ${DB_PASSWORD:zyC191380!!}
```

**gateway-service 的 application.yml**：

```yaml
jwt:
  secret: ${JWT_SECRET:mini-mall-jwt-secret-key-must-be-at-least-32-bytes-long}
```

**user-service 的 application.yml**（JWT 部分）：

```yaml
jwt:
  secret: ${JWT_SECRET:mini-mall-jwt-secret-key-must-be-at-least-32-bytes-long}
  expiration: ${JWT_EXPIRATION:86400000}
```

---

## 四、练习任务清单

### 任务 1：编写 4 个 Dockerfile
- gateway-service/Dockerfile（8080）
- user-service/Dockerfile（8081）
- product-service/Dockerfile（8083）
- order-service/Dockerfile（8082）

### 任务 2：编写 docker-compose.yml
- 6 个服务 + healthcheck + depends_on

### 任务 3：application.yml 环境变量化
- 3 个业务服务的 datasource url 改 `${DB_HOST:127.0.0.1}`
- gateway + user 的 jwt.secret 改 `${JWT_SECRET:...}`

### 任务 4：准备建表 SQL 挂载目录
```bash
# 把 Day 2 的建表 SQL 整理到 sql/ 目录（项目已有 sql/ 目录）
ls mini-mall/sql/  # 确认有 3 库建表脚本
```

### 任务 5：构建 + 启动
```bash
cd ~/Documents/java_project/mini-mall
docker compose up -d --build

# 查看状态
docker compose ps

# 查看日志
docker compose logs -f nacos
docker compose logs -f user-service
```

### 任务 6：验证全链路
```bash
# Nacos 控制台
open http://localhost:8848/nacos

# 注册用户（经 Gateway）
curl -X POST http://localhost:8080/users/register -H "Content-Type: application/json" \
  -d '{"username":"dockeruser","password":"123456","nickname":"Docker用户"}'

# 登录拿 Token
curl -X POST http://localhost:8080/users/login -H "Content-Type: application/json" \
  -d '{"username":"dockeruser","password":"123456"}'

# 带 Token 查商品
curl http://localhost:8080/products -H "Authorization: Bearer <token>"
```

---

## 五、验收清单

| # | 验收项 | 验证方法 | 预期结果 |
|---|--------|----------|----------|
| 1 | Dockerfile 存在 | ls 各模块 Dockerfile | 4 个文件存在 |
| 2 | docker-compose.yml 存在 | 根目录 | 文件存在，语法正确 |
| 3 | 构建成功 | `docker compose build` | 4 个镜像构建成功 |
| 4 | 基础设施启动 | `docker compose ps` | mysql/redis/rabbitmq/nacos healthy |
| 5 | 微服务启动 | `docker compose ps` | 4 个服务运行中 |
| 6 | Nacos 注册 | 浏览器 Nacos 控制台 | 4 个服务 healthy |
| 7 | Gateway 路由 | curl localhost:8080/users/health | 200 |
| 8 | 全链路下单 | 注册→登录→查商品→下单 | 全流程通过 |
| 9 | 日志无 ERROR | `docker compose logs` | 无红色 ERROR |

---

## 六、踩坑预判

| # | 陷阱 | 症状 | 解决方案 |
|---|------|------|----------|
| 1 | **镜像拉取超时** | docker pull 卡住/失败 | 配置镜像加速（阿里云/中科大），或检查网络代理 |
| 2 | **依赖下载慢** | maven 构建阶段卡住 | 把 `~/.m2/settings.xml` 配阿里云镜像源；`dependency:go-offline` 提前缓存依赖 |
| 3 | **微服务启动早于 Nacos** | 注册失败 | `depends_on: condition: service_healthy` 确保 Nacos 健康后再启动 |
| 4 | **MySQL 初始化脚本顺序** | 建表 SQL 未执行 | mysql 容器的 `/docker-entrypoint-initdb.d` 目录按文件名顺序执行，放 `sql/` 下保证顺序 |
| 5 | **容器内 localhost** | 微服务连不上数据库 | 容器内 `localhost` 是容器自己！数据库要用服务名 `mysql`，Nacos 用 `nacos` |
| 6 | **端口冲突** | 本地已有 mysql/redis 占用端口 | 测试时可映射到其他端口（如 `3307:3306`），或先停本地服务 |
| 7 | **多阶段 COPY 路径** | 构建报 COPY 路径不存在 | 多阶段 `COPY . .` 的 context 是项目根目录，Dockerfile 放各模块目录时注意路径 |

---

## 七、核心理解点（写完自问）

1. **多阶段 Dockerfile 为什么镜像更小？哪两阶段分别是做什么的？**
2. **为什么容器内微服务不能连 `localhost`，必须连 `mysql`/`nacos` 这样的服务名？**
3. **`healthcheck` 和 `depends_on` 配合解决了什么问题？**
4. **application.yml 的 `${DB_HOST:127.0.0.1}` 冒号后是什么？本地开发和容器部署如何用同一份配置？**
5. **docker-compose 的 volumes 挂载解决了什么问题？（如 sql 初始化、MySQL 数据持久化）**

---

> 教学文档版本：v1.0 | 生成日期：2026-09-17

> AI生成