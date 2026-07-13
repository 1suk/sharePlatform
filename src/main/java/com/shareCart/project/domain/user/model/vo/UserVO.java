package com.shareCart.project.domain.user.model.vo;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UserVO {
    private Long id;
    private Long townId;
    private String email;
    private String name;
    private String password;
    private String role;
    private String phone;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
