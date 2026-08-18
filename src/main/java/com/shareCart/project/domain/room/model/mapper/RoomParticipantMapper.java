package com.shareCart.project.domain.room.model.mapper;

import com.shareCart.project.domain.room.model.vo.ParticipantItemVO;
import com.shareCart.project.domain.room.model.vo.RoomParticipantVO;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.data.repository.query.Param;

import java.util.List;

@Mapper
public interface RoomParticipantMapper {
    int insertParticipant(RoomParticipantVO roomParticipantVO);
    int countByRoomId(Long roomId);
    RoomParticipantVO  findByRoomIdAndUserId(@Param("roomId") Long roomId, @Param("userId") Long userId);
}
