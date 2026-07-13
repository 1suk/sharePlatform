package com.shareCart.project.domain.user.controller;

import com.shareCart.project.domain.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.shareCart.project.domain.user.model.dto.SignupDto;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
//@CrossOrigin(origins = "*")
public class UserController {

    private final UserService userService;

    @PostMapping("/signup")
    public ResponseEntity<SignupDto.Response> signup(@RequestBody SignupDto.Request request){
        SignupDto.Response response = userService.signup(request);
        return ResponseEntity.ok(response);
    }
}
