package com.shareCart.project.domain.room.service;

import com.shareCart.project.domain.room.model.dto.RoomDto;
import com.shareCart.project.domain.room.model.vo.RoomVO;
import org.springframework.stereotype.Service;

public interface RoomService {
    void createRoom(String eamil, RoomDto.Create createDto);
}
