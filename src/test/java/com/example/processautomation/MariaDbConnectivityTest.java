package com.example.processautomation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class MariaDbConnectivityTest {
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void connectsToMariaDbAndRunsQuery() {
        String version = jdbcTemplate.queryForObject("SELECT VERSION()", String.class);

        assertThat(version).isNotBlank();
        assertThat(version.toLowerCase()).contains("mariadb");
    }
}
