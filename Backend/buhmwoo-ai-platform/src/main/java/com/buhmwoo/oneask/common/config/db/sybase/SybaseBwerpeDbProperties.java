package com.buhmwoo.oneask.common.config.db.sybase;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "custom.db.sybase.bwerpe")
public class SybaseBwerpeDbProperties {
    private String host;
    private int port;
    private String dbname;
    private String username;
    private String password;
}
