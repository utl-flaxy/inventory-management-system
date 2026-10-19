package com.example.demo.security;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.StandardEnvironment;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class JwtConfigurationTest {

    // 実application.propertiesを読み、開発PC/CIの環境変数によらず設定境界を検証する
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(context -> {
                context.getEnvironment().getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                context.getEnvironment().getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                new ConfigDataApplicationContextInitializer().initialize(context);
            })
            .withUserConfiguration(JwtConfiguration.class);

    @Test
    void environmentSecretCreatesJwtBean() {
        String secret = Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded());

        contextRunner.withPropertyValues("JWT_SECRET=" + secret).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(JwtUtil.class);
            JwtUtil jwtUtil = context.getBean(JwtUtil.class);
            String uid = java.util.UUID.randomUUID().toString();
            assertThat(jwtUtil.verify(jwtUtil.generateToken(uid, 0)).uid()).isEqualTo(uid);
        });
    }

    @Test
    void missingRequiredEnvironmentSecretFailsStartup() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("JWT_SECRET");
        });
    }

    @Test
    void weakEnvironmentSecretFailsStartup() {
        contextRunner.withPropertyValues("JWT_SECRET=too-short").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasStackTraceContaining("at least 32 bytes");
        });
    }

    @Test
    void blankEnvironmentSecretFailsStartup() {
        contextRunner.withPropertyValues("JWT_SECRET=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasStackTraceContaining("must not be blank");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({JwtUtil.class, com.example.demo.config.ClockConfiguration.class})
    static class JwtConfiguration {

        @Bean
        static PropertySourcesPlaceholderConfigurer placeholders() {
            return new PropertySourcesPlaceholderConfigurer();
        }
    }
}
