package com.buhmwoo.oneask.common.config.db.oracle;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

import java.util.Objects;

import javax.sql.DataSource;

@Configuration
@EnableJpaRepositories(
        basePackages = "com.buhmwoo.oneask.modules.oracle",
        entityManagerFactoryRef = "oracleEntityManagerFactory",
        transactionManagerRef = "oracleTransactionManager"
)
public class OracleDbConfig {

    @Value("${custom.db.oracle.host}") private String host;
    @Value("${custom.db.oracle.port}") private int port;
    @Value("${custom.db.oracle.service}") private String service;
    @Value("${custom.db.oracle.username}") private String username;
    @Value("${custom.db.oracle.password}") private String password;

    @Bean(name = "oracleDataSource")
    public DataSource oracleDataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:oracle:thin:@//" + host + ":" + port + "/" + service);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setDriverClassName("oracle.jdbc.OracleDriver");
        return ds;
    }

    @Bean(name = "oracleEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean oracleEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("oracleDataSource") DataSource dataSource
    ) {
        return builder
                .dataSource(dataSource)
                .packages("com.buhmwoo.oneask.modules.oracle")
                .persistenceUnit("oraclePU")
                .build();
    }

    @Bean(name = "oracleTransactionManager")
    public JpaTransactionManager oracleTransactionManager(
            @Qualifier("oracleEntityManagerFactory")
            LocalContainerEntityManagerFactoryBean emf
    ) {
        return new JpaTransactionManager(Objects.requireNonNull(emf.getObject()));

      }
}
