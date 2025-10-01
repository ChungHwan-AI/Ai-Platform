package com.buhmwoo.oneask.common.config.db.oracle;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class OracleDataSourceConfig {

    @Value("${custom.db.oracle.host}")
    private String host;

    @Value("${custom.db.oracle.port}")
    private int port;

    // SID 또는 ServiceName 중 하나만 입력
    @Value("${custom.db.oracle.sid:}")
    private String sid;

    @Value("${custom.db.oracle.service:}")
    private String service;

    @Value("${custom.db.oracle.username}")
    private String username;

    @Value("${custom.db.oracle.password}")
    private String password;

    @Bean(name = "oracleDataSource")
    public DataSource oracleDataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setDriverClassName("oracle.jdbc.OracleDriver");

        String jdbcUrl;
        if (service != null && !service.isBlank()) {
            // ServiceName 방식
            jdbcUrl = String.format("jdbc:oracle:thin:@//%s:%d/%s", host, port, service);
        } else {
            // SID 방식
            jdbcUrl = String.format("jdbc:oracle:thin:@%s:%d:%s", host, port, sid);
        }

        ds.setJdbcUrl(jdbcUrl);
        ds.setUsername(username);
        ds.setPassword(password);

        return ds;
    }
}
