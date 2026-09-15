package com.shareCart.project.domain.room.service;

import com.shareCart.project.domain.room.model.dto.RoomDto;
import com.shareCart.project.domain.room.model.mapper.RoomItemMapper;
import com.shareCart.project.domain.room.model.mapper.RoomMapper;
import com.shareCart.project.domain.room.model.mapper.RoomParticipantMapper;
import com.shareCart.project.domain.room.model.vo.RoomList;
import com.shareCart.project.domain.room.model.vo.RoomParticipantVO;
import com.shareCart.project.domain.room.model.vo.RoomVO;
import com.shareCart.project.domain.user.model.mapper.UserMapper;
import com.shareCart.project.domain.user.model.vo.UserVO;
import jdk.jfr.Category;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Array;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoomServiceImpl implements RoomService {
    private final RoomMapper roomMapper;
    private final UserMapper userMapper;
    private final RoomParticipantMapper roomParticipantMapper;
    private final RoomItemMapper roomItemMapper;

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

//        roomParticipantMapper.insertParticipant(roomInfo.getId(),hostId,"HOST");
        RoomParticipantVO participant = RoomParticipantVO.builder()
                .roomId(roomInfo.getId())
                .userId(hostId)
                .role("HOST")
                .build();

        roomParticipantMapper.insertParticipant(participant);
    }

    public List<RoomDto.Summary> getRoomList(String email){
        UserVO user = userMapper.findByEmail(email);
        if (user == null) {
            throw new IllegalArgumentException("존재하지 않는 사용자입니다.");
        }

        List<RoomList> rooms = roomMapper.findRoomsByTownId(user.getTownId());
        if(rooms.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> roomIds = new ArrayList<>();

        for(RoomList rooom : rooms){
            roomIds.add(rooom.getId());
        }


        List<RoomDto.ItemSummary> items = roomItemMapper.findItemNamesByRoomIds(roomIds);
        log.debug(items.toString());

        Map<Long, List<String>> roomWithItems = new HashMap<>();
        for (RoomDto.ItemSummary item : items) {
            Long roomId = item.getRoomId();
            String itemName = item.getItemName();

            if(!roomWithItems.containsKey(roomId)){
                roomWithItems.put(roomId, new ArrayList<>());
            }

            roomWithItems.get(roomId).add(itemName);
        }
        log.debug(roomWithItems.toString());

        List<RoomDto.Summary> summaries = new ArrayList<>();

        for (RoomList room : rooms){
            List<String> roomItems = roomWithItems.getOrDefault(room.getId(), new ArrayList<>());

            List<String> limitedItems;
            if(roomItems.size() > 3){
                limitedItems = roomItems.subList(0,3);
            }else{
                limitedItems = roomItems;
            }

            String status = calculateStatus(room.getMeetAt(), room.getCurrentParticipants(), room.getMaxParticipants());

            summaries.add(RoomDto.Summary.builder()
                    .roomId(room.getId())
                    .marketName(room.getMarketName())
                    .meetPlace(room.getMeetPlace())
                    .meetAt(room.getMeetAt())
                    .maxParticipants(room.getMaxParticipants())
                    .currentParticipants(room.getCurrentParticipants())
                    .itemNames(limitedItems)
                    .status(status)
                    .build());
        }

        return summaries;
    }

    private String calculateStatus(LocalDateTime meetAt, int current, int max) {
        if (LocalDateTime.now().isAfter(meetAt)) return "마감";
        if (current >= max) return "모집완료";
        if (LocalDateTime.now().plusMinutes(30).isAfter(meetAt)) return "마감임박";
        return "모집중";
    }
}

