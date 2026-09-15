package com.shareCart.project.domain.room.service;

import com.shareCart.project.domain.room.model.dto.RoomDto;

import java.util.List;

public interface RoomService {
    void createRoom(String eamil, RoomDto.Create createDto);
    List<RoomDto.Summary> getRoomList(String email);
}
