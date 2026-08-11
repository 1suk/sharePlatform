package com.shareCart.project;

import com.shareCart.project.global.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class JunitTest {

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Test
    void generateCsvTokens() {
        for (long i = 10; i <= 60; i++) {
            Long userId = i;
            String email = "user" + i + "@test.com";
            String role = "ROLE_USER";
            long tokenVersion = 0L;

            String token = jwtTokenProvider.generateAccessToken(email, role, tokenVersion);

            System.out.println(userId + "," + token);
        }
    }
}