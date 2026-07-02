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
class KnowledgeDocumentChunkMigrationTests {

    private final JdbcTemplate jdbc;

    KnowledgeDocumentChunkMigrationTests(@Autowired DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void migrationsCreateKnowledgeTables() {
        assertThat(tableExists("knowledge_document")).isTrue();
        assertThat(tableExists("knowledge_document_revision")).isTrue();
        assertThat(tableExists("knowledge_chunk")).isTrue();
    }

    @Test
    void migrationsCreateJsonAndVectorColumnsForKnowledgeChunks() {
        assertThat(columnType("knowledge_document", "metadata")).isEqualTo("JSON");
        assertThat(columnType("knowledge_document_revision", "metadata")).isEqualTo("JSON");
        assertThat(columnType("knowledge_chunk", "tags")).isEqualTo("JSON");
        assertThat(columnType("knowledge_chunk", "applicable_stages")).isEqualTo("JSON");
        assertThat(columnType("knowledge_chunk", "metadata")).isEqualTo("JSON");
        assertThat(columnExists("knowledge_chunk", "embedding")).isTrue();
    }

    @Test
    void migrationsCreateKnowledgeLookupAndVectorIndexes() {
        assertThat(indexExists("idx_knowledge_document_type_module")).isTrue();
        assertThat(indexExists("idx_knowledge_revision_document_latest")).isTrue();
        assertThat(indexExists("idx_knowledge_chunk_document_revision")).isTrue();
        assertThat(indexExists("idx_knowledge_chunk_status")).isTrue();
        assertThat(indexExists("idx_knowledge_chunk_embedding")).isTrue();
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
