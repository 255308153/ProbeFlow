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
class MemoryItemMigrationTests {

    private final JdbcTemplate jdbc;

    MemoryItemMigrationTests(@Autowired DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void migrationsCreateScopedPostgresMemoryTables() {
        assertThat(tableExists("task_memory_item")).isTrue();
        assertThat(tableExists("long_term_memory")).isTrue();
    }

    @Test
    void migrationsCreateCommonMemoryMetadataAndVectorColumns() {
        assertThat(columnType("task_memory_item", "tags")).isEqualTo("JSON");
        assertThat(columnType("task_memory_item", "metadata")).isEqualTo("JSON");
        assertThat(columnType("long_term_memory", "tags")).isEqualTo("JSON");
        assertThat(columnType("long_term_memory", "metadata")).isEqualTo("JSON");
        assertThat(columnExists("long_term_memory", "embedding")).isTrue();
    }

    @Test
    void migrationsCreateMemoryLookupAndVectorIndexes() {
        assertThat(indexExists("idx_task_memory_task_status")).isTrue();
        assertThat(indexExists("idx_task_memory_scope_type")).isTrue();
        assertThat(indexExists("idx_long_term_memory_scope_status")).isTrue();
        assertThat(indexExists("idx_long_term_memory_embedding")).isTrue();
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
