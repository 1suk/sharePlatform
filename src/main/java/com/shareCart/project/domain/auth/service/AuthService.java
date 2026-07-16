package com.shareCart.project.domain.auth.service;

import com.shareCart.project.domain.auth.model.dto.LoginDto;
import com.shareCart.project.domain.auth.model.dto.ReissueDto;
import com.shareCart.project.domain.user.model.mapper.UserMapper;
import com.shareCart.project.domain.user.model.vo.UserVO;
import com.shareCart.project.global.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;
    //    private final RedisTemplate redisTemplate;


    public LoginDto.Response login(LoginDto.Request request){
        UserVO user = userMapper.findByEmail(request.getEmail());

        if(user == null){
            throw new IllegalArgumentException("존재하지 않는 이메일입니다.");
        }

        if(!passwordEncoder.matches(request.getPassword(), user.getPassword())){
            throw new IllegalArgumentException("비밀번호가 일치하지 않습니다.");
        }

        long tokenVersion = refreshTokenService.getTokenVersion(user.getEmail());

        String accessToken = jwtTokenProvider.generateAccessToken(user.getEmail(), user.getRole(), tokenVersion);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getEmail());

        refreshTokenService.save(user.getEmail(), refreshToken);

        return LoginDto.Response.builder()
                .userId(user.getId())
                .name(user.getName())
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
    }

    public void logout(String email) {
        refreshTokenService.increaseTokenVersion(email);
        refreshTokenService.delete(email);
    }

    public ReissueDto.Response reissue(ReissueDto.Request request){
        String refreshToken = request.getRefreshToken();

        Claims claims = jwtTokenProvider.validateToken(refreshToken)
                .orElseThrow(() -> new IllegalArgumentException("유효하지 않거나 만료된 refresh token입니다."));

        String email = claims.getSubject();

        if(!refreshTokenService.validate(email, refreshToken)){
            throw new IllegalArgumentException("일치하지 않는 refresh token 입니다");
        }

        UserVO user = userMapper.findByEmail(email);

        if(user == null){
            throw new IllegalArgumentException("존재하지 않는 사용자입니다.");
        }

        long tokenVersion = refreshTokenService.getTokenVersion(email);
        String newAccessToken = jwtTokenProvider.generateAccessToken(user.getEmail(), user.getRole(), tokenVersion);

        return ReissueDto.Response.builder()
                .accessToken(newAccessToken)
                .build();
    }

}
