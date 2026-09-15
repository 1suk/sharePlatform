package com.shareCart.project.domain.room.model.dto;

import lombok.*;

import java.time.LocalDateTime;
import java.util.List;


public class RoomDto {

    @Getter
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    @AllArgsConstructor
    public static class Create{
        private Long id;
        private Long townId;
        private String marketName;
        private String meetPlace;
        private LocalDateTime meetAt;

        private List<Item> items;
    }

    @Getter
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    @AllArgsConstructor
    public static class Item{
        private String itemName;
    }

    @Getter
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    @AllArgsConstructor
    public static class UpdateItemDetailsRequest {
        private String unit;
        private Integer stepQty;
        private Integer totalQty;
    }


    @Getter
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    @AllArgsConstructor
    @Builder
    public static class Summary{
        private Long roomId;
        private String marketName;
        private String meetPlace;
        private LocalDateTime meetAt;
        private Integer currentParticipants;
        private Integer maxParticipants;
        private List<String> itemNames;
        private String status;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ItemSummary{
        private Long roomId;
        private String itemName;
    }
}
