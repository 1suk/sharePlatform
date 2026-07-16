package com.shareCart.project.domain.auth.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

public class ReissueDto {

    @Getter
    @NoArgsConstructor
    public static class Request{
        private String refreshToken;
    }

    @Getter
    @AllArgsConstructor
    @Builder
    public static class Response{
        private String accessToken;
    }
}
