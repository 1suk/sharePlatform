package com.shareCart.project.domain.room.service;

import com.shareCart.project.domain.room.model.mapper.ParticipantItemMapper;
import com.shareCart.project.domain.room.model.mapper.RoomItemMapper;
import com.shareCart.project.domain.room.model.mapper.RoomMapper;
import com.shareCart.project.domain.room.model.mapper.RoomParticipantMapper;
import com.shareCart.project.domain.room.model.vo.ParticipantItemVO;
import com.shareCart.project.domain.room.model.vo.RoomItemVO;
import com.shareCart.project.domain.room.model.vo.RoomParticipantVO;
import com.shareCart.project.domain.room.model.vo.RoomVO;
import com.shareCart.project.domain.user.model.mapper.UserMapper;
import com.shareCart.project.domain.user.model.vo.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RoomParticipantServiceImpl implements RoomParticipantService {
    private final UserMapper userMapper;
    private final RoomParticipantMapper roomParticipantMapper;
    private final RoomMapper roomMapper;
    private final ParticipantItemMapper participantItemMapper;
    private final RoomItemMapper roomItemMapper;
    private final DefaultRedisScript<Long> allocScript;
    private final StringRedisTemplate redisTemplate;

    private static final long DUPLICATE_REQUEST = -1L;
    private static final long NOT_ENOUGH_QTY = -2L;
    private static final long OVER_CAP = -3L;

    @Transactional
    public void joinRoom(Long roomId, String email) {
//        RoomVO room = roomMapper.findRoomByIdForUpdate(roomId);
//
//        if(room == null){
//            throw new IllegalArgumentException("존재하지 않는 방입니다.");
//        }

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

//        if(room.getCurrentParticipants() >= room.getMaxParticipants()){
//            throw new IllegalStateException("정원이 가득찬 방입니다");
//        }

//        try {
//            Thread.sleep(2000);
//        } catch (InterruptedException e) {
//
//        }

//        int currentCount = roomParticipantMapper.countByRoomId(roomId);
//        if(currentCount >= room.getMaxParticipants()){
//            throw new IllegalStateException("참여 인원인 가득 찼습니다.");
//        }

        int updatedRows = roomMapper.increaseParticipants(roomId);

        if (updatedRows == 0) {
            throw new IllegalArgumentException("정원이 가득찬 방입니다");
        }

        RoomParticipantVO participant = RoomParticipantVO.builder()
                .roomId(roomId)
                .userId(user.getId())
                .role("MEMBER")
                .build();

        roomParticipantMapper.insertParticipant(participant);
    }

    public void allocateItem(Long roomId, String email, Long itemId, String requestId,Integer step) {
        UserVO user = userMapper.findByEmail(email);
        if(user == null){
            throw new IllegalArgumentException("존재하지 않는 사용자입니다.");
        }

        RoomParticipantVO participant = roomParticipantMapper.findByRoomIdAndUserId(roomId, user.getId());
        if (participant == null) {
            throw new IllegalStateException("이 방에 참여하지 않은 사용자입니다.");
        }

        RoomItemVO item = roomItemMapper.findRoomItemById(itemId);

//        Integer total_qty = roomItemMapper.findRoomItemById(itemId).getTotalQty();
//        Integer alreadyAllocated = participantItemMapper.sumAllocatedByItemExcludingParticipant(roomId, itemId);
//        if(alreadyAllocated + quantity > total_qty){
//            throw new IllegalStateException("남은 수량을 초과했습니다.");
//        }

        if(item == null || !item.getRoomId().equals(roomId)){
            throw new IllegalArgumentException("존재하지 않는 품목입니다");
        }

        int delta = step * item.getStepQty();

        String dedupKey = "dedup:" + requestId;
        String zsetKey = "zset:" + itemId;
        String totalKey = "total:" + itemId;

        Long result = redisTemplate.execute(
                allocScript,
                List.of(dedupKey, zsetKey, totalKey),
                requestId,
                String.valueOf(participant.getId()),
                String.valueOf(delta),
                String.valueOf(item.getTotalQty())
        );

        if (result == null) {
            throw new IllegalStateException("할당 처리 중 오류가 발생했습니다.");
        }
        if (result == DUPLICATE_REQUEST) {
            return;
        }
        if (result == NOT_ENOUGH_QTY) {
            throw new IllegalStateException("본인 할당 수량이 부족합니다.");
        }
        if (result == OVER_CAP) {
            throw new IllegalStateException("남은 수량을 초과했습니다.");
        }

//        participantItemMapper.upsertAllocation(participant.getId(), itemId, quantity);
    }

//    private void restoreIfMissing(Long itemId, String zsetKey, String totalKey) {
//        if (Boolean.TRUE.equals(redisTemplate.hasKey(zsetKey))) {
//            return; // 이미 있으면 복원 필요 없음
//        }
//
//        List<ParticipantItemVO> allocations = participantItemMapper.findByItemId(itemId);
//        if (allocations.isEmpty()) return;
//
//        long total = 0;
//        for (ParticipantItemVO alloc : allocations) {
//            redisTemplate.opsForZSet().add(zsetKey, String.valueOf(alloc.getParticipantId()), alloc.getAllocQuantity());
//            total += alloc.getAllocQuantity();
//        }
//        redisTemplate.opsForValue().set(totalKey, String.valueOf(total));
//    }
}

