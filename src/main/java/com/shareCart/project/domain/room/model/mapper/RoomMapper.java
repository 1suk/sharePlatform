package com.shareCart.project.domain.room.model.mapper;

import com.shareCart.project.domain.room.model.dto.RoomDto;
import com.shareCart.project.domain.room.model.vo.RoomVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface RoomMapper {
    void insertRoom(RoomVO roomVO);
    void insertRoomItems(@Param("roomId") Long RoomId, @Param("items") List<RoomDto.Item> items);
}
