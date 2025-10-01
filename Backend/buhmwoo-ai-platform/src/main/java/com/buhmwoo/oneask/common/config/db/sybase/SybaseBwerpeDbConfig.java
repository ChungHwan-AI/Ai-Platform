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
        basePackages = "com.buhmwoo.oneask.modules.sybase.bwerpe",
        entityManagerFactoryRef = "sybaseBwerpeEntityManagerFactory",
        transactionManagerRef = "sybaseBwerpeTransactionManager"
)
public class SybaseBwerpeDbConfig {

    @Value("${custom.db.sybase.bwerpe.host}") private String host;
    @Value("${custom.db.sybase.bwerpe.port}") private int port;
    @Value("${custom.db.sybase.bwerpe.dbname}") private String dbname;
    @Value("${custom.db.sybase.bwerpe.username}") private String username;
    @Value("${custom.db.sybase.bwerpe.password}") private String password;

    @Bean(name = "sybaseBwerpeDataSource")
    public DataSource sybaseBwerpeDataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:sybase:Tds:" + host + ":" + port + "/" + dbname);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setDriverClassName("com.sybase.jdbc4.jdbc.SybDriver");
        return ds;
    }

    @Bean(name = "sybaseBwerpeEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean sybaseBwerpeEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("sybaseBwerpeDataSource") DataSource dataSource
    ) {
        Map<String, Object> jpaProps = new HashMap<>();
        jpaProps.put("hibernate.dialect", "org.hibernate.dialect.SybaseDialect"); // ✅ 핵심    
        return builder
                .dataSource(dataSource)
                .packages("com.buhmwoo.oneask.modules.sybase.bwerpe")
                .persistenceUnit("sybaseBwerpePU")
                .properties(Map.of("hibernate.dialect", "org.hibernate.dialect.SybaseDialect")) // ✅ 추가
                .build();
    }

    @Bean(name = "sybaseBwerpeTransactionManager")
    public JpaTransactionManager sybaseBwerpeTransactionManager(
            @Qualifier("sybaseBwerpeEntityManagerFactory")
            LocalContainerEntityManagerFactoryBean emf
    ) {
        return new JpaTransactionManager(Objects.requireNonNull(emf.getObject()));
      }
    
}
