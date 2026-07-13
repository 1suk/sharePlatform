package com.shareCart.project.domain.user.model.dto;

import com.shareCart.project.domain.town.model.dto.TownDto;
import lombok.*;

public class SignupDto {

    @Getter
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    public static class Request {
        private String name;
        private String email;
        private String password;
        private String phone;
        private TownDto town;
    }

    @Getter
    @AllArgsConstructor
    @Builder
    public static class Response {
        private Long userId;
        private String name;
    }
}
