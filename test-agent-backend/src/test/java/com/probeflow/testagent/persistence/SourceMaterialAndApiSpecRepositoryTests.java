package com.probeflow.testagent.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.apispec.ApiSpec;
import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.apispec.ApiSpecSourceType;
import com.probeflow.testagent.apispec.HttpMethod;
import com.probeflow.testagent.sourcematerial.IngestStatus;
import com.probeflow.testagent.sourcematerial.MaterialType;
import com.probeflow.testagent.sourcematerial.SourceMaterial;
import com.probeflow.testagent.sourcematerial.SourceMaterialRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SourceMaterialAndApiSpecRepositoryTests {

    @Autowired
    private SourceMaterialRepository sourceMaterials;

    @Autowired
    private ApiSpecRepository apiSpecs;

    @Autowired
    private EntityManager entityManager;

    @Test
    void sourceMaterialCanStoreAllSupportedMaterialTypesAndIngestStatuses() {
        var materialTypes = List.of(
            MaterialType.GIT_REPO,
            MaterialType.CODE_ARCHIVE,
            MaterialType.OPENAPI_FILE,
            MaterialType.REQUIREMENT_DOC,
            MaterialType.MANUAL_SELECTION
        );
        var statuses = List.of(IngestStatus.PENDING, IngestStatus.READY, IngestStatus.FAILED);
        var materialIds = new java.util.ArrayList<String>();

        for (int index = 0; index < materialTypes.size(); index++) {
            var material = new SourceMaterial();
            material.setTaskId("task-" + index);
            material.setMaterialType(materialTypes.get(index));
            material.setOriginalName("source-" + index);
            material.setOriginalRef("ref-" + index);
            material.setStoragePath("/tmp/probeflow/source-" + index);
            material.setIngestStatus(statuses.get(index % statuses.size()));

            materialIds.add(sourceMaterials.save(material).getMaterialId());
        }

        entityManager.flush();
        entityManager.clear();

        var loaded = materialIds.stream().map(id -> sourceMaterials.findById(id).orElseThrow()).toList();

        assertThat(loaded).extracting(SourceMaterial::getMaterialType).containsExactlyInAnyOrderElementsOf(materialTypes);
        assertThat(loaded).extracting(SourceMaterial::getIngestStatus).contains(IngestStatus.PENDING, IngestStatus.READY, IngestStatus.FAILED);
    }

    @Test
    void apiSpecCanStoreHttpDefinitionReadinessFlagsAndStructuredMetadata() {
        var apiSpec = new ApiSpec();
        apiSpec.setSystemName("order-platform");
        apiSpec.setModuleName("order");
        apiSpec.setHttpMethod(HttpMethod.POST);
        apiSpec.setPath("/api/orders");
        apiSpec.setSummary("Create order");
        apiSpec.setParameters(Map.of(
            "body", Map.of(
                "skuId", Map.of("type", "string", "required", true),
                "quantity", Map.of("type", "integer", "required", true)
            )
        ));
        apiSpec.setConstraints(Map.of(
            "quantity", Map.of("min", 1, "max", 99)
        ));
        apiSpec.setAuth(Map.of(
            "type", "bearer",
            "header", "Authorization"
        ));
        apiSpec.setSourceType(ApiSpecSourceType.OPENAPI);
        apiSpec.setSourceRef("openapi://orders.yaml#/paths/~1api~1orders/post");
        apiSpec.setVersion(1);
        apiSpec.setRouteReady(true);
        apiSpec.setBasicParamReady(true);
        apiSpec.setDtoExpanded(true);
        apiSpec.setValidationReady(true);
        apiSpec.setAuthReady(true);
        apiSpec.setKnowledgeContextReady(false);

        var saved = apiSpecs.save(apiSpec);
        entityManager.flush();
        entityManager.clear();

        var loaded = apiSpecs.findById(saved.getApiSpecId()).orElseThrow();

        assertThat(loaded.getSystemName()).isEqualTo("order-platform");
        assertThat(loaded.getModuleName()).isEqualTo("order");
        assertThat(loaded.getHttpMethod()).isEqualTo(HttpMethod.POST);
        assertThat(loaded.getPath()).isEqualTo("/api/orders");
        assertThat(loaded.isRouteReady()).isTrue();
        assertThat(loaded.isBasicParamReady()).isTrue();
        assertThat(loaded.isDtoExpanded()).isTrue();
        assertThat(loaded.isValidationReady()).isTrue();
        assertThat(loaded.isAuthReady()).isTrue();
        assertThat(loaded.isKnowledgeContextReady()).isFalse();
        assertThat(loaded.getParameters()).containsEntry("body", apiSpec.getParameters().get("body"));
        assertThat(loaded.getConstraints()).containsEntry("quantity", apiSpec.getConstraints().get("quantity"));
        assertThat(loaded.getAuth()).containsEntry("type", "bearer");
        assertThat(loaded.getCreatedAt()).isBeforeOrEqualTo(Instant.now());
        assertThat(loaded.getUpdatedAt()).isBeforeOrEqualTo(Instant.now());
    }
}
