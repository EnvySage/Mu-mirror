package org.xianshen.mumirrorb.common.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.xianshen.mumirrorb.mapper.UserMapper;

/**
 * 用户详情服务实现（Spring Security 需要）
 */
@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserMapper userMapper;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // 查询用户
        LambdaQueryWrapper<org.xianshen.mumirrorb.pojo.DO.User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(org.xianshen.mumirrorb.pojo.DO.User::getUsername, username);
        org.xianshen.mumirrorb.pojo.DO.User user = userMapper.selectOne(wrapper);

        if (user == null) {
            throw new UsernameNotFoundException("用户不存在：" + username);
        }

        // 返回 UserDetails（不需要权限）
        return User.builder()
                .username(user.getUsername())
                .password(user.getPasswordHash())
                .authorities("ROLE_USER")
                .build();
    }
}
