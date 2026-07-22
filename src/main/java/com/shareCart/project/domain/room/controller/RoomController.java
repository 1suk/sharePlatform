package com.shareCart.project.domain.room.controller;

import com.shareCart.project.domain.room.model.dto.RoomDto;
import com.shareCart.project.domain.room.service.RoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
public class RoomController {
    private final RoomService roomService;

    @PostMapping("/create")
    public ResponseEntity<Void> createRoom(
            @AuthenticationPrincipal String email,
            @RequestBody RoomDto.Create createDto){
        roomService.createRoom(email, createDto);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
