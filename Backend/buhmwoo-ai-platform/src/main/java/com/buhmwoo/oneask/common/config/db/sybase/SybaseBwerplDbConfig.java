package com.buhmwoo.oneask.common.config.db.sybase;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

import java.util.HashMap;
import java.util.Map;      // ✅ 추가
import java.util.Objects;

import javax.sql.DataSource;

@Configuration
@EnableJpaRepositories(
        basePackages = "com.buhmwoo.oneask.modules.sybase.bwerpl",
        entityManagerFactoryRef = "sybaseBwerplEntityManagerFactory",
        transactionManagerRef = "sybaseBwerplTransactionManager"
)
public class SybaseBwerplDbConfig {

    @Value("${custom.db.sybase.bwerpl.host}") private String host;
    @Value("${custom.db.sybase.bwerpl.port}") private int port;
    @Value("${custom.db.sybase.bwerpl.dbname}") private String dbname;
    @Value("${custom.db.sybase.bwerpl.username}") private String username;
    @Value("${custom.db.sybase.bwerpl.password}") private String password;

    @Bean(name = "sybaseBwerplDataSource")
    public DataSource sybaseBwerplDataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:sybase:Tds:" + host + ":" + port + "/" + dbname);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setDriverClassName("com.sybase.jdbc4.jdbc.SybDriver");
        return ds;
    }

    @Bean(name = "sybaseBwerplEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean sybaseBwerplEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("sybaseBwerplDataSource") DataSource dataSource
    ) {
        Map<String, Object> jpaProps = new HashMap<>();
        jpaProps.put("hibernate.dialect", "org.hibernate.dialect.SybaseDialect"); // ✅ 핵심        
        return builder
                .dataSource(dataSource)
                .packages("com.buhmwoo.oneask.modules.sybase.bwerpl")
                .persistenceUnit("sybaseBwerplPU")
                .properties(Map.of("hibernate.dialect", "org.hibernate.dialect.SybaseDialect")) // ✅ 추가
                .build();
    }

    @Bean(name = "sybaseBwerplTransactionManager")
    public JpaTransactionManager sybaseBwerplTransactionManager(
            @Qualifier("sybaseBwerplEntityManagerFactory")
            LocalContainerEntityManagerFactoryBean emf
    ) {
        return new JpaTransactionManager(Objects.requireNonNull(emf.getObject()));
      }
}

