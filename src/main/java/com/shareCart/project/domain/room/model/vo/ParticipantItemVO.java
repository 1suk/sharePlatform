package com.shareCart.project.domain.room.model.vo;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ParticipantItemVO {
    private Long id;
    private Long participantId;
    private Long itemId;
    private Integer allocQuantity;
}