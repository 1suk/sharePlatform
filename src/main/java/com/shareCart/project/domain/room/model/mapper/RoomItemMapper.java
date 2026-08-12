package com.shareCart.project.domain.room.model.mapper;

import com.shareCart.project.domain.room.model.vo.RoomItemVO;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.data.repository.query.Param;

import java.util.List;

@Mapper
public interface RoomItemMapper {
    RoomItemVO findRoomItemById(Long id);
    int updateItemDetails(
            @Param("roomItemId") Long roomItemId,
            @Param("unit") String unit,
            @Param("stepQty") Integer stepQty,
            @Param("totalQty") Integer totalQty
    );

    List<String> findItemNamesByRoomId(Long roomId);
}
