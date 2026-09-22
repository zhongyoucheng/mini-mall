---
AIGC:
  ContentProducer: '001191110102MAD55U9H0F10002'
  ContentPropagator: '001191110102MAD55U9H0F10002'
  Label: '1'
  ProduceID: '06fcd22d-46de-4841-a81e-2badc54458e9'
  PropagateID: '06fcd22d-46de-4841-a81e-2badc54458e9'
  ReservedCode1: '54ad0e95-cd11-41c2-949b-5d5d6649598f'
  ReservedCode2: '54ad0e95-cd11-41c2-949b-5d5d6649598f'
---

# 阶段九 Day 10：JVM 调优 + 综合压测 + 项目总结

> mini-mall 电商订单系统 | 教学文档 v1.0 | 2026-09-18
> **九阶段学习路径收官**

---

## 一、今日目标

把阶段八的 JVM 知识应用到 mini-mall 容器化环境：
1. **生产级 JVM 参数**：为 4 个微服务配置 G1 GC + 堆大小 + OOM dump
2. **压测**：用 `ab` 对 Gateway 做压力测试
3. **运行时监控**：jstat/jmap/jstack 监控容器内 JVM
4. **调优对比**：压测前 vs 调优后的 GC 表现
5. **项目总结**：架构全貌、知识点回顾、踩坑记录

---

## 二、概念讲解

### 2.1 容器内 JVM 参数注入

Docker 容器中 JVM 参数通过 `JAVA_OPTS` 环境变量传入。需要修改 Dockerfile 的 ENTRYPOINT：

```dockerfile
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

然后在 docker-compose.yml 中注入：

```yaml
environment:
  JAVA_OPTS: "-Xms256m -Xmx256m -XX:+UseG1GC ..."
```

### 2.2 JDK 17 + 容器感知

JDK 17 自动感知容器内存限制（`-XX:+UseContainerSupport` 默认开启）。但默认只用容器内存的 1/4 作为堆——如果容器限 512MB，堆只有 128MB，太小。需要显式指定 `-Xmx`。

### 2.3 压测工具 ab（Apache Bench）

```
ab -n 1000 -c 10 http://127.0.0.1:8090/users/health
```
- `-n 1000`：总请求 1000 次
- `-c 10`：并发 10
- 输出：RPS（每秒请求数）、平均响应时间、P99

### 2.4 G1 GC 关键参数

| 参数 | 作用 | 推荐值 |
|------|------|--------|
| `-XX:+UseG1GC` | 使用 G1 收集器 | JDK 17 默认，显式写更清晰 |
| `-Xms256m` | 初始堆 | 与 Xmx 相同（避免动态扩缩） |
| `-Xmx256m` | 最大堆 | 容器内存的 50-60% |
| `-XX:MaxGCPauseMillis=200` | 目标停顿时间 | 200ms（G1 会尽力满足） |
| `-XX:+HeapDumpOnOutOfMemoryError` | OOM 时自动 dump | 生产必备 |
| `-XX:HeapDumpPath=/app/dump.hprof` | dump 文件路径 | 配合卷挂载持久化 |
| `-Xlog:gc*:file=/app/gc.log` | GC 日志 | JDK 17 统一日志格式 |

---

## 三、参考代码

### 3.1 修改 Dockerfile（4 个统一改）

以 `gateway-service/Dockerfile` 为例：

```dockerfile
FROM maven:3.9.9-eclipse-temurin-17 AS builder
WORKDIR /build
COPY gateway-service/target/*.jar app.jar

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /build/app.jar app.jar
EXPOSE 8080
# 用 sh -c 解析 $JAVA_OPTS 环境变量
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

> 4 个 Dockerfile 都改 ENTRYPOINT 这一行，其余不变。

### 3.2 docker-compose.yml 注入 JVM 参数

在 4 个微服务的 `environment:` 中加 `JAVA_OPTS`：

```yaml
  gateway-service:
    # ... 原有配置
    environment:
      NACOS_ADDR: nacos:8848
      JWT_SECRET: ${JWT_SECRET:-mini-mall-jwt-secret-key-must-be-at-least-32-bytes-long}
      JAVA_OPTS: "-Xms256m -Xmx256m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Xlog:gc*:file=/app/gc.log:time -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/app/dump.hprof"

  user-service:
    environment:
      # ... 原有配置
      JAVA_OPTS: "-Xms256m -Xmx256m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Xlog:gc*:file=/app/gc.log:time -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/app/dump.hprof"

  # product-service 和 order-service 同上
```

### 3.3 压测脚本

**文件：`mini-mall/stress-test.sh`**

```bash
#!/bin/bash
# mini-mall 压测脚本

BASE_URL="http://127.0.0.1:8090"

echo "========== mini-mall 压测 =========="
echo ""

# 1. 健康检查接口（无 Token，最轻量）
echo "=== 1. /users/health（无 Token） ==="
ab -n 1000 -c 10 "$BASE_URL/users/health" 2>&1 | grep -E "Requests per second|Time per request|Failed requests|Complete requests"

echo ""
echo "=== 2. /products/health（无 Token） ==="
ab -n 1000 -c 10 "$BASE_URL/products/health" 2>&1 | grep -E "Requests per second|Time per request|Failed requests|Complete requests"

echo ""
echo "=== 3. 登录接口（POST + JSON Body） ==="
# 先注册一个压测用户
curl -s -X POST "$BASE_URL/users/register" \
  -H "Content-Type: application/json" \
  -d '{"username":"benchuser","password":"123456"}' > /dev/null 2>&1

ab -n 500 -c 10 -p /tmp/login.json -T "application/json" "$BASE_URL/users/login" 2>&1 | grep -E "Requests per second|Time per request|Failed requests|Complete requests"

echo ""
echo "=== 4. 带 Token 查商品列表（经过鉴权 + 路由 + downstream） ==="
TOKEN=$(curl -s -X POST "$BASE_URL/users/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"benchuser","password":"123456"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])" 2>/dev/null)

ab -n 500 -c 10 -H "Authorization: Bearer $TOKEN" "$BASE_URL/products" 2>&1 | grep -E "Requests per second|Time per request|Failed requests|Complete requests"

echo ""
echo "========== 压测完成 =========="
```

### 3.4 监控脚本

**文件：`mini-mall/monitor.sh`**

```bash
#!/bin/bash
# 容器内 JVM 监控

CONTAINER="mini-mall-gateway"

echo "=== JVM 进程 ==="
docker exec $CONTAINER jps 2>/dev/null

echo ""
echo "=== GC 统计（jstat -gcutil） ==="
PID=$(docker exec $CONTAINER jps -q 2>/dev/null | head -1)
docker exec $CONTAINER jstat -gcutil $PID 1000 5 2>/dev/null

echo ""
echo "=== 堆内存概况（jmap -heap） ==="
docker exec $CONTAINER jhsdb jmap --heap --pid $PID 2>/dev/null | head -30

echo ""
echo "=== 内存对象 Top10（jmap -histo） ==="
docker exec $CONTAINER jmap -histo $PID 2>/dev/null | head -15
```

---

## 四、练习任务清单

### 任务 1：修改 4 个 Dockerfile
- ENTRYPOINT 改为 `["sh", "-c", "java $JAVA_OPTS -jar app.jar"]`

### 任务 2：修改 docker-compose.yml
- 4 个微服务加 `JAVA_OPTS` 环境变量（按 3.2）

### 任务 3：创建压测脚本 + 监控脚本
- `stress-test.sh`（按 3.3）
- `monitor.sh`（按 3.4）
- 准备登录压测 body：`echo '{"username":"benchuser","password":"123456"}' > /tmp/login.json`

### 任务 4：重新构建 + 启动
```bash
cd ~/Documents/java_project/mini-mall
mvn clean package -DskipTests
docker compose down
docker compose up -d --build
```

### 任务 5：压测 + 监控
```bash
# 终端 1：监控
bash monitor.sh

# 终端 2：压测
bash stress-test.sh
```

---

## 五、验收清单

| # | 验收项 | 验证方法 | 预期结果 |
|---|--------|----------|----------|
| 1 | JVM 参数生效 | `docker exec mini-mall-gateway jps -v` | 看到 -Xms256m -Xmx256m -XX:+UseG1GC |
| 2 | GC 日志生成 | `docker exec mini-mall-gateway ls /app/gc.log` | 文件存在 |
| 3 | health 压测 | `ab -n 1000 -c 10 /users/health` | RPS > 500，0 失败 |
| 4 | 登录压测 | `ab -n 500 -c 10 /users/login` | RPS > 50，0 失败 |
| 5 | 带鉴权压测 | `ab -n 500 -c 10 /products` | RPS > 100，0 失败 |
| 6 | jstat 监控 | `jstat -gcutil` | G1 分区正常，Full GC = 0 |
| 7 | 堆内存监控 | `jmap --heap` | 堆使用 < 80% |

---

## 六、踩坑预判

| # | 陷阱 | 症状 | 解决方案 |
|---|------|------|----------|
| 1 | **ENTRYPOINT 不解析变量** | `$JAVA_OPTS` 原样传入 JVM 报错 | 用 `["sh", "-c", "java $JAVA_OPTS -jar app.jar"]` 而非 `["java", "$JAVA_OPTS", "-jar", "app.jar"]` |
| 2 | **ab 未安装** | `ab: command not found` | macOS 自带 `/usr/sbin/ab`，如果没有 `brew install httpd` |
| 3 | **ab POST body 传参** | 登录压测报 415 | 加 `-p /tmp/login.json -T "application/json"` |
| 4 | **容器内缺少 jstat/jmap** | `jstat: command not found` | eclipse-temurin:17-jre **不含 JDK 工具**！需改用 `eclipse-temurin:17-jdk` 或在宿主机用 `docker exec` + 容器内 PID |
| 5 | **GC 日志路径无权限** | 无法写 /app/gc.log | 确保 WORKDIR /app 有写权限（默认有） |
| 6 | **压测时 OOM** | 容器被 kill | 增大 -Xmx 或容器内存限制 |

> 踩坑 #4 重要：`eclipse-temurin:17-jre` 不含 jstat/jmap/jstack。压测监控需要 JDK 工具，有两个方案：
> - 方案 A（推荐）：在**宿主机**用 `jstat` 连接容器内进程——需要容器暴露 JMX 端口
> - 方案 B（简单）：压测时不监控 jstat，改用 `docker stats` 看容器内存/CPU 概况

---

## 七、项目总结

### 7.1 系统架构全貌

```
                    ┌────────────────┐
                    │  前端 / Postman  │
                    └───────┬────────┘
                            │ HTTP
                    ┌───────▼────────┐
                    │  Gateway :8090  │  JWT 鉴权 + 路由 + 限流
                    └───────┬────────┘
               ┌────────────┼────────────┐
               │            │            │
        ┌──────▼───┐ ┌─────▼────┐ ┌────▼─────┐
        │User :8081│ │Product   │ │Order     │
        │JWT+MySQL │ │:8083     │ │:8082     │
        │BCrypt    │ │MySQL+Redis│ │MySQL+MQ │
        └──────────┘ │Cache Aside│ │Feign+Sentinel│
                     └──────────┘ └──────────┘
                            │            │
               ┌────────────┼────────────┤
               │            │            │
        ┌──────▼──┐  ┌─────▼───┐  ┌────▼────┐
        │ MySQL   │  │ Redis   │  │RabbitMQ │
        │ 3库6表  │  │ 商品缓存 │  │异步消息  │
        └─────────┘  └─────────┘  └─────────┘
               │
        ┌──────▼──┐
        │ Nacos   │  注册中心 + 配置中心
        │ :8848   │
        └─────────┘
```

### 7.2 10 天知识点回顾

| 天 | 主题 | 核心知识点 |
|----|------|-----------|
| Day 1 | 项目初始化 | BOM import + `-parameters` + 多模块 + Nacos 注册 |
| Day 2 | 数据库 + MyBatis | 3 库 6 表 + resultMap + useGeneratedKeys + Cache Aside 预埋（库存双字段/快照/日志表） |
| Day 3 | 用户 + JWT | BCrypt + JJWT 0.12.6 新 API + JwtAuthenticationFilter + SecurityConfig + 401 JSON |
| Day 4 | 商品 + Redis | RedisConfig 序列化器 + Cache Aside 读写 + 空值缓存防穿透 |
| Day 5 | 订单 + Feign + MQ | 下单全链路 + Feign 跨服务调用 + FallbackFactory 降级 + RabbitMQ 异步消息 |
| Day 6 | 网关 + 限流 | AuthGlobalFilter JWT 鉴权 + 用户信息 Header 透传 + Sentinel 限流规则 |
| Day 7 | 定时任务 + 状态机 | @Scheduled 超时取消 + 状态机校验 + @Transactional + OrderLog |
| Day 8 | 测试套件 | JUnit 5 + Mockito @Mock/@InjectMocks + @WebMvcTest 切片测试 |
| Day 9 | Docker 容器化 | 多阶段 Dockerfile + docker-compose + healthcheck + 环境变量化 |
| Day 10 | JVM 调优 + 压测 | G1 GC 参数 + ab 压测 + jstat/jmap 监控 + 项目总结 |

### 7.3 整合的前八阶段知识

| 阶段 | 在 mini-mall 中的体现 |
|------|----------------------|
| Java 核心 | 实体类设计、异常处理、BigDecimal 金额计算、switch 表达式 |
| 工程化 | Maven 多模块、分层架构 Controller-Service-Mapper |
| 数据库 | MyBatis 动态 SQL、resultMap、多表关联、DATE_SUB 时间函数 |
| 中间件 | Redis Cache Aside、RabbitMQ DirectExchange、@Scheduled 定时任务 |
| 安全认证 | Spring Security + JWT、BCrypt、白名单、401/403 JSON |
| 微服务 | Nacos 注册发现、OpenFeign 声明式调用、Gateway 网关、Sentinel 熔断限流 |
| 测试 | JUnit 5 + Mockito 单元测试、@WebMvcTest 集成测试、19 个测试 |
| JVM 调优 | G1 GC 参数、堆大小配置、OOM dump、ab 压测 |

### 7.4 踩坑记录汇总

| 来源 | 问题 | 解决方案 |
|------|------|----------|
| Day 1 | BOM import 忘加 `-parameters` | 父 POM maven-compiler-plugin 加 `<parameters>true</parameters>` |
| Day 1 | `SERVER_PORT=4398` 覆盖 Spring Boot 端口 | 启动时 `env -u SERVER_PORT` |
| Day 1 | 8080 端口 IPv4 被 TeleAgent node 占用 | curl 用 `[::1]` 或 Gateway 映射 8090 |
| Day 6 | `ReadOnlyHttpHeaders.remove()` 抛异常 | 在 `mutate()` 链中 `.headers(h -> h.remove(...))` |
| Day 9 | Docker 镜像拉取 EOF 断连 | 重试从缓存续传 |
| Day 9 | 本地 Redis/RabbitMQ 端口冲突 | `brew services stop` + Docker 直接映射 |

---

## 八、核心理解点（写完自问）

1. **G1 GC 为什么适合微服务容器场景？相比 CMS/Parallel 有什么优势？**
2. **`-Xms` 和 `-Xmx` 设成相同值有什么好处？**
3. **`ab` 压测的 RPS 和并发数（-c）是什么关系？为什么并发越高 RPS 不一定越高？**
4. **`HeapDumpOnOutOfMemoryError` 在生产环境为什么必须开？OOM dump 文件能做什么？**
5. **容器内 JVM 和裸机 JVM 最大的区别是什么？JDK 17 是怎么解决容器感知问题的？**

---

## 九、九阶段学习路径收官

```
阶段一 Java 核心 → 阶段二 工程化 → 阶段三 数据库
→ 阶段四 中间件 → 阶段五 安全认证 → 阶段六 微服务
→ 阶段七 测试 → 阶段八 JVM 调优 → 阶段九 综合实战
```

mini-mall 电商订单系统 = 前八阶段全部知识的综合应用。

学完后你应该能够：
- 读懂任何 Spring Boot 微服务项目代码
- 快速接手项目，定位问题，做功能修改
- 理解从代码到容器化部署的完整 DevOps 链路

---

> 教学文档版本：v1.0 | 生成日期：2026-09-18 | **九阶段收官**

> AI生成