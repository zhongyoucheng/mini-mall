---
AIGC:
  ContentProducer: '001191110102MAD55U9H0F10002'
  ContentPropagator: '001191110102MAD55U9H0F10002'
  Label: '1'
  ProduceID: 'a4d82323-4b48-4121-88f9-ed39381dd6aa'
  PropagateID: 'a4d82323-4b48-4121-88f9-ed39381dd6aa'
  ReservedCode1: 'ef1b8f4c-58ff-4258-986c-ed6eeeecdffc'
  ReservedCode2: 'ef1b8f4c-58ff-4258-986c-ed6eeeecdffc'
---

# 阶段九 Day 8：测试套件（JUnit 5 + Mockito + 集成测试）

> mini-mall 电商订单系统 | 教学文档 v1.0 | 2026-09-17

---

## 一、今日目标

为 mini-mall 编写自动化测试，建立"改代码不怕改坏"的安全网：
1. **单元测试**：用 JUnit 5 + Mockito 测试 Service 层（Mock 掉 Mapper 和外部依赖）
2. **工具类测试**：测试 JwtUtil 的签发/解析/验签
3. **集成测试**：用 @SpringBootTest + MockMvc 测试 Controller 层（真实 HTTP 请求）
4. **运行测试**：`mvn test` 全部通过

---

## 二、概念讲解

### 2.1 为什么要写测试？

不写测试时，每次改代码都要手动启动服务 + curl 验证，效率低且容易漏。写测试后，`mvn test` 一键验证所有功能，改代码不怕改坏。

类比：测试像安全网——高空走钢丝（改代码）时有网兜着，摔了立刻知道哪里摔的。

### 2.2 单元测试 vs 集成测试

| 维度 | 单元测试 | 集成测试 |
|------|----------|----------|
| 测什么 | 单个类/方法 | 多个组件协作 |
| 依赖 | Mock 掉外部依赖 | 真实组件（或嵌入式） |
| 速度 | 极快（毫秒级） | 较慢（秒级，要启动 Spring） |
| 类比 | 测试单个零件 | 测试组装后的整机 |

### 2.3 JUnit 5 常用注解

| 注解 | 作用 | Node 类比 |
|------|------|-----------|
| `@Test` | 标记测试方法 | `it('should ...')` |
| `@BeforeEach` | 每个测试前执行 | `beforeEach()` |
| `@AfterEach` | 每个测试后执行 | `afterEach()` |
| `@DisplayName` | 测试名（中文可读） | `describe('...')` |
| `@ParameterizedTest` | 参数化测试 | 多组输入 |
| `@ExtendWith(MockitoExtension.class)` | 启用 Mockito | — |

### 2.4 Mockito 核心概念

Mockito 用来"伪造"依赖，让测试只关注被测类本身的逻辑。

| 方法 | 作用 | 类比 |
|------|------|------|
| `@Mock` | 创建假的依赖 | `jest.fn()` |
| `@InjectMocks` | 把 @Mock 注入到被测类 | `new Service(mockDep)` |
| `when(mock.method()).thenReturn(x)` | 指定假方法返回值 | `mockFn.mockReturnValue(x)` |
| `verify(mock, times(n)).method()` | 验证方法被调用 n 次 | `expect(mockFn).toHaveBeenCalledTimes(n)` |
| `assertThrows(X.class, () -> ...)` | 验证抛出指定异常 | `expect(() => fn()).toThrow()` |

---

## 三、POM 依赖

每个需要测试的模块都要加 `spring-boot-starter-test`（包含 JUnit 5 + Mockito + AssertJ + Spring Test）。

在 `user-service/pom.xml` 和 `order-service/pom.xml` 的 `<dependencies>` 中加：

```xml
<!-- 测试依赖（scope=test 只在测试时可用） -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
```

> 注意：`scope=test` 表示这个依赖只在编译和运行测试代码时可用，不会打包到最终 jar 中。

---

## 四、参考代码

### 4.1 JwtUtil 单元测试（user-service）

**文件：`user-service/src/test/java/com/minimall/user/util/JwtUtilTest.java`**

```java
package com.minimall.user.util;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JwtUtil 工具类测试")
class JwtUtilTest {

    private JwtUtil jwtUtil;
    private static final String SECRET = "mini-mall-jwt-secret-key-must-be-at-least-32-bytes-long";
    private static final long EXPIRATION = 86400000L;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil(SECRET, EXPIRATION);
    }

    @Test
    @DisplayName("生成 Token 不为空且为三段式")
    void generateToken_shouldReturnNonEmptyToken() {
        String token = jwtUtil.generateToken(1L, "alice", "USER");
        
        assertNotNull(token);
        assertEquals(3, token.split("\\.").length, "Token 应为三段式（Header.Payload.Signature）");
    }

    @Test
    @DisplayName("解析 Token 应返回正确的用户信息")
    void parseToken_shouldReturnCorrectClaims() {
        String token = jwtUtil.generateToken(1L, "alice", "ADMIN");
        
        Claims claims = jwtUtil.parseToken(token);
        
        assertEquals("1", claims.getSubject(), "subject 应为 userId");
        assertEquals("alice", claims.get("username", String.class));
        assertEquals("ADMIN", claims.get("role", String.class));
    }

    @Test
    @DisplayName("验证合法 Token 返回 true")
    void validateToken_validToken_shouldReturnTrue() {
        String token = jwtUtil.generateToken(1L, "alice", "USER");
        
        assertTrue(jwtUtil.validateToken(token));
    }

    @Test
    @DisplayName("验证篡改 Token 返回 false")
    void validateToken_tamperedToken_shouldReturnFalse() {
        String token = jwtUtil.generateToken(1L, "alice", "USER");
        String tampered = token.substring(0, token.length() - 5) + "XXXXX";
        
        assertFalse(jwtUtil.validateToken(tampered));
    }

    @Test
    @DisplayName("验证过期 Token 返回 false")
    void validateToken_expiredToken_shouldReturnFalse() {
        // 创建一个已过期的 JwtUtil（expiration=0，立即过期）
        JwtUtil expiredJwtUtil = new JwtUtil(SECRET, 1L);
        String token = expiredJwtUtil.generateToken(1L, "alice", "USER");
        
        // 等 10ms 让 Token 过期
        try { Thread.sleep(20); } catch (InterruptedException e) { }
        
        assertFalse(expiredJwtUtil.validateToken(token));
    }
}
```

### 4.2 UserService 单元测试（user-service，Mockito Mock Mapper）

**文件：`user-service/src/test/java/com/minimall/user/service/UserServiceTest.java`**

```java
package com.minimall.user.service;

import com.minimall.user.common.BusinessException;
import com.minimall.user.dto.LoginRequest;
import com.minimall.user.dto.LoginResponse;
import com.minimall.user.dto.RegisterRequest;
import com.minimall.user.mapper.UserMapper;
import com.minimall.user.model.User;
import com.minimall.user.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService 单元测试")
class UserServiceTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtUtil jwtUtil;

    @InjectMocks
    private UserService userService;

    @Test
    @DisplayName("注册：用户名不存在时应成功插入")
    void register_newUsername_shouldInsert() {
        // 1. 准备数据
        RegisterRequest req = new RegisterRequest();
        req.setUsername("newuser");
        req.setPassword("123456");
        req.setNickname("新用户");

        // 2. Mock 行为：查重返回 null（用户名不存在）
        when(userMapper.findByUsername("newuser")).thenReturn(null);
        when(passwordEncoder.encode("123456")).thenReturn("$2a$10$encoded");

        // 3. 执行
        userService.register(req);

        // 4. 验证：userMapper.insert 被调用 1 次
        verify(userMapper, times(1)).insert(any(User.class));
    }

    @Test
    @DisplayName("注册：用户名已存在时应抛出业务异常")
    void register_existingUsername_shouldThrow() {
        RegisterRequest req = new RegisterRequest();
        req.setUsername("alice");
        req.setPassword("123456");

        // Mock：查重返回非 null（用户名已存在）
        User existing = new User();
        existing.setUsername("alice");
        when(userMapper.findByUsername("alice")).thenReturn(existing);

        // 验证：抛出 BusinessException
        BusinessException ex = assertThrows(BusinessException.class, () -> {
            userService.register(req);
        });

        assertEquals(400, ex.getCode());
        assertEquals("用户名已存在", ex.getMessage());

        // 验证：insert 从未被调用
        verify(userMapper, never()).insert(any());
    }

    @Test
    @DisplayName("登录：正确密码应返回 Token + 用户信息")
    void login_correctPassword_shouldReturnToken() {
        LoginRequest req = new LoginRequest();
        req.setUsername("alice");
        req.setPassword("123456");

        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        user.setPassword("$2a$10$encoded");
        user.setNickname("Alice");
        user.setRole("USER");

        when(userMapper.findByUsername("alice")).thenReturn(user);
        when(passwordEncoder.matches("123456", "$2a$10$encoded")).thenReturn(true);
        when(jwtUtil.generateToken(1L, "alice", "USER")).thenReturn("mock-token");

        LoginResponse resp = userService.login(req);

        assertNotNull(resp);
        assertEquals("mock-token", resp.getToken());
        assertEquals(1L, resp.getUserId());
        assertEquals("alice", resp.getUsername());
        assertEquals("USER", resp.getRole());
    }

    @Test
    @DisplayName("登录：错误密码应抛出 401 异常")
    void login_wrongPassword_shouldThrow401() {
        LoginRequest req = new LoginRequest();
        req.setUsername("alice");
        req.setPassword("wrongpass");

        User user = new User();
        user.setUsername("alice");
        user.setPassword("$2a$10$encoded");

        when(userMapper.findByUsername("alice")).thenReturn(user);
        when(passwordEncoder.matches("wrongpass", "$2a$10$encoded")).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () -> {
            userService.login(req);
        });

        assertEquals(401, ex.getCode());
    }

    @Test
    @DisplayName("登录：用户不存在应抛出 401 异常")
    void login_userNotFound_shouldThrow401() {
        LoginRequest req = new LoginRequest();
        req.setUsername("ghost");
        req.setPassword("123456");

        when(userMapper.findByUsername("ghost")).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> {
            userService.login(req);
        });

        assertEquals(401, ex.getCode());
    }
}
```

### 4.3 OrderService 单元测试（order-service，Mock Feign + Mapper）

**文件：`order-service/src/test/java/com/minimall/order/service/OrderServiceTest.java`**

```java
package com.minimall.order.service;

import com.minimall.order.client.ProductClient;
import com.minimall.order.common.BusinessException;
import com.minimall.order.dto.*;
import com.minimall.order.mapper.*;
import com.minimall.order.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderService 单元测试")
class OrderServiceTest {

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderItemMapper orderItemMapper;
    @Mock
    private OrderLogMapper orderLogMapper;
    @Mock
    private ProductClient productClient;
    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private OrderService orderService;

    @Test
    @DisplayName("支付订单：status=0 时应成功更新为 1")
    void payOrder_status0_shouldUpdateTo1() {
        Order order = new Order();
        order.setId(1L);
        order.setStatus(0);

        when(orderMapper.findById(1L)).thenReturn(order);

        orderService.payOrder(1L);

        verify(orderMapper).updateStatus(1L, 1);
        verify(orderLogMapper).insert(any(OrderLog.class));
    }

    @Test
    @DisplayName("支付订单：status=1 时应抛出异常")
    void payOrder_status1_shouldThrow() {
        Order order = new Order();
        order.setId(1L);
        order.setStatus(1);

        when(orderMapper.findById(1L)).thenReturn(order);

        BusinessException ex = assertThrows(BusinessException.class, () -> {
            orderService.payOrder(1L);
        });

        assertEquals(400, ex.getCode());
        verify(orderMapper, never()).updateStatus(anyLong(), anyInt());
    }

    @Test
    @DisplayName("取消订单：status=0 时应成功更新为 2")
    void cancelOrder_status0_shouldUpdateTo2() {
        Order order = new Order();
        order.setId(1L);
        order.setStatus(0);

        when(orderMapper.findById(1L)).thenReturn(order);

        orderService.cancelOrder(1L);

        verify(orderMapper).updateStatus(1L, 2);
        verify(orderLogMapper).insert(any(OrderLog.class));
    }

    @Test
    @DisplayName("取消订单：status=2 时应抛出异常")
    void cancelOrder_status2_shouldThrow() {
        Order order = new Order();
        order.setId(1L);
        order.setStatus(2);

        when(orderMapper.findById(1L)).thenReturn(order);

        assertThrows(BusinessException.class, () -> {
            orderService.cancelOrder(1L);
        });

        verify(orderMapper, never()).updateStatus(anyLong(), anyInt());
    }

    @Test
    @DisplayName("支付订单：订单不存在应抛出 404")
    void payOrder_notFound_shouldThrow404() {
        when(orderMapper.findById(999L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> {
            orderService.payOrder(999L);
        });

        assertEquals(404, ex.getCode());
    }

    @Test
    @DisplayName("超时取消：应更新 status=3 + 写日志 + 发 MQ")
    void timeoutCancelOrder_shouldUpdateAndLogAndSendMQ() {
        Order order = new Order();
        order.setId(1L);
        order.setOrderNo("NO001");
        order.setUserId(1L);
        order.setTotalAmount(BigDecimal.valueOf(100));
        order.setStatus(0);

        orderService.timeoutCancelOrder(order);

        verify(orderMapper).updateStatus(1L, 3);
        verify(orderLogMapper).insert(argThat(log ->
            "TIMEOUT".equals(log.getAction()) &&
            log.getToStatus() == 3
        ));
        verify(rabbitTemplate).convertAndSend(anyString(), anyString(), any(OrderMessage.class));
    }
}
```

### 4.4 UserController 集成测试（user-service，MockMvc）

**文件：`user-service/src/test/java/com/minimall/user/controller/UserControllerTest.java`**

```java
package com.minimall.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minimall.user.dto.RegisterRequest;
import com.minimall.user.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("UserController 集成测试")
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserService userService;  // Mock 掉 Service，只测 Controller 层

    @Test
    @DisplayName("注册接口：合法参数应返回 200")
    void register_validInput_shouldReturn200() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setUsername("testuser");
        req.setPassword("123456");

        doNothing().when(userService).register(any(RegisterRequest.class));

        mockMvc.perform(post("/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("注册接口：空用户名应返回 400（参数校验）")
    void register_emptyUsername_shouldReturn400() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setUsername("");
        req.setPassword("123456");

        mockMvc.perform(post("/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())  // GlobalExceptionHandler 返回 200 + code=400
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("注册接口：密码不足 6 位应返回 400")
    void register_shortPassword_shouldReturn400() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setUsername("testuser");
        req.setPassword("123");  // 少于 6 位

        mockMvc.perform(post("/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(jsonPath("$.code").value(400));
    }
}
```

---

## 五、练习任务清单

### 任务 1：加测试依赖
- user-service/pom.xml 加 `spring-boot-starter-test`（scope=test）
- order-service/pom.xml 加 `spring-boot-starter-test`（scope=test）

### 任务 2：JwtUtil 单元测试
- `user-service/src/test/java/com/minimall/user/util/JwtUtilTest.java`（按 4.1）
- 5 个测试：生成/解析/验签合法/验签篡改/验签过期

### 任务 3：UserService 单元测试
- `user-service/src/test/java/com/minimall/user/service/UserServiceTest.java`（按 4.2）
- 5 个测试：注册成功/注册重复/登录成功/登录密码错/登录用户不存在

### 任务 4：OrderService 单元测试
- `order-service/src/test/java/com/minimall/order/service/OrderServiceTest.java`（按 4.3）
- 6 个测试：支付成功/支付重复/取消成功/取消重复/订单不存在/超时取消

### 任务 5：UserController 集成测试
- `user-service/src/test/java/com/minimall/user/controller/UserControllerTest.java`（按 4.4）
- 3 个测试：注册合法/空用户名/密码太短

### 任务 6：运行测试
```bash
cd ~/Documents/java_project/mini-mall
~/tools/apache-maven-3.9.16/bin/mvn test
```

---

## 六、验收清单

| # | 验收项 | 验证方法 | 预期结果 |
|---|--------|----------|----------|
| 1 | 编译通过 | `mvn test-compile` | BUILD SUCCESS |
| 2 | JwtUtil 测试通过 | `mvn test -pl user-service -Dtest=JwtUtilTest` | 5 个测试全 PASS |
| 3 | UserService 测试通过 | `mvn test -pl user-service -Dtest=UserServiceTest` | 5 个测试全 PASS |
| 4 | UserController 测试通过 | `mvn test -pl user-service -Dtest=UserControllerTest` | 3 个测试全 PASS |
| 5 | OrderService 测试通过 | `mvn test -pl order-service -Dtest=OrderServiceTest` | 6 个测试全 PASS |
| 6 | 全部测试通过 | `mvn test` | 全部 PASS，0 失败 |

---

## 七、踩坑预判

| # | 陷阱 | 症状 | 解决方案 |
|---|------|------|----------|
| 1 | **没加 spring-boot-starter-test** | 编译报 `JUnit` 类找不到 | pom.xml 加依赖（scope=test） |
| 2 | **@MockBean vs @Mock 混用** | Spring 容器启动失败 | @Mock 用于纯单元测试，@MockBean 用于 @SpringBootTest |
| 3 | **集成测试启动全量 Spring** | 测试很慢 | @SpringBootTest 会启动完整容器，正常现象。可用 @WebMvcTest 只加载 Controller 层加速 |
| 4 | **SecurityConfig 拦截测试请求** | UserControllerTest 返回 401 | @SpringBootTest 加载了 SecurityConfig，可能拦截。可用 @MockBean 替换 SecurityFilterChain 放行全部 |
| 5 | **Mockito 语法错误** | `when()` 报 NullPointerException | Mock 对象必须先声明再使用，`@InjectMocks` 不能注入非 @Mock 的依赖 |
| 6 | **Test 目录结构不对** | 找不到测试类 | 测试代码放 `src/test/java/`，包结构与 main 一致 |
| 7 | **assertThrows 语法** | 编译报错 | `assertThrows(Class, () -> { ... })`，Lambda 中放被测代码 |

---

## 八、核心理解点（写完自问）

1. **@Mock 和 @MockBean 的区别是什么？分别用在什么场景？**
2. **为什么单元测试要 Mock 掉 Mapper 而不是连真实数据库？**
3. **@SpringBootTest 启动了什么？它和 @WebMvcTest 有什么区别？**
4. **`verify(mock, never()).method()` 验证的是什么？为什么重要？**
5. **`assertThrows` 和 `@Test(expected=...)` 的区别是什么？**

---

> 教学文档版本：v1.0 | 生成日期：2026-09-17

> AI生成