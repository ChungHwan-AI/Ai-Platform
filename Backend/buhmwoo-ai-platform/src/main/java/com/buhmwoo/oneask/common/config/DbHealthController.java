package com.buhmwoo.oneask.common.config;

import com.buhmwoo.oneask.common.dto.ApiResponseDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "DB 별 접속 가능 확인 API", description = "DB 별 접속 가능 확인 API")
@RestController
@RequestMapping("/db")
public class DbHealthController {

    private final JdbcTemplate mariaJdbc;
    private final JdbcTemplate oracleJdbc;
    private final JdbcTemplate sybaseBwerpeJdbc;
    private final JdbcTemplate sybaseBwerplJdbc;

    public DbHealthController(
            @Qualifier("mariaJdbcTemplate") JdbcTemplate mariaJdbc,
            @Qualifier("oracleJdbcTemplate") JdbcTemplate oracleJdbc,
            @Qualifier("sybaseBwerpeJdbcTemplate") JdbcTemplate sybaseBwerpeJdbc,
            @Qualifier("sybaseBwerplJdbcTemplate") JdbcTemplate sybaseBwerplJdbc
    ) {
        this.mariaJdbc = mariaJdbc;
        this.oracleJdbc = oracleJdbc;
        this.sybaseBwerpeJdbc = sybaseBwerpeJdbc;
        this.sybaseBwerplJdbc = sybaseBwerplJdbc;
    }

    @GetMapping("/ping/maria")
    public ApiResponseDto<Integer> pingMaria() {
        try {
            return ApiResponseDto.ok(mariaJdbc.queryForObject("SELECT 1", Integer.class));
        } catch (Exception e) {
            return ApiResponseDto.fail("Maria 연결 실패: " + e.getMessage());
        }
    }

    @GetMapping("/ping/oracle")
    public ApiResponseDto<Integer> pingOracle() {
        try {
            return ApiResponseDto.ok(oracleJdbc.queryForObject("SELECT 1 FROM dual", Integer.class));
        } catch (Exception e) {
            return ApiResponseDto.fail("Oracle 연결 실패: " + e.getMessage());
        }
    }

    @GetMapping("/ping/sybase/bwerpe")
    public ApiResponseDto<Integer> pingSybaseBwerpe() {
        try {
            return ApiResponseDto.ok(sybaseBwerpeJdbc.queryForObject("SELECT 1", Integer.class));
        } catch (Exception e) {
            return ApiResponseDto.fail("Sybase BWERPE 연결 실패: " + e.getMessage());
        }
    }

    @GetMapping("/ping/sybase/bwerpl")
    public ApiResponseDto<Integer> pingSybaseBwerpl() {
        try {
            return ApiResponseDto.ok(sybaseBwerplJdbc.queryForObject("SELECT 1", Integer.class));
        } catch (Exception e) {
            return ApiResponseDto.fail("Sybase BWERPL 연결 실패: " + e.getMessage());
        }
    }
}
