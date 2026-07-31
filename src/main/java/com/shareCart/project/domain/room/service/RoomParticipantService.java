package com.shareCart.project.domain.room.service;

public interface RoomParticipantService {
    void joinRoom(Long roomId, String email);
    void allocateItem(Long roomId, String email, Long itemId, Integer quantity);
}
