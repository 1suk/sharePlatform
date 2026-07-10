package com.shareCart.project.domain.auth.model.vo;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
public class UserVO {
    private Long id;
    private Long townId;
    private String email;
    private String name;
    private String password;
    private String role;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
