package com.shareCart.project.domain.room.model.vo;

import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class RoomItemVO {
    private Long id;
    private Long roomId;
    private String itemName;
    private Integer ocrPrice;
    private Integer actualPrice;
    private Integer totalQty;
    private String unit;
}
