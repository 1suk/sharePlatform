package com.shareCart.project.domain.room.model.vo;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoomParticipantVO {
    private Long id;
    private Long roomId;
    private Long userId;
    private String role;
    private String settlementStatus;
    private LocalDateTime joinedAt;
}

