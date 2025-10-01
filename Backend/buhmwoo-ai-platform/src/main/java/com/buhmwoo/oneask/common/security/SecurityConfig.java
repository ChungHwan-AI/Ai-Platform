package com.buhmwoo.oneask.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity // (부트 3에서는 없어도 되지만, 명시해두면 IDE 인식에 유리)
public class SecurityConfig {

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
      .csrf(csrf -> csrf.disable())
      .authorizeHttpRequests(auth -> auth
        .requestMatchers("/health", 
                         "/error", 
                         "/actuator/**",
                         "/v3/api-docs/**",
                         "/swagger-ui.html",
                         "/swagger-ui/**").permitAll()
        .anyRequest().permitAll()  // 초기: 전부 허용. 나중에 JWT 붙이면 바꿉니다.
      )
      .httpBasic(Customizer.withDefaults())
      .formLogin(form -> form.disable());
    return http.build();
  }
}
