package com.minimall.user.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

@Component
public class JwtUtil {

    private static final Map<String, Integer> SIGNATURE_BYTES = Map.of(
            "HS256", 32,
            "HS384", 48,
            "HS512", 64
    );

    private final SecretKey key;
    private final long expiration;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JwtUtil(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.expiration}") long expiration) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiration = expiration;
    }

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

    public Claims parseToken(String token) {
        // 严格校验签名长度，防止末尾追加垃圾字符被 JJWT 静默忽略
        assertSignatureLength(token);
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void assertSignatureLength(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("JWT 必须由 3 部分组成");
        }
        try {
            JsonNode header = objectMapper.readTree(Decoders.BASE64URL.decode(parts[0]));
            String alg = header.has("alg") ? header.get("alg").asText() : null;
            Integer expectedBytes = SIGNATURE_BYTES.get(alg);
            if (expectedBytes == null) {
                throw new IllegalArgumentException("不支持的签名算法: " + alg);
            }
            // Base64URL 无 padding 时，N 字节编码后字符数为 ceil(N * 8 / 6)
            int expectedChars = (expectedBytes * 8 + 5) / 6;
            if (parts[2].length() != expectedChars) {
                throw new IllegalArgumentException("JWT 签名长度不合法");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("JWT 格式错误", e);
        }
    }
}
