package com.buhmwoo.oneask.common.config.db;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration
public class JdbcTemplateConfig {

    /**
     * MariaDB JdbcTemplate
     */
    @Bean(name = "mariaJdbcTemplate")
    public JdbcTemplate mariaJdbcTemplate(@Qualifier("mariaDataSource") DataSource mariaDataSource) {
        return new JdbcTemplate(mariaDataSource);
    }

    /**
     * Oracle JdbcTemplate
     */
    @Bean(name = "oracleJdbcTemplate")
    public JdbcTemplate oracleJdbcTemplate(@Qualifier("oracleDataSource") DataSource oracleDataSource) {
        return new JdbcTemplate(oracleDataSource);
    }

    /**
     * Sybase BWERPE JdbcTemplate
     */
    @Bean(name = "sybaseBwerpeJdbcTemplate")
    public JdbcTemplate sybaseBwerpeJdbcTemplate(@Qualifier("sybaseBwerpeDataSource") DataSource sybaseBwerpeDataSource) {
        return new JdbcTemplate(sybaseBwerpeDataSource);
    }

    /**
     * Sybase BWERPL JdbcTemplate
     */
    @Bean(name = "sybaseBwerplJdbcTemplate")
    public JdbcTemplate sybaseBwerplJdbcTemplate(@Qualifier("sybaseBwerplDataSource") DataSource sybaseBwerplDataSource) {
        return new JdbcTemplate(sybaseBwerplDataSource);
    }
}
