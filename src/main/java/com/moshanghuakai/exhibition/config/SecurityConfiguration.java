package com.moshanghuakai.exhibition.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 所有业务接口直接访问，不校验访问令牌或设备签名，不创建登录会话。
 *
 * @author MoShangHuaKai
 */
@Configuration
public class SecurityConfiguration {
  @Bean
  public SecurityFilterChain security(HttpSecurity http) throws Exception {
    http.csrf()
        .disable()
        .sessionManagement()
        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
        .and()
        .authorizeRequests()
        // 所有业务接口及文档直接放行；参数、文件类型等业务约束仍由各自 Controller 校验。
        .antMatchers(
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/v3/api-docs.yaml")
        .permitAll()
        .antMatchers("/api/device/p6s/events", "/api/admin/**", "/api/screen/**", "/api/media/**")
        .permitAll()
        .anyRequest()
        .denyAll()
        .and()
        .httpBasic()
        .disable()
        .formLogin()
        .disable()
        .logout()
        .disable()
        .requestCache()
        .disable();
    return http.build();
  }
}
