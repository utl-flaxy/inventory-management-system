package com.example.demo.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class ConfigurationContractTest {
    @ParameterizedTest
    @ValueSource(strings = {"application.properties", "application-prod.properties"})
    void defaultAndProdDisableDevelopmentSurfacesAndSql(String resource) throws Exception {
        var properties = properties(resource);
        for (String name : java.util.List.of("springdoc.api-docs.enabled", "springdoc.swagger-ui.enabled",
                "spring.h2.console.enabled", "spring.jpa.show-sql", "spring.jpa.properties.hibernate.format_sql"))
            assertEquals("false", properties.getProperty(name), name);
        assertEquals("health", properties.getProperty("management.endpoints.web.exposure.include"));
        assertEquals("never", properties.getProperty("management.endpoint.health.show-details"));
        assertEquals("never", properties.getProperty("management.endpoint.health.show-components"));
    }

    @Test
    void commonHasNoDatasourceFallbackAndPreservesFlywayValidation() throws Exception {
        var properties = properties("application.properties");
        for (String name : java.util.List.of("url", "username", "password"))
            assertNull(properties.getProperty("spring.datasource." + name));
        assertEquals("validate", properties.getProperty("spring.jpa.hibernate.ddl-auto"));
        assertEquals("true", properties.getProperty("spring.flyway.enabled"));
        assertEquals("false", properties.getProperty("spring.flyway.baseline-on-migrate"));
        assertEquals("true", properties.getProperty("spring.flyway.clean-disabled"));
        assertEquals("${JWT_SECRET}", properties.getProperty("app.jwt.secret"));
    }

    @Test
    void localOptsIntoSwaggerWithoutCredentialsOrSqlLogging() throws Exception {
        var properties = properties("application-local.properties");
        assertEquals("true", properties.getProperty("springdoc.api-docs.enabled"));
        assertEquals("true", properties.getProperty("springdoc.swagger-ui.enabled"));
        assertEquals("true", properties.getProperty("spring.jpa.properties.hibernate.format_sql"));
        assertEquals("false", properties.getProperty("spring.jpa.show-sql"));
        assertTrue(properties.keySet().stream().noneMatch(key -> key.toString().contains("datasource")));
    }

    @Test
    void h2IsTestOnlyAndActuatorVersionIsBootManaged() throws Exception {
        var document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(Path.of("pom.xml").toFile());
        var xpath = javax.xml.xpath.XPathFactory.newInstance().newXPath();
        assertEquals("test", xpath.evaluate("//dependency[artifactId='h2']/scope", document));
        assertEquals("spring-boot-starter-actuator", xpath.evaluate("//dependency[artifactId='spring-boot-starter-actuator']/artifactId", document));
        assertEquals("", xpath.evaluate("//dependency[artifactId='spring-boot-starter-actuator']/version", document));
    }

    @Test
    void structuredConsoleLoggingIsProdOnly() throws Exception {
        assertEquals("logstash", properties("application-prod.properties")
                .getProperty("logging.structured.format.console"));
        for (String resource : java.util.List.of("application.properties", "application-local.properties")) {
            var properties = properties(resource);
            assertNull(properties.getProperty("logging.structured.format.console"));
            for (String key : java.util.List.of("spring.config.import", "spring.profiles.include",
                    "spring.profiles.active", "spring.profiles.group.local")) {
                assertFalse(properties.getProperty(key, "").contains("prod"), key);
            }
        }
        for (String resource : java.util.List.of("application.properties", "application-local.properties",
                "application-prod.properties")) {
            var properties = properties(resource);
            assertNull(properties.getProperty("logging.file.name"));
            assertNull(properties.getProperty("logging.file.path"));
        }
    }

    @Test
    void databaseDiagnosticSuppressionIsProdOnly() throws Exception {
        for (String logger : java.util.List.of("org.mariadb.jdbc.message.server.ErrorPacket",
                "org.hibernate.engine.jdbc.spi.SqlExceptionHelper")) {
            String key = "logging.level." + logger;
            assertEquals("OFF", properties("application-prod.properties").getProperty(key));
            assertNull(properties("application.properties").getProperty(key));
            assertNull(properties("application-local.properties").getProperty(key));
        }
    }

    @Test
    void standardProbesAreConfiguredOnlyInProd() throws Exception {
        var expected = java.util.Map.of(
                "management.endpoint.health.probes.enabled", "true",
                "management.endpoint.health.probes.add-additional-paths", "true",
                "management.endpoint.health.group.liveness.include", "livenessState",
                "management.endpoint.health.group.readiness.include", "readinessState,db");
        var prod = properties("application-prod.properties");
        for (var entry : expected.entrySet()) {
            assertEquals(entry.getValue(), prod.getProperty(entry.getKey()));
            assertNull(properties("application.properties").getProperty(entry.getKey()));
            assertNull(properties("application-local.properties").getProperty(entry.getKey()));
        }
    }

    @Test
    void healthUsesTheApplicationPort() throws Exception {
        for (String resource : java.util.List.of("application.properties", "application-local.properties",
                "application-prod.properties")) {
            assertNull(properties(resource).getProperty("management.server.port"));
        }
    }

    private Properties properties(String resource) throws Exception {
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("src/main/resources", resource))) {
            properties.load(reader);
        }
        return properties;
    }
}
