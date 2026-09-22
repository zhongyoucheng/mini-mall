---
AIGC:
  ContentProducer: '001191110102MAD55U9H0F10002'
  ContentPropagator: '001191110102MAD55U9H0F10002'
  Label: '1'
  ProduceID: '1202efae-22cf-4ee5-91ac-c27304e3fba2'
  PropagateID: '1202efae-22cf-4ee5-91ac-c27304e3fba2'
  ReservedCode1: '902c5165-cf3a-45c4-8912-8690bb5ffed4'
  ReservedCode2: '902c5165-cf3a-45c4-8912-8690bb5ffed4'
---

# 阶段九 Day 3：用户服务（注册 + 登录 + JWT 认证）

> mini-mall 电商订单系统 | 教学文档 v1.0 | 2026-09-16

---

## 一、今日目标

完成 user-service 的核心业务能力：
1. **用户注册**：校验用户名唯一 → BCrypt 加密密码 → 入库
2. **用户登录**：校验密码 → 签发 JWT Token
3. **JWT 认证拦截**：Spring Security 整合 JWT，除白名单外所有接口需带 Token
4. **统一 401/403 JSON 响应**：认证失败返回 JSON 而非默认 HTML 登录页

```
┌──────────┐   POST /users/register   ┌─────────────┐
│  前端/Postman │ ───────────────────────▶ │ user-service │
└──────────┘                          │  (8081)     │
     │  POST /users/login                │             │
     │ ─────────────────────────────────▶ │ 校验密码     │
     │                                    │ 签发 JWT     │
     │ ◀───────── {token: "xxx"} ──────── │             │
     │                                    │             │
     │  GET /users/me (Authorization: Bearer xxx)       │
     │ ─────────────────────────────────▶ │ JwtFilter    │
     │                                    │ 验签→放行    │
     │ ◀───────── {用户信息} ───────────── │             │
```

---

## 二、概念讲解（回顾 + 新知识点）

### 2.1 JWT 三段结构（回顾）

JWT = `Header.Payload.Signature`，用 `.` 分隔：

| 段 | 内容 | 可读性 |
|----|------|--------|
| Header | `{"alg":"HS256"}` 算法声明 | Base64 可解码，任何人可看 |
| Payload | `{"sub":"alice","role":"USER","exp":...}` 业务数据 | Base64 可解码，任何人可看 |
| Signature | `HMACSHA256(base64(Header).base64(Payload), 密钥)` | 只有持有密钥的服务端能生成 |

**关键认知**：Payload 不是加密！只是 Base64 编码。不能把密码等敏感信息放 JWT。Signature 才是防篡改的核心——没有密钥无法伪造。

### 2.2 BCrypt 密码加密

密码绝不明文存储。用 `BCryptPasswordEncoder`：
- **单向哈希**：无法从密文反推明文
- **内置盐值**：相同密码每次加密结果不同（防彩虹表攻击）
- **慢哈希**：故意设计得慢（约 100ms），增加暴力破解成本

类比 Node.js：`bcrypt.hashSync(pwd, 10)` / `bcrypt.compareSync(pwd, hash)`。

### 2.3 Spring Security + JWT 整合架构

Spring Security 是 Filter 链。我们插入一个自定义 Filter 到链中：

```
请求 → JwtAuthenticationFilter → Security 其他过滤器 → Controller
        │
        ├─ 带合法 Token → 解析 → SecurityContextHolder 放入认证信息 → 放行
        ├─ 没带 Token → 直接放行（让 Security 判断：白名单放行/非白名单 401）
        └─ Token 无效 → 直接放行（同上）
```

### 2.4 认证 vs 授权（回顾）

- **认证（Authentication）**：你是谁？JwtAuthenticationFilter 验签后回答
- **授权（Authorization）**：你能干什么？@PreAuthorize / 授权规则回答
- 401 = 没登录；403 = 登录了但没权限

---

## 三、参考代码

### 3.1 JwtUtil 工具类

**文件：`user-service/src/main/java/com/minimall/user/util/JwtUtil.java`**

```java
package com.minimall.user.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtil {

    private final SecretKey key;
    private final long expiration;

    public JwtUtil(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.expiration}") long expiration) {
        // 密钥字符串至少 32 字节，Keys.hmacShaKeyFor 要求
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiration = expiration;
    }

    /**
     * 生成 Token
     * @param userId   用户ID
     * @param username 用户名
     * @param role     角色（USER/ADMIN）
     */
    public String generateToken(Long userId, String username, String role) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + expiration);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("role", role)
                .issuedAt(now)
                .expiration(exp)
                .signWith(key)
                .compact();
    }

    /**
     * 解析 Token，失败抛异常
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 验证 Token 是否有效（签名+过期）
     */
    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
```

> 注意：JJWT 0.12.6 新版 API。旧版 `setSubject()`/`parseClaimsJws()` 已废弃。

### 3.2 DTO（请求体）

**文件：`user-service/src/main/java/com/minimall/user/dto/RegisterRequest.java`**

```java
package com.minimall.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 50, message = "用户名长度 3-50")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 100, message = "密码长度至少 6 位")
    private String password;

    private String nickname;
    private String email;
    private String phone;
}
```

#### 文件：`user-service/src/main/java/com/minimall/user/dto/LoginRequest.java`

```java
package com.minimall.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginRequest {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;
}
```

#### 文件：`user-service/src/main/java/com/minimall/user/dto/LoginResponse.java`

```java
package com.minimall.user.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LoginResponse {
    private String token;
    private Long userId;
    private String username;
    private String nickname;
    private String role;
}
```

### 3.3 统一响应 Result（复用 Day 2 的 Map 风格，或定义 Result 类）

为了保持简洁并便于扩展，今天引入统一响应类：

**文件：`user-service/src/main/java/com/minimall/user/common/Result.java`**

```java
package com.minimall.user.common;

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

**文件：`user-service/src/main/java/com/minimall/user/common/BusinessException.java`**

```java
package com.minimall.user.common;

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

**文件：`user-service/src/main/java/com/minimall/user/common/GlobalExceptionHandler.java`**

```java
package com.minimall.user.common;

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

### 3.4 UserService

**文件：`user-service/src/main/java/com/minimall/user/service/UserService.java`**

```java
package com.minimall.user.service;

import com.minimall.user.common.BusinessException;
import com.minimall.user.dto.LoginRequest;
import com.minimall.user.dto.LoginResponse;
import com.minimall.user.dto.RegisterRequest;
import com.minimall.user.mapper.UserMapper;
import com.minimall.user.model.User;
import com.minimall.user.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    /** 注册 */
    public void register(RegisterRequest req) {
        // 1. 查重
        if (userMapper.findByUsername(req.getUsername()) != null) {
            throw new BusinessException("用户名已存在");
        }
        // 2. 加密 + 入库
        User user = new User();
        user.setUsername(req.getUsername());
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setNickname(req.getNickname());
        user.setEmail(req.getEmail());
        user.setPhone(req.getPhone());
        user.setRole("USER");
        userMapper.insert(user);
    }

    /** 登录 */
    public LoginResponse login(LoginRequest req) {
        // 1. 查用户
        User user = userMapper.findByUsername(req.getUsername());
        if (user == null) {
            throw new BusinessException(401, "用户名或密码错误");
        }
        // 2. 验密码
        if (!passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            throw new BusinessException(401, "用户名或密码错误");
        }
        // 3. 签发 Token
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());
        return new LoginResponse(token, user.getId(), user.getUsername(), user.getNickname(), user.getRole());
    }
}
```

### 3.5 UserController 改造

**文件：`user-service/src/main/java/com/minimall/user/controller/UserController.java`**

```java
package com.minimall.user.controller;

import com.minimall.user.common.BusinessException;
import com.minimall.user.common.Result;
import com.minimall.user.dto.LoginRequest;
import com.minimall.user.dto.LoginResponse;
import com.minimall.user.dto.RegisterRequest;
import com.minimall.user.mapper.UserMapper;
import com.minimall.user.model.User;
import com.minimall.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final UserMapper userMapper;

    /** 注册（白名单） */
    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterRequest req) {
        userService.register(req);
        return Result.success(null);
    }

    /** 登录（白名单） */
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        return Result.success(userService.login(req));
    }

    /** 当前登录用户信息（需 Token） */
    @GetMapping("/me")
    public Result<User> me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = auth.getName();
        User user = userMapper.findByUsername(username);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        user.setPassword(null);
        return Result.success(user);
    }

    /** 查询用户（保留 Day 2，需 Token） */
    @GetMapping("/{id}")
    public Result<User> getById(@PathVariable Long id) {
        User user = userMapper.findById(id);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        user.setPassword(null);
        return Result.success(user);
    }

    /** 查重名（保留 Day 2，白名单，注册时前端要调） */
    @GetMapping("/check/{username}")
    public Result<Boolean> checkUsername(@PathVariable String username) {
        return Result.success(userMapper.findByUsername(username) != null);
    }
}
```

### 3.6 JwtAuthenticationFilter（核心）

**文件：`user-service/src/main/java/com/minimall/user/filter/JwtAuthenticationFilter.java`**

```java
package com.minimall.user.filter;

import com.minimall.user.util.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 1. 从 Header 提取 Token
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                // 2. 验签 + 解析
                Claims claims = jwtUtil.parseToken(token);
                Long userId = Long.valueOf(claims.getSubject());
                String username = claims.get("username", String.class);
                String role = claims.get("role", String.class);

                // 3. 构造认证对象放入 SecurityContext
                // ROLE_ 前缀是 Spring Security 约定，@PreAuthorize("hasRole('ADMIN')") 匹配
                var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
                var authentication = new UsernamePasswordAuthenticationToken(username, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (Exception e) {
                // Token 无效：不设置认证，继续放行（后续 Security 会返回 401）
            }
        }

        // 4. 继续过滤链（无论有无 Token 都放行，由 Spring Security 决定结果）
        filterChain.doFilter(request, response);

        // 5. 清理线程变量（Tomcat 线程池复用，防止身份串号）
        SecurityContextHolder.clearContext();
    }
}
```

### 3.7 SecurityConfig 改造（核心）

**文件：`user-service/src/main/java/com/minimall/user/config/SecurityConfig.java`**

```java
package com.minimall.user.config;

import com.minimall.user.common.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minimall.user.filter.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(c -> c.disable())
            // 关闭 Session（JWT 无状态认证）
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                // 白名单：注册 / 登录 / 健康检查 / 查重
                .requestMatchers("/users/register", "/users/login", "/users/check/**", "/users/health").permitAll()
                // 其他请求需要认证
                .anyRequest().authenticated())
            // 未认证时返回统一 JSON（而非默认 HTML 登录页 / 302）
            .exceptionHandling(e -> e.authenticationEntryPoint((request, response, ex) -> {
                response.setStatus(401);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(objectMapper.writeValueAsString(
                        Result.error(401, "未登录或 Token 无效")));
            }))
            // 把 JWT Filter 插在 UsernamePasswordAuthenticationFilter 之前
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

### 3.8 application.yml 确认

Day 1 已配好 JWT 密钥，确认无误：

```yaml
jwt:
  secret: mini-mall-jwt-secret-key-must-be-at-least-32-bytes-long   # ≥32 字节
  expiration: 86400000   # 24 小时（毫秒）
```

---

## 四、练习任务清单

### 任务 1：创建 JwtUtil
- `util/JwtUtil.java`（按 3.1）

### 任务 2：创建 DTO
- `dto/RegisterRequest.java`、`dto/LoginRequest.java`、`dto/LoginResponse.java`（按 3.2）

### 任务 3：创建统一响应类
- `common/Result.java`、`common/BusinessException.java`、`common/GlobalExceptionHandler.java`（按 3.3）

### 任务 4：创建 UserService
- `service/UserService.java`（按 3.4）

### 任务 5：改造 UserController
- 替换为 3.5 版本（register/login/me 接口）

### 任务 6：创建 JwtAuthenticationFilter
- `filter/JwtAuthenticationFilter.java`（按 3.6）

### 任务 7：改造 SecurityConfig
- 替换为 3.7 版本（PasswordEncoder + STATELESS + 白名单 + 401 JSON + JWT Filter）

### 任务 8：编译 + 重启 + 验证

```bash
cd ~/Documents/java_project/mini-mall
~/tools/apache-maven-3.9.16/bin/mvn clean compile

# 启动 user-service（注意清除 SERVER_PORT）
cd user-service
env -u SERVER_PORT DB_PASSWORD=zyC191380!! ~/tools/apache-maven-3.9.16/bin/mvn spring-boot:run
```

---

## 五、验收清单

| # | 验收项 | 验证方法 | 预期结果 |
|---|--------|----------|----------|
| 1 | 编译通过 | `mvn clean compile` | BUILD SUCCESS |
| 2 | 启动成功 | 控制台 | Started + 注册 Nacos |
| 3 | 注册成功 | `curl -X POST /users/register` | code=200，数据库新增用户 |
| 4 | 重复注册拦截 | 再注册同用户名 | code=400 "用户名已存在" |
| 5 | 登录成功 | `curl -X POST /users/login` | 返回 token + 用户信息 |
| 6 | 密码错误 | 错误密码登录 | code=401 "用户名或密码错误" |
| 7 | 带 Token 查 /me | Authorization: Bearer xxx | code=200 用户信息 |
| 8 | 不带 Token 查 /me | 无 Header | HTTP 401 + JSON |
| 9 | Token 篡改 | 改 Token 最后一位 | HTTP 401 + JSON |
| 10 | 白名单放行 | 无 Token 调 /users/health | code=200 |

---

## 六、踩坑预判

| # | 陷阱 | 症状 | 解决方案 |
|---|------|------|----------|
| 1 | **密钥 <32 字节** | `Keys.hmacShaKeyFor` 抛 `WeakKeyException` | secret 必须 ≥32 字节 |
| 2 | **JJWT 旧版 API** | 编译报 `setSubject()` 已废弃/不存在 | 用 0.12.6 新版：`Jwts.builder().subject()...signWith(key)` |
| 3 | **SecurityConfig 没注入 Filter** | Filter 不生效，所有接口仍 401 | `addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)` |
| 4 | **忘记 STATELESS** | 登录后仍生成 JSESSIONID | `sessionCreationPolicy(SessionCreationPolicy.STATELESS)` |
| 5 | **注册接口被 Security 拦截** | 注册返回 401 | SecurityConfig 白名单加 `/users/register` |
| 6 | **@Valid 校验失败返回 500** | 字段为空报 500 | GlobalExceptionHandler 加 `MethodArgumentNotValidException` 处理 |
| 7 | **BCrypt 每次加密结果不同** | 数据库密码与预期不符 | 这是正常现象，登录用 `matches()` 验证不是 `equals()` |
| 8 | **-parameters 未配置** | `@PathVariable` 报错 | 父 POM 已配 `<parameters>true</parameters>`，勿删 |

---

## 七、核心理解点（写完自问）

1. **为什么密码用 BCrypt 而不是 MD5/SHA-256？**
2. **JWT 的 Payload 能放密码吗？为什么？**
3. **JwtAuthenticationFilter 为什么"没带 Token 也放行"而不是直接 401？**
4. **STATELESS 是什么意思？不用它会怎样？**
5. **@PreAuthorize("hasRole('ADMIN')") 为什么 Filter 里要加 `ROLE_` 前缀？**

---

> 教学文档版本：v1.0 | 生成日期：2026-09-16

> AI生成