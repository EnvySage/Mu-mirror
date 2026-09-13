package org.xianshen.mumirrorb.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.xianshen.mumirrorb.common.security.JwtAuthenticationFilter;

/**
 * Spring Security 配置
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    /**
     * 密码编码器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 认证管理器
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    /**
     * 安全过滤器链
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // 禁用 CSRF（使用 JWT 不需要）
            .csrf(csrf -> csrf.disable())

            // 无状态会话（使用 JWT 不需要 Session）
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // 请求授权规则
            .authorizeHttpRequests(auth -> auth
                // ERROR/FORWARD/ASYNC dispatch（SseEmitter 完成/超时时 Tomcat 做 async dispatch
                // 回容器线程，或转发 /error）
                // 必须显式放行 ASYNC：JwtAuthenticationFilter 是 OncePerRequestFilter（默认
                // shouldNotFilterAsyncDispatch=true 跳过）+ STATELESS，async dispatch 时
                // SecurityContext 为空；而 Spring Security 6 的 AuthorizationFilter 默认
                // shouldFilterAllDispatcherTypes=true 仍会过滤 ASYNC → 抛 AuthorizationDeniedException，
                // 此时 SSE 响应已 committed → "Unable to handle the Spring Security Exception
                // because the response is already committed"
                .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR,
                        jakarta.servlet.DispatcherType.FORWARD,
                        jakarta.servlet.DispatcherType.ASYNC).permitAll()
                .requestMatchers("/error").permitAll()
                // 公开接口（不含 context-path）
                .requestMatchers("/auth/login", "/auth/register", "/auth/status").permitAll()
                // Druid 监控页面
                .requestMatchers("/druid/**").permitAll()
                // Swagger/Knife4j 文档（SpringDoc 需要这些路径）
                .requestMatchers(
                        "/doc.html",
                        "/v3/api-docs",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-resources/**",
                        "/webjars/**"
                ).permitAll()
                // 其他接口需要认证
                .anyRequest().authenticated()
            )

            // 添加 JWT 过滤器
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
