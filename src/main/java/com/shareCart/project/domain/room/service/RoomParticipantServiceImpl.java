package com.shareCart.project.domain.room.service;

import com.shareCart.project.domain.room.model.mapper.ParticipantItemMapper;
import com.shareCart.project.domain.room.model.mapper.RoomItemMapper;
import com.shareCart.project.domain.room.model.mapper.RoomMapper;
import com.shareCart.project.domain.room.model.mapper.RoomParticipantMapper;
import com.shareCart.project.domain.room.model.vo.RoomParticipantVO;
import com.shareCart.project.domain.room.model.vo.RoomVO;
import com.shareCart.project.domain.user.model.mapper.UserMapper;
import com.shareCart.project.domain.user.model.vo.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RoomParticipantServiceImpl implements RoomParticipantService {
    private final UserMapper userMapper;
    private final RoomParticipantMapper roomParticipantMapper;
    private final RoomMapper roomMapper;
    private final ParticipantItemMapper participantItemMapper;
    private final RoomItemMapper roomItemMapper;

    public void joinRoom(Long roomId, String email) {
        UserVO user = userMapper.findByEmail(email);
        if(user == null){
            throw new IllegalArgumentException("존재하지 않는 사용자입니다.");
        }

        RoomParticipantVO existing = roomParticipantMapper.findByRoomIdAndUserId(roomId, user.getId());
        if(existing != null){
            throw new IllegalStateException("이미 참여한 방입니다.");
        }

        RoomVO room = roomMapper.findRoomById(roomId);
        if (room == null) {
            throw new IllegalArgumentException("존재하지 않는 방입니다.");
        }

        int currentCount = roomParticipantMapper.countByRoomId(roomId);
        if(currentCount >= room.getMaxParticipants()){
            throw new IllegalStateException("참여 인원인 가득 찼습니다.");
        }

        RoomParticipantVO participant = RoomParticipantVO.builder()
                .roomId(roomId)
                .userId(user.getId())
                .role("MEMBER")
                .build();

        roomParticipantMapper.insertParticipant(participant);
    }

    public void allocateItem(Long roomId, String email, Long itemId, Integer quantity) {
        UserVO user = userMapper.findByEmail(email);
        if(user == null){
            throw new IllegalArgumentException("존재하지 않는 사용자입니다.");
        }

        RoomParticipantVO participant = roomParticipantMapper.findByRoomIdAndUserId(roomId, user.getId());
        if (participant == null) {
            throw new IllegalStateException("이 방에 참여하지 않은 사용자입니다.");
        }

        Integer total_qty = roomItemMapper.findRoomItemById(itemId).getTotalQty();
        Integer alreadyAllocated = participantItemMapper.sumAllocatedByItemExcludingParticipant(roomId, itemId);
        if(alreadyAllocated + quantity > total_qty){
            throw new IllegalStateException("남은 수량을 초과했습니다.");
        }

        participantItemMapper.upsertAllocation(participant.getId(), itemId, quantity);
    }
}

