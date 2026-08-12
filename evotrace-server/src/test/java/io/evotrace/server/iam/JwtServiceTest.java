package io.evotrace.server.iam;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtServiceTest {

    private static final String SECRET = "test-secret-key-at-least-32-bytes-long!!";

    private final JwtService jwtService = new JwtService(SECRET, 12);

    @Test
    void issueAndParseRoundTrip() {
        String token = jwtService.issue("alice", "ADMIN");

        assertEquals("alice", jwtService.parseUsername(token));
        assertEquals("ADMIN", jwtService.parseRole(token));
    }

    @Test
    void parseRoleFallsBackToUserWhenClaimMissing() {
        // 用不含 role claim 的第三方构造 token（模拟旧版 token）
        String token = io.jsonwebtoken.Jwts.builder()
                .subject("bob")
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .compact();

        assertEquals("bob", jwtService.parseUsername(token));
        assertEquals("USER", jwtService.parseRole(token));
    }

    @Test
    void rejectsTokenSignedWithDifferentKey() {
        JwtService other = new JwtService("another-secret-key-at-least-32-bytes!!", 12);
        String foreignToken = other.issue("alice", "ADMIN");

        assertThrows(Exception.class, () -> jwtService.parseUsername(foreignToken));
    }

    @Test
    void rejectsExpiredToken() {
        JwtService shortLived = new JwtService(SECRET, 0);
        String token = shortLived.issue("alice", "ADMIN");

        assertThrows(Exception.class, () -> jwtService.parseUsername(token));
    }

    @Test
    void rejectsGarbageToken() {
        assertThrows(Exception.class, () -> jwtService.parseUsername("not.a.jwt"));
    }
}
