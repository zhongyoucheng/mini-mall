package com.minimall.user.mapper;

import com.minimall.user.model.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserMapper {

    int insert(User user);

    User findById(@Param("id") Long id);

    User findByUsername(@Param("username") String username);

    int update(User user);

    int deleteById(@Param("id") Long id);
}
