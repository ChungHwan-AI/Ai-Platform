package com.buhmwoo.oneask.common.config.db.maria;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class MariaDataSourceConfig {

    @Value("${custom.db.maria.host}")
    private String host;

    @Value("${custom.db.maria.port}")
    private int port;

    @Value("${custom.db.maria.dbname}")
    private String dbname;

    @Value("${custom.db.maria.username}")
    private String username;

    @Value("${custom.db.maria.password}")
    private String password;

    @Bean(name = "mariaDataSource")
    public DataSource mariaDataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setDriverClassName("org.mariadb.jdbc.Driver");
        ds.setJdbcUrl(String.format("jdbc:mariadb://%s:%d/%s", host, port, dbname));
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setMaximumPoolSize(10);
        return ds;
    }
}
