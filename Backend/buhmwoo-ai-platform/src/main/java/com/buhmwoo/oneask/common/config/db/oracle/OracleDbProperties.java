package com.buhmwoo.oneask.common.config.db.oracle;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "custom.db.oracle")
public class OracleDbProperties {
    private String host;
    private int port;
    private String service;
    private String username;
    private String password;
}
