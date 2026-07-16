package com.shareCart.project.domain.auth.controller;

import com.shareCart.project.domain.auth.model.dto.LoginDto;
import com.shareCart.project.domain.auth.model.dto.ReissueDto;
import com.shareCart.project.domain.auth.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<LoginDto.Response> login(@RequestBody LoginDto.Request request){
        LoginDto.Response response = authService.login(request);
        return ResponseEntity.ok(response);
    }


    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal String email) {
        authService.logout(email);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/reissue")
    public ResponseEntity<ReissueDto.Response> reissue(@RequestBody ReissueDto.Request request){
        ReissueDto.Response response = authService.reissue(request);
        return ResponseEntity.ok(response);
    }
}
