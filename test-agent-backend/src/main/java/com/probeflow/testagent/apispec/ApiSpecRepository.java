package com.probeflow.testagent.apispec;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiSpecRepository extends JpaRepository<ApiSpec, String> {

    List<ApiSpec> findBySourceMaterialIdOrderByPathAscHttpMethodAsc(String sourceMaterialId);
}
