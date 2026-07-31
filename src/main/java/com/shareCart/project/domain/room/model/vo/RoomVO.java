package com.shareCart.project.domain.room.model.vo;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class RoomVO {
    private Long id;
    private Long townId;
    private Long hostId;
    private String marketName;
    private String meetPlace;
    private LocalDateTime meetAt;
    private Integer maxParticipants;
}
