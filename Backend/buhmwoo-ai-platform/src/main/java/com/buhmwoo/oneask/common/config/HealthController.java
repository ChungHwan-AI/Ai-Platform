package com.buhmwoo.oneask.common.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "백엔드 가동 확인 API", description = "백엔드 가동 확인 API")
@RestController
public class HealthController {
  @GetMapping("/health")
  public String health() { return "OK"; }
}
