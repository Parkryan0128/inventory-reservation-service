package dev.ryanpark.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class FoundationTest {
    @Autowired JdbcTemplate jdbc;

    @Test void flywayCreatesCatalog() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM products", Long.class)).isNotNull();
    }

    @Test void databaseRejectsBrokenInventoryInvariant() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO products VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), "invalid", "Invalid", 100, "USD", 10, 1, 0, 10))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
