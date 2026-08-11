package com.shareCart.project.domain.room.service;

import com.shareCart.project.domain.room.model.dto.RoomDto;
import com.shareCart.project.domain.room.model.vo.RoomVO;
import org.springframework.stereotype.Service;

import java.util.List;

public interface RoomService {
    void createRoom(String eamil, RoomDto.Create createDto);
    List<RoomDto.Summary> getRoomList(String email);
}
