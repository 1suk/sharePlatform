package com.shareCart.project.domain.auth.model.dto;

import lombok.*;

public class LoginDto {

    @Getter
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    public static class Request {
        private String email;
        private String password;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    public static class Response {
        private Long userId;
        private String name;
        private String accessToken;
        private String refreshToken;
    }
}
