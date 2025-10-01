package com.buhmwoo.oneask;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan(basePackages = "com.buhmwoo.oneask")
public class OneAskApplication {
    public static void main(String[] args) {
        SpringApplication.run(OneAskApplication.class, args);
    }
}
