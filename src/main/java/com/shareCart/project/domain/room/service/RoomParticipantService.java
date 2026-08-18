package com.shareCart.project.domain.room.service;

import com.shareCart.project.domain.room.model.vo.ParticipantItemVO;

import java.util.List;

public interface RoomParticipantService {
    void joinRoom(Long roomId, String email);
    void allocateItem(Long roomId, String email, Long itemId, String requestId, Integer step);
}
