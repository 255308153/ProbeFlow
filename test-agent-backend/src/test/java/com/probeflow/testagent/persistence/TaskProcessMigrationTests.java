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
class TaskProcessMigrationTests {

    private final JdbcTemplate jdbc;

    TaskProcessMigrationTests(@Autowired DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void migrationsCreateTaskProcessTables() {
        assertThat(tableExists("task")).isTrue();
        assertThat(tableExists("plan_step")).isTrue();
        assertThat(tableExists("task_case_execution")).isTrue();
        assertThat(tableExists("report")).isTrue();
    }

    @Test
    void migrationsCreateJsonbColumnsForTargetsSnapshotsAndReportDetails() {
        assertThat(columnType("task", "target_api_spec_ids")).isEqualTo("JSON");
        assertThat(columnType("task", "metadata")).isEqualTo("JSON");
        assertThat(columnType("task_case_execution", "snapshot_json")).isEqualTo("JSON");
        assertThat(columnType("report", "findings")).isEqualTo("JSON");
        assertThat(columnType("report", "suggestions")).isEqualTo("JSON");
    }

    @Test
    void migrationsCreateTaskProcessLookupIndexes() {
        assertThat(indexExists("idx_task_status_created_at")).isTrue();
        assertThat(indexExists("idx_plan_step_task_order")).isTrue();
        assertThat(indexExists("idx_task_case_execution_task")).isTrue();
        assertThat(indexExists("idx_task_case_execution_case")).isTrue();
        assertThat(indexExists("idx_report_task")).isTrue();
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
