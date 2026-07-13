package com.shareCart.project.domain.town.model.dto;

import lombok.*;
import java.math.BigDecimal;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TownDto {
    private String regionId;
    private String townName;
    private String sido;
    private String sigungu;
    private String emd;
    private BigDecimal latitude;
    private BigDecimal longitude;
}
