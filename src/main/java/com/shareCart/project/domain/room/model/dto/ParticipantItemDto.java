package com.shareCart.project.domain.room.model.dto;

import lombok.*;

public class ParticipantItemDto {
    @Getter
    @AllArgsConstructor
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    public static class AllocateRequest{
        private Long itemId;
        private Integer quantity;
        //        private Long participantId;
    }
}
