package com.probeflow.testagent.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ExecutionObservationChangeLogMigrationTests {

    private final JdbcTemplate jdbc;

    ExecutionObservationChangeLogMigrationTests(@Autowired DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void migrationsCreateExecutionObservationAndChangeLogTables() {
        assertThat(tableExists("execution_record")).isTrue();
        assertThat(tableExists("observation")).isTrue();
        assertThat(tableExists("change_log")).isTrue();
    }

    @Test
    void migrationsCreateJsonbColumnsForExecutionFactsAndChangeSnapshots() {
        assertThat(columnType("execution_record", "request_snapshot")).isEqualTo("JSON");
        assertThat(columnType("execution_record", "response_snapshot")).isEqualTo("JSON");
        assertThat(columnType("execution_record", "assertion_results")).isEqualTo("JSON");
        assertThat(columnType("change_log", "before_snapshot")).isEqualTo("JSON");
        assertThat(columnType("change_log", "after_snapshot")).isEqualTo("JSON");
    }

    @Test
    void migrationsCreateLookupIndexesForExecutionObservationAndChangeLog() {
        assertThat(indexExists("idx_execution_record_task_case_time")).isTrue();
        assertThat(indexExists("idx_execution_record_case_created_at")).isTrue();
        assertThat(indexExists("idx_observation_task_execution")).isTrue();
        assertThat(indexExists("idx_change_log_entity")).isTrue();
        assertThat(indexExists("idx_change_log_task")).isTrue();
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbc.queryForObject(
            """
            SELECT COUNT(*)
            FROM information_schema.tables
            WHERE LOWER(table_schema) = 'public'
              AND LOWER(table_name) = LOWER(?)
            """,
            Integer.class,
            tableName
        );

        return count != null && count == 1;
    }

    private String columnType(String tableName, String columnName) {
        return jdbc.queryForObject(
            """
            SELECT UPPER(data_type)
            FROM information_schema.columns
            WHERE LOWER(table_schema) = 'public'
              AND LOWER(table_name) = LOWER(?)
              AND LOWER(column_name) = LOWER(?)
            """,
            String.class,
            tableName,
            columnName
        );
    }

    private boolean indexExists(String indexName) {
        Integer count = jdbc.queryForObject(
            """
            SELECT COUNT(*)
            FROM information_schema.indexes
            WHERE LOWER(table_schema) = 'public'
              AND LOWER(index_name) = LOWER(?)
            """,
            Integer.class,
            indexName
        );

        return count != null && count == 1;
    }
}
