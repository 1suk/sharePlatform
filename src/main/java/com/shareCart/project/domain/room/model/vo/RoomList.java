package com.shareCart.project.domain.room.model.vo;

import java.time.LocalDateTime;

import lombok.*;

@Getter
@AllArgsConstructor
@Builder
public class RoomList {
    private Long id;
    private String marketName;
    private String meetPlace;
    private LocalDateTime meetAt;
    private Integer maxParticipants;
    private Integer currentParticipants; //집계를 위해 VO 만듬
}
