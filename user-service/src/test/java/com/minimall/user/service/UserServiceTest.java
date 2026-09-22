package com.minimall.user.service;

import com.minimall.user.common.BusinessException;
import com.minimall.user.dto.LoginRequest;
import com.minimall.user.dto.LoginResponse;
import com.minimall.user.dto.RegisterRequest;
import com.minimall.user.mapper.UserMapper;
import com.minimall.user.model.User;
import com.minimall.user.util.JwtUtil;
import org.junit.jupiter.api.DisplayName;
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
        RegisterRequest req = new RegisterRequest();
        req.setUsername("newuser");
        req.setPassword("123456");
        req.setNickname("新用户");

        when(userMapper.findByUsername("newuser")).thenReturn(null);
        when(passwordEncoder.encode("123456")).thenReturn("$2a$10$encoded");

        userService.register(req);

        verify(userMapper, times(1)).insert(any(User.class));
    }

    @Test
    @DisplayName("注册：用户名已存在时应抛出业务异常")
    void register_existingUsername_shouldThrow() {
        RegisterRequest req = new RegisterRequest();
        req.setUsername("alice");
        req.setPassword("123456");

        User existing = new User();
        existing.setUsername("alice");
        when(userMapper.findByUsername("alice")).thenReturn(existing);

        BusinessException ex = assertThrows(BusinessException.class, () -> {
            userService.register(req);
        });

        assertEquals(400, ex.getCode());
        assertEquals("用户名已存在", ex.getMessage());

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
