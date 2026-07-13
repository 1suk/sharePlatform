package com.shareCart.project.domain.user.model.mapper;

import com.shareCart.project.domain.user.model.vo.UserVO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper {
    void insertUser(UserVO userVO);
    UserVO findByEmail(String email);
}
