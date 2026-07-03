package com.probeflow.testagent.apispec;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiSpecRepository extends JpaRepository<ApiSpec, String> {

    List<ApiSpec> findBySourceMaterialIdOrderByPathAscHttpMethodAsc(String sourceMaterialId);

    Optional<ApiSpec> findFirstBySourceMaterialIdAndOperationId(String sourceMaterialId, String operationId);

    Optional<ApiSpec> findFirstBySourceMaterialIdAndHttpMethodAndPath(
        String sourceMaterialId,
        HttpMethod httpMethod,
        String path
    );
}
