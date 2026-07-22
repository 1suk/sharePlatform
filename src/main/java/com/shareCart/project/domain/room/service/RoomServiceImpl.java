package com.shareCart.project.domain.room.service;

import com.shareCart.project.domain.room.model.dto.RoomDto;
import com.shareCart.project.domain.room.model.mapper.RoomMapper;
import com.shareCart.project.domain.room.model.vo.RoomVO;
import com.shareCart.project.domain.user.model.mapper.UserMapper;
import com.shareCart.project.domain.user.model.vo.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RoomServiceImpl implements RoomService {
    private final RoomMapper roomMapper;
    private final UserMapper userMapper;

    public void createRoom(String email, RoomDto.Create createDto) {
        UserVO user = userMapper.findByEmail(email);

        if(user == null) {
            throw new IllegalArgumentException("존재하지 않는 사용자입니다.");
        }

        Long hostId = user.getId();

        RoomVO roomInfo = RoomVO.builder()
                .townId(createDto.getTownId())
                .hostId(hostId)
                .marketName(createDto.getMarketName())
                .meetPlace(createDto.getMeetPlace())
                .meetAt(createDto.getMeetAt())
                .build();

        roomMapper.insertRoom(roomInfo);

        if(createDto.getItems() != null && !createDto.getItems().isEmpty()) {
            roomMapper.insertRoomItems(roomInfo.getId(), createDto.getItems());
        }
    }
}
