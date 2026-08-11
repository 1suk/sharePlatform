package com.shareCart.project.domain.room.model.mapper;

import com.shareCart.project.domain.room.model.dto.RoomDto;
import com.shareCart.project.domain.room.model.vo.RoomItemVO;
import com.shareCart.project.domain.room.model.vo.RoomList;
import com.shareCart.project.domain.room.model.vo.RoomVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface RoomMapper {
    void insertRoom(RoomVO roomVO);
    void insertRoomItems(@Param("roomId") Long roomId, @Param("items") List<RoomDto.Item> items);
    RoomVO findRoomById(Long roomId);
    List<RoomList> findRoomsByTownId(Long townId);
    int increaseParticipants(Long roomId);
    RoomVO findRoomByIdForUpdate(Long roomId);
}
