package com.buhmwoo.oneask.common.config.db.maria;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

import java.util.Objects;

import javax.sql.DataSource;

@Configuration
@EnableJpaRepositories(
        basePackages = "com.buhmwoo.oneask.modules",
        entityManagerFactoryRef = "mariaEntityManagerFactory",
        transactionManagerRef = "mariaTransactionManager"
)
public class MariaDbConfig {

    @Value("${custom.db.maria.host}") private String host;
    @Value("${custom.db.maria.port}") private int port;
    @Value("${custom.db.maria.dbname}") private String dbname;
    @Value("${custom.db.maria.username}") private String username;
    @Value("${custom.db.maria.password}") private String password;

    @Primary
    @Bean(name = "mariaDataSource")
    public DataSource mariaDataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:mariadb://" + host + ":" + port + "/" + dbname);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setDriverClassName("org.mariadb.jdbc.Driver");
        return ds;
    }

    @Primary
    @Bean(name = "mariaEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean mariaEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("mariaDataSource") DataSource dataSource
    ) {
        return builder
                .dataSource(dataSource)
                .packages("com.buhmwoo.oneask.modules")
                .persistenceUnit("mariaPU")
                .build();
    }

    @Primary
    @Bean(name = "mariaTransactionManager")
    public JpaTransactionManager mariaTransactionManager(
            @Qualifier("mariaEntityManagerFactory")
            LocalContainerEntityManagerFactoryBean emf
    ) {
        return new JpaTransactionManager(Objects.requireNonNull(emf.getObject()));
      }
}
