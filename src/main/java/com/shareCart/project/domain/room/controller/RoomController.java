package com.shareCart.project.domain.room.controller;

import com.shareCart.project.domain.room.model.dto.ParticipantItemDto;
import com.shareCart.project.domain.room.model.dto.RoomDto;
import com.shareCart.project.domain.room.service.RoomItemService;
import com.shareCart.project.domain.room.service.RoomParticipantService;
import com.shareCart.project.domain.room.service.RoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
public class RoomController {
    private final RoomService roomService;
    private final RoomParticipantService roomParticipantService;
    private final RoomItemService roomItemService;

    @PostMapping("/create")
    public ResponseEntity<Void> createRoom(
            @AuthenticationPrincipal String email,
            @RequestBody RoomDto.Create createDto){
        roomService.createRoom(email, createDto);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/{roomId}/join")
    public ResponseEntity<Void> joinRoom(
            @PathVariable Long roomId,
            @AuthenticationPrincipal String email){
        roomParticipantService.joinRoom(roomId, email);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{roomId}/items/allocate")
    public ResponseEntity<Void> allocateItem(
            @PathVariable Long roomId,
            @AuthenticationPrincipal String email,
            @RequestBody ParticipantItemDto.AllocateRequest request){

        roomParticipantService.allocateItem(roomId, email, request.getItemId(), request.getQuantity());
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/{roomId}/items/{roomItemId}")
    public ResponseEntity<Void> updateItemDetails(
            @PathVariable Long roomId,
            @PathVariable Long roomItemId,
            @AuthenticationPrincipal String email,
            @RequestBody RoomDto.UpdateItemDetailsRequest request) {
        roomItemService.updateItemDetails(roomId, roomItemId, email, request.getUnit(), request.getTotalQty());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/list")
    public ResponseEntity<List<RoomDto.Summary>> getRoomList(
            @AuthenticationPrincipal String email){
        List<RoomDto.Summary> response = roomService.getRoomList(email);
        return ResponseEntity.ok(response);
    }
}
