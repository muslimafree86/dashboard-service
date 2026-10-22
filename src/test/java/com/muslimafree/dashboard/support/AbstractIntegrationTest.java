package com.muslimafree.dashboard.support;

import java.time.Instant;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.MySQLContainer;

import com.muslimafree.dashboard.DashboardApplication;
import com.muslimafree.dashboard.location.LocationIndexer;
import com.muslimafree.dashboard.support.Members.Member;

/**
 * The whole service against a real MariaDB (default) or MySQL ({@code -Dit.db=mysql}) started once by Testcontainers.
 * The database is first given user-profile-managment's schema by its own migrations and history table, then the
 * dashboard starts and applies its own migrations on top - the production order.
 */
@SpringBootTest(classes = {com.muslimafree.dashboard.DashboardApplication.class,
        AbstractIntegrationTest.TestClockConfig.class})
@AutoConfigureMockMvc
// tests page through feeds far faster than a person; RateLimitIT overrides these with @DynamicPropertySource
@TestPropertySource(properties = {"app.rate-limit.per-minute=100000", "app.rate-limit.per-day=100000",
        // jobs run when a test says so (LocationIndexer.reconcileAll)
        "app.jobs.enabled=false"})
public abstract class AbstractIntegrationTest {

    protected static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");
    protected static final String PHOTO_SIGNING_KEY = "test-photo-signing-key-of-at-least-32-chars";

    static final JdbcDatabaseContainer<?> DATABASE = createDatabase();

    static {
        DATABASE.start();
        Flyway.configure()
                .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .locations("classpath:db/profile-schema")
                .load()
                .migrate();
    }

    @SuppressWarnings("resource")
    private static JdbcDatabaseContainer<?> createDatabase() {
        if ("mysql".equalsIgnoreCase(System.getProperty("it.db"))) {
            return new MySQLContainer<>("mysql:8.4").withDatabaseName("muslima_users")
                    .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");
        }
        return new MariaDBContainer<>("mariadb:11.4").withDatabaseName("muslima_users")
                .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        // application.yml defaults to the MariaDB driver, which refuses jdbc:mysql:// URLs
        registry.add("spring.datasource.driver-class-name", DATABASE::getDriverClassName);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
        registry.add("app.security.issuer", () -> TestTokens.ISSUER);
        registry.add("app.security.public-key-location", TestTokens::publicKeyLocation);
        // revocations must be visible immediately in tests
        registry.add("app.security.session-cache-ttl", () -> "0s");
        registry.add("app.photos.url-signing-key", () -> PHOTO_SIGNING_KEY);
    }

    @TestConfiguration
    public static class TestClockConfig {

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected LocationIndexer locationIndexer;

    protected Members members;

    @BeforeEach
    void resetDatabase() {
        clock.set(NOW);
        members = new Members(jdbc, clock, locationIndexer::refresh);
        members.deleteAll();
    }

    protected static RequestPostProcessor bearer(String accessToken) {
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
            return request;
        };
    }

    protected static RequestPostProcessor as(Members.Member member) {
        return bearer(member.token());
    }
}
