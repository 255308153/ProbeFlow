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
class TestCaseAndDraftMigrationTests {

    private final JdbcTemplate jdbc;

    TestCaseAndDraftMigrationTests(@Autowired DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void migrationsCreateTestCaseAndDraftTables() {
        assertThat(tableExists("test_case")).isTrue();
        assertThat(tableExists("test_case_draft")).isTrue();
    }

    @Test
    void migrationsCreateJsonbColumnsForStructuredSnapshots() {
        assertThat(columnType("test_case", "detail")).isEqualTo("JSON");
        assertThat(columnType("test_case", "steps")).isEqualTo("JSON");
        assertThat(columnType("test_case", "based_on_api_spec_versions")).isEqualTo("JSON");
        assertThat(columnType("test_case_draft", "draft_content")).isEqualTo("JSON");
    }

    @Test
    void migrationsCreateKeyLookupAndDeduplicationIndexes() {
        assertThat(indexExists("idx_test_case_primary_api_spec")).isTrue();
        assertThat(indexExists("idx_test_case_stale_status")).isTrue();
        assertThat(indexExists("idx_test_case_module_scenario")).isTrue();
        assertThat(indexExists("idx_test_case_draft_task_dedup")).isTrue();
        assertThat(indexExists("idx_test_case_draft_target_status")).isTrue();
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
