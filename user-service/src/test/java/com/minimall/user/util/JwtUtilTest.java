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
        JwtUtil expiredJwtUtil = new JwtUtil(SECRET, 1L);
        String token = expiredJwtUtil.generateToken(1L, "alice", "USER");

        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertFalse(expiredJwtUtil.validateToken(token));
    }
}
