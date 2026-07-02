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
class SourceMaterialAndApiSpecMigrationTests {

    private final JdbcTemplate jdbc;

    SourceMaterialAndApiSpecMigrationTests(@Autowired DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void migrationsCreateSourceMaterialAndApiSpecTables() {
        assertThat(tableExists("source_material")).isTrue();
        assertThat(tableExists("api_spec")).isTrue();
    }

    @Test
    void migrationsCreateKeyLookupIndexes() {
        assertThat(indexExists("idx_source_material_task_id")).isTrue();
        assertThat(indexExists("idx_source_material_type_status")).isTrue();
        assertThat(indexExists("idx_api_spec_module_path")).isTrue();
        assertThat(indexExists("idx_api_spec_readiness")).isTrue();
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
