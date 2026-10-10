package com.example.demo.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;

@Configuration(proxyBeanMethods = false)
@Profile("prod")
public class ProductionConfiguration {
    @Bean
    static BeanFactoryPostProcessor validateProductionConfiguration(Environment environment) {
        // DataSource/Flyway等のbean生成前に検証し、例外へ設定値を含めない。
        return beanFactory -> {
            String url = required(environment, "spring.datasource.url", "SPRING_DATASOURCE_URL");
            String username = required(environment, "spring.datasource.username", "SPRING_DATASOURCE_USERNAME");
            required(environment, "spring.datasource.password", "SPRING_DATASOURCE_PASSWORD");
            String secret = required(environment, "JWT_SECRET", "JWT_SECRET");
            if ("root".equalsIgnoreCase(username.trim())) {
                throw new IllegalStateException("SPRING_DATASOURCE_USERNAME must not be root");
            }
            // URL内secretはdriver/起動ログに露出し得る。passwordは独立した環境変数だけで渡す。
            String decodedUrl;
            try {
                decodedUrl = URLDecoder.decode(url, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException("SPRING_DATASOURCE_URL has invalid encoding");
            }
            if (decodedUrl.matches("(?is).*([?;&](password|user|username)=|//[^/]*@).*")) {
                throw new IllegalStateException("SPRING_DATASOURCE_URL must not contain credentials");
            }
            if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
                throw new IllegalStateException("JWT_SECRET must be at least 32 bytes");
            }
        };
    }

    private static String required(Environment environment, String property, String name) {
        String value = environment.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }
}
