package com.shareCart.project.domain.town.model.vo;

import lombok.*;
import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TownVO {
    private Long id;
    private String regionId;
    private String townName;
    private String sido;
    private String sigungu;
    private String emd;
    private BigDecimal latitude;
    private BigDecimal longitude;
}
