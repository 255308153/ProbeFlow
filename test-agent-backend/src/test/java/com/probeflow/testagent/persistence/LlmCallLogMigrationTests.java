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
class LlmCallLogMigrationTests {

    private final JdbcTemplate jdbc;

    LlmCallLogMigrationTests(@Autowired DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void migrationCreatesLlmCallLogTableAndAuditColumns() {
        assertThat(tableExists("llm_call_log")).isTrue();
        assertThat(columnExists("llm_call_log", "llm_call_id")).isTrue();
        assertThat(columnExists("llm_call_log", "task_id")).isTrue();
        assertThat(columnExists("llm_call_log", "plan_step_id")).isTrue();
        assertThat(columnExists("llm_call_log", "purpose")).isTrue();
        assertThat(columnExists("llm_call_log", "provider")).isTrue();
        assertThat(columnExists("llm_call_log", "model")).isTrue();
        assertThat(columnExists("llm_call_log", "template_id")).isTrue();
        assertThat(columnExists("llm_call_log", "template_version")).isTrue();
        assertThat(columnExists("llm_call_log", "request_hash")).isTrue();
        assertThat(columnExists("llm_call_log", "status")).isTrue();
        assertThat(columnExists("llm_call_log", "error_type")).isTrue();
        assertThat(columnExists("llm_call_log", "latency_ms")).isTrue();
        assertThat(columnExists("llm_call_log", "prompt_tokens")).isTrue();
        assertThat(columnExists("llm_call_log", "completion_tokens")).isTrue();
        assertThat(columnExists("llm_call_log", "total_tokens")).isTrue();
        assertThat(columnExists("llm_call_log", "prompt_summary")).isTrue();
        assertThat(columnExists("llm_call_log", "response_summary")).isTrue();
        assertThat(columnExists("llm_call_log", "provider_trace_id")).isTrue();
        assertThat(columnExists("llm_call_log", "fake_provider")).isTrue();
        assertThat(columnType("llm_call_log", "metadata")).isEqualTo("JSON");
    }

    @Test
    void migrationCreatesLookupIndexesAndAvoidsSecretColumns() {
        assertThat(indexExists("idx_llm_call_log_task_created_at")).isTrue();
        assertThat(indexExists("idx_llm_call_log_status_created_at")).isTrue();
        assertThat(indexExists("idx_llm_call_log_purpose_created_at")).isTrue();
        assertThat(columnExists("llm_call_log", "api_key")).isFalse();
        assertThat(columnExists("llm_call_log", "authorization_header")).isFalse();
        assertThat(columnExists("llm_call_log", "secret")).isFalse();
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

    private boolean columnExists(String tableName, String columnName) {
        Integer count = jdbc.queryForObject(
            """
            SELECT COUNT(*)
            FROM information_schema.columns
            WHERE LOWER(table_schema) = 'public'
              AND LOWER(table_name) = LOWER(?)
              AND LOWER(column_name) = LOWER(?)
            """,
            Integer.class,
            tableName,
            columnName
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
