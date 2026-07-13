package com.shareCart.project.domain.user.service;

import com.shareCart.project.domain.town.model.dto.TownDto;
import com.shareCart.project.domain.town.model.mapper.TownMapper;
import com.shareCart.project.domain.town.model.vo.TownVO;
import com.shareCart.project.domain.user.model.dto.SignupDto;
import com.shareCart.project.domain.user.model.mapper.UserMapper;
import com.shareCart.project.domain.user.model.vo.UserVO;
import lombok.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Getter
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Service
public class UserService {

    private final UserMapper userMapper;
    private final TownMapper townMapper;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public SignupDto.Response signup(SignupDto.Request request) {
        if (userMapper.findByEmail(request.getEmail()) != null){
            throw new IllegalStateException("이미 가입된 이메일입니다");
        }

        Long townId = resolvedTownId(request.getTown());
        String password = passwordEncoder.encode(request.getPassword());

        UserVO userVO = UserVO.builder()
                .townId(townId)
                .email(request.getEmail())
                .name(request.getName())
                .password(password)
                .phone(request.getPhone())
                .build();

        userMapper.insertUser(userVO);

        return SignupDto.Response.builder()
                .userId(userVO.getId())
                .name(userVO.getName())
                .build();
    }

    private Long resolvedTownId(TownDto townDto) {
        TownVO existingTown = townMapper.findTownByRegionId(townDto.getRegionId());

        if(existingTown != null){
            return existingTown.getId();
        }

        TownVO newTown = TownVO.builder()
                .regionId(townDto.getRegionId())
                .townName(townDto.getTownName())
                .sido(townDto.getSido())
                .sigungu(townDto.getSigungu())
                .emd(townDto.getEmd())
                .latitude(townDto.getLatitude())
                .longitude(townDto.getLongitude())
                .build();

        try{
            townMapper.insertTown(newTown);
            return newTown.getId();
        }catch(DuplicateKeyException e){
            return townMapper.findTownByRegionId(townDto.getRegionId()).getId();
        }
    }

}
