package com.shareCart.project.domain.town.model.mapper;

import com.shareCart.project.domain.town.model.vo.TownVO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TownMapper {
    int insertTown(TownVO townVO);
    TownVO findTownByRegionId(String regionId);
}
