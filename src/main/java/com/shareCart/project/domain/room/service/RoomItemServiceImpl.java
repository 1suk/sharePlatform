package com.shareCart.project.domain.room.service;

import com.shareCart.project.domain.room.model.mapper.RoomItemMapper;
import com.shareCart.project.domain.room.model.mapper.RoomMapper;
import com.shareCart.project.domain.room.model.vo.RoomItemVO;
import com.shareCart.project.domain.room.model.vo.RoomVO;
import com.shareCart.project.domain.user.model.mapper.UserMapper;
import com.shareCart.project.domain.user.model.vo.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RoomItemServiceImpl implements RoomItemService {
    private final RoomItemMapper roomItemMapper;
    private final UserMapper userMapper;
    private final RoomMapper roomMapper;

    public void updateItemDetails(Long roomId, Long roomItemId, String email, String unit, Integer totalQty){
        UserVO user = userMapper.findByEmail(email);
        if (user == null) {
            throw new IllegalArgumentException("존재하지 않는 사용자입니다.");
        }

        RoomVO room = roomMapper.findRoomById(roomId);
        if (room == null) {
            throw new IllegalArgumentException("존재하지 않는 방입니다.");
        }

        if(!user.getId().equals(room.getHostId())){
            throw new IllegalStateException("방장만 품목 정보를 수정할 수 있습니다.");
        }

        RoomItemVO item = roomItemMapper.findRoomItemById(roomItemId);
        if (item == null || !item.getRoomId().equals(roomId)) {
            throw new IllegalArgumentException("존재하지 않는 품목입니다.");
        }

        roomItemMapper.updateItemDetails(roomItemId, unit, totalQty);
    }
}
