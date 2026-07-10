package com.probeflow.testagent.contractsmoke;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.probeflow.testagent.apispec.ApiSpec;
import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.apispec.ApiSpecSourceType;
import com.probeflow.testagent.apispec.HttpMethod;
import com.probeflow.testagent.executionrecord.ExecutionRecordRepository;
import com.probeflow.testagent.httpexecution.FakeHttpClientGateway;
import com.probeflow.testagent.httpexecution.HttpClientResponse;
import com.probeflow.testagent.testcase.TestCaseRepository;
import com.probeflow.testagent.testcasedraft.DraftStatus;
import com.probeflow.testagent.testcasedraft.TestCaseDraftRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OpenApiContractSmokeRunIssue01Tests {

    @Autowired
    private OpenApiContractSmokeRunApplicationService smokeRun;

    @Autowired
    private FakeHttpClientGateway fakeHttpClient;

    @Autowired
    private ApiSpecRepository apiSpecs;

    @Autowired
    private TestCaseDraftRepository drafts;

    @Autowired
    private TestCaseRepository testCases;

    @Autowired
    private ExecutionRecordRepository executionRecords;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void resetGateway() {
        fakeHttpClient.reset();
    }

    @Test
    void eligibleOpenApiApiSpecRunsDeterministicSmokeThroughDraftAndExecution() {
        var apiSpec = apiSpecs.save(openApiCreateOrderSpec());
        fakeHttpClient.respondWith(new HttpClientResponse(
            201,
            Map.of("Content-Type", "application/json; charset=utf-8"),
            Map.of("orderId", "order-1"),
            12L
        ));

        var first = smokeRun.run(request(apiSpec.getApiSpecId()));
        var second = smokeRun.run(new OpenApiContractSmokeRunRequest(
            apiSpec.getApiSpecId(),
            first.taskId(),
            "test",
            OpenApiContractSmokeRunRequest.DEFAULT_PROFILE,
            Map.of("baseUrl", "https://api.example.test"),
            Map.of("authToken", "token-abc"),
            "tester"
        ));

        assertThat(first.outcome()).isEqualTo(ContractSmokeOutcome.PASSED);
        assertThat(first.expectedStatus()).isEqualTo(201);
        assertThat(first.actualStatus()).isEqualTo(201);
        assertThat(first.statusCodeMatches()).isTrue();
        assertThat(first.expectedContentType()).isEqualTo("application/json");
        assertThat(first.contentTypeMatches()).isTrue();
        assertThat(first.draftId()).isNotBlank();
        assertThat(first.caseId()).isNotBlank();
        assertThat(first.executionRecordId()).isNotBlank();
        assertThat(first.contractOrigin())
            .containsEntry("apiSpecId", apiSpec.getApiSpecId())
            .containsEntry("contractSource", "API_SPEC")
            .containsEntry("sourceType", "OPENAPI");
        assertThat(first.generatedRequest())
            .containsEntry("method", "POST")
            .containsEntry("path", "/api/orders");
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) first.generatedRequest().get("body");
        assertThat(body)
            .containsEntry("skuId", "sample-skuId")
            .containsEntry("quantity", 1)
            .containsEntry("status", "NEW");

        assertThat(second.generatedRequest()).isEqualTo(first.generatedRequest());
        assertThat(fakeHttpClient.requests()).hasSize(2);
        assertThat(fakeHttpClient.requests().getFirst().body()).isEqualTo(body);
        assertThat(fakeHttpClient.requests().getFirst().headers())
            .containsEntry("Authorization", "Bearer token-abc");

        var draft = drafts.findById(first.draftId()).orElseThrow();
        assertThat(draft.getStatus()).isEqualTo(DraftStatus.PROMOTED);
        assertThat(draft.getPromotedCaseId()).isEqualTo(first.caseId());
        assertThat(draft.getExpectedStatusCode()).isEqualTo(201);
        assertThat(draft.getDraftContent().get("generationMetadata")).asString()
            .contains("openapi-contract-smoke");
        assertThat(draft.getDraftContent().get("contractOrigin")).isInstanceOf(Map.class);

        var testCase = testCases.findById(first.caseId()).orElseThrow();
        assertThat(testCase.getPrimaryApiSpecId()).isEqualTo(apiSpec.getApiSpecId());
        assertThat(testCase.getDetail())
            .containsEntry("expectedStatus", 201)
            .containsKey("contractOrigin")
            .containsKey("assertions");

        var record = executionRecords.findById(first.executionRecordId()).orElseThrow();
        assertThat(record.getCaseId()).isEqualTo(first.caseId());
        assertThat(record.getTaskId()).isEqualTo(first.taskId());
        assertThat(record.getAssertionResults()).anySatisfy(assertion ->
            assertThat(assertion).containsEntry("type", "STATUS_CODE").containsEntry("status", "PASSED")
        );
        assertThat(record.getAssertionResults()).anySatisfy(assertion ->
            assertThat(assertion).containsEntry("type", "CONTENT_TYPE").containsEntry("status", "PASSED")
        );
    }

    @Test
    void missingContractResponsesReturnsSkippedWithoutHttpTrafficOrDraft() {
        var apiSpec = apiSpecs.save(baseSpec(HttpMethod.GET, "/api/orders/{orderId}"));
        apiSpec.setParameters(Map.of(
            "path", List.of(Map.of(
                "name", "orderId",
                "required", true,
                "schema", Map.of("type", "string")
            ))
        ));
        apiSpecs.save(apiSpec);
        var draftCountBefore = drafts.count();

        var result = smokeRun.run(request(apiSpec.getApiSpecId()));

        assertThat(result.outcome()).isEqualTo(ContractSmokeOutcome.SKIPPED);
        assertThat(result.diagnostics()).anySatisfy(diagnostic ->
            assertThat(diagnostic.code()).isEqualTo("CONTRACT_RESPONSES_MISSING")
        );
        assertThat(result.draftId()).isNull();
        assertThat(result.executionRecordId()).isNull();
        assertThat(fakeHttpClient.requests()).isEmpty();
        assertThat(drafts.count()).isEqualTo(draftCountBefore);
    }

    @Test
    void unsupportedSchemaCompositionReturnsUnsupportedWithoutHttpTraffic() {
        var apiSpec = apiSpecs.save(baseSpec(HttpMethod.POST, "/api/orders"));
        apiSpec.setParameters(Map.of(
            "requestBody", Map.of(
                "required", true,
                "content", List.of(Map.of(
                    "mediaType", "application/json",
                    "schema", Map.of(
                        "oneOf", List.of(
                            Map.of("type", "object"),
                            Map.of("type", "string")
                        )
                    )
                ))
            ),
            "responses", Map.of(
                "201", Map.of(
                    "description", "Created",
                    "content", List.of(Map.of("mediaType", "application/json", "schema", Map.of("type", "object")))
                )
            )
        ));
        apiSpecs.save(apiSpec);

        var result = smokeRun.run(request(apiSpec.getApiSpecId()));

        assertThat(result.outcome()).isEqualTo(ContractSmokeOutcome.UNSUPPORTED);
        assertThat(result.diagnostics()).anySatisfy(diagnostic ->
            assertThat(diagnostic.code()).isEqualTo("CONTRACT_SCHEMA_COMPOSITION_UNSUPPORTED")
        );
        assertThat(fakeHttpClient.requests()).isEmpty();
        assertThat(result.draftId()).isNull();
    }

    @Test
    void readinessBlockedApiSpecDoesNotSendHttp() {
        var apiSpec = apiSpecs.save(openApiCreateOrderSpec());
        apiSpec.setRouteReady(false);
        apiSpecs.save(apiSpec);

        var result = smokeRun.run(request(apiSpec.getApiSpecId()));

        assertThat(result.outcome()).isEqualTo(ContractSmokeOutcome.BLOCKED);
        assertThat(result.diagnostics()).anySatisfy(diagnostic ->
            assertThat(diagnostic.code()).isEqualTo("CONTRACT_READINESS_BLOCKED")
        );
        assertThat(fakeHttpClient.requests()).isEmpty();
        assertThat(result.draftId()).isNull();
    }

    @Test
    void missingBaseUrlBlocksExecutionEnvironmentWithoutHttp() {
        var apiSpec = apiSpecs.save(openApiCreateOrderSpec());

        var result = smokeRun.run(new OpenApiContractSmokeRunRequest(
            apiSpec.getApiSpecId(),
            null,
            "test",
            OpenApiContractSmokeRunRequest.DEFAULT_PROFILE,
            Map.of(),
            Map.of("authToken", "token-abc"),
            "tester"
        ));

        assertThat(result.outcome()).isEqualTo(ContractSmokeOutcome.BLOCKED);
        assertThat(result.diagnostics()).anySatisfy(diagnostic ->
            assertThat(diagnostic.code()).isEqualTo("EXECUTION_BASE_URL_REQUIRED")
        );
        assertThat(fakeHttpClient.requests()).isEmpty();
    }

    @Test
    void statusCodeMismatchReturnsFailedContractResultWithTraceability() {
        var apiSpec = apiSpecs.save(openApiCreateOrderSpec());
        fakeHttpClient.respondWith(new HttpClientResponse(
            500,
            Map.of("Content-Type", "application/json"),
            Map.of("error", "boom"),
            9L
        ));

        var result = smokeRun.run(request(apiSpec.getApiSpecId()));

        assertThat(result.outcome()).isEqualTo(ContractSmokeOutcome.FAILED);
        assertThat(result.expectedStatus()).isEqualTo(201);
        assertThat(result.actualStatus()).isEqualTo(500);
        assertThat(result.statusCodeMatches()).isFalse();
        assertThat(result.executionRecordId()).isNotBlank();
        assertThat(result.caseId()).isNotBlank();
        assertThat(result.diagnostics()).anySatisfy(diagnostic ->
            assertThat(diagnostic.code()).isEqualTo("CONTRACT_STATUS_MISMATCH")
        );
        assertThat(fakeHttpClient.requests()).hasSize(1);
    }

    @Test
    void contentTypeMismatchReturnsFailedContractResult() {
        var apiSpec = apiSpecs.save(openApiCreateOrderSpec());
        fakeHttpClient.respondWith(new HttpClientResponse(
            201,
            Map.of("Content-Type", "text/html"),
            "<html>oops</html>",
            8L
        ));

        var result = smokeRun.run(request(apiSpec.getApiSpecId()));

        assertThat(result.outcome()).isEqualTo(ContractSmokeOutcome.FAILED);
        assertThat(result.statusCodeMatches()).isTrue();
        assertThat(result.expectedContentType()).isEqualTo("application/json");
        assertThat(result.actualContentType()).isEqualTo("text/html");
        assertThat(result.contentTypeMatches()).isFalse();
        assertThat(result.diagnostics()).anySatisfy(diagnostic ->
            assertThat(diagnostic.code()).isEqualTo("CONTRACT_CONTENT_TYPE_MISMATCH")
        );
    }

    @Test
    void openApiSchemeOnlyAuthStillInjectsBearerHeaderOnExecutedRequest() {
        var apiSpec = openApiCreateOrderSpec();
        // Legacy OpenAPI auth shape before type mapping was added.
        apiSpec.setAuth(Map.of(
            "required", true,
            "schemes", List.of("bearerAuth"),
            "requirements", List.of(List.of("bearerAuth"))
        ));
        apiSpec = apiSpecs.save(apiSpec);
        fakeHttpClient.respondWith(new HttpClientResponse(
            201,
            Map.of("Content-Type", "application/json"),
            Map.of("orderId", "order-1"),
            5L
        ));

        var result = smokeRun.run(request(apiSpec.getApiSpecId()));

        assertThat(result.outcome()).isEqualTo(ContractSmokeOutcome.PASSED);
        assertThat(fakeHttpClient.requests()).singleElement()
            .satisfies(httpRequest -> assertThat(httpRequest.headers())
                .containsEntry("Authorization", "Bearer token-abc"));
    }

    @Test
    void generatedValuesRespectMaximumAndZeroMaxLengthConstraints() {
        var apiSpec = baseSpec(HttpMethod.POST, "/api/widgets/{code}");
        apiSpec.setAuth(Map.of());
        apiSpec.setParameters(Map.of(
            "path", List.of(Map.of(
                "name", "code",
                "required", true,
                "schema", Map.of("type", "string", "maxLength", 0)
            )),
            "query", List.of(Map.of(
                "name", "limit",
                "required", true,
                "schema", Map.of("type", "integer", "maximum", 0)
            )),
            "requestBody", Map.of(
                "required", true,
                "content", List.of(Map.of(
                    "mediaType", "application/json",
                    "schema", Map.of(
                        "type", "object",
                        "required", List.of("quantity", "label"),
                        "properties", Map.of(
                            "quantity", Map.of("type", "integer", "minimum", 5, "maximum", 5),
                            "label", Map.of("type", "string", "maxLength", 3)
                        )
                    )
                ))
            ),
            "responses", Map.of(
                "200", Map.of(
                    "description", "OK",
                    "content", List.of(Map.of("mediaType", "application/json", "schema", Map.of("type", "object")))
                )
            )
        ));
        apiSpec.setConstraints(Map.of(
            "required", List.of("path.code", "query.limit", "requestBody.quantity", "requestBody.label"),
            "validations", Map.of(
                "path.code", Map.of("maxLength", 0),
                "query.limit", Map.of("maximum", 0),
                "requestBody.quantity", Map.of("minimum", 5, "maximum", 5),
                "requestBody.label", Map.of("maxLength", 3)
            )
        ));
        apiSpec = apiSpecs.save(apiSpec);
        fakeHttpClient.respondWith(new HttpClientResponse(
            200,
            Map.of("Content-Type", "application/json"),
            Map.of("ok", true),
            3L
        ));

        var result = smokeRun.run(new OpenApiContractSmokeRunRequest(
            apiSpec.getApiSpecId(),
            null,
            "test",
            OpenApiContractSmokeRunRequest.DEFAULT_PROFILE,
            Map.of("baseUrl", "https://api.example.test"),
            Map.of(),
            "tester"
        ));

        assertThat(result.outcome()).isEqualTo(ContractSmokeOutcome.PASSED);
        assertThat(result.generatedRequest().get("path")).isEqualTo("/api/widgets/");
        assertThat(result.generatedRequest().get("queryParams")).isEqualTo(Map.of("limit", 0));
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) result.generatedRequest().get("body");
        assertThat(body).containsEntry("quantity", 5);
        assertThat(String.valueOf(body.get("label")).length()).isLessThanOrEqualTo(3);
        assertThat(fakeHttpClient.requests()).singleElement()
            .satisfies(httpRequest -> {
                assertThat(httpRequest.path()).isEqualTo("/api/widgets/");
                assertThat(httpRequest.queryParams()).containsEntry("limit", 0);
            });
    }

    @Test
    void httpEntryPointRunsContractSmokeAndReturnsTraceableResult() throws Exception {
        var apiSpec = apiSpecs.save(openApiCreateOrderSpec());
        fakeHttpClient.respondWith(new HttpClientResponse(
            201,
            Map.of("Content-Type", "application/json"),
            Map.of("orderId", "order-http"),
            6L
        ));

        mockMvc.perform(post("/api/contract-smoke-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "apiSpecId", apiSpec.getApiSpecId(),
                    "environment", "test",
                    "profile", "smoke-valid",
                    "environmentVariables", Map.of("baseUrl", "https://api.example.test"),
                    "authVariables", Map.of("authToken", "token-abc"),
                    "requestedBy", "trial-user"
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("PASSED"))
            .andExpect(jsonPath("$.apiSpecId").value(apiSpec.getApiSpecId()))
            .andExpect(jsonPath("$.draftId").isNotEmpty())
            .andExpect(jsonPath("$.caseId").isNotEmpty())
            .andExpect(jsonPath("$.executionRecordId").isNotEmpty())
            .andExpect(jsonPath("$.expectedStatus").value(201))
            .andExpect(jsonPath("$.statusCodeMatches").value(true))
            .andExpect(jsonPath("$.contractOrigin.contractSource").value("API_SPEC"));

        assertThat(fakeHttpClient.requests()).hasSize(1);
    }

    @Test
    void httpEntryPointReturnsNotFoundForMissingApiSpec() throws Exception {
        mockMvc.perform(post("/api/contract-smoke-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "apiSpecId", "missing-api-spec",
                    "environmentVariables", Map.of("baseUrl", "https://api.example.test")
                ))))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("CONTRACT_SMOKE_NOT_FOUND"))
            .andExpect(jsonPath("$.field").value("apiSpecId"));
        assertThat(fakeHttpClient.requests()).isEmpty();
    }

    private OpenApiContractSmokeRunRequest request(String apiSpecId) {
        return new OpenApiContractSmokeRunRequest(
            apiSpecId,
            null,
            "test",
            OpenApiContractSmokeRunRequest.DEFAULT_PROFILE,
            Map.of("baseUrl", "https://api.example.test"),
            Map.of("authToken", "token-abc"),
            "tester"
        );
    }

    private ApiSpec openApiCreateOrderSpec() {
        var apiSpec = baseSpec(HttpMethod.POST, "/api/orders");
        apiSpec.setSourceType(ApiSpecSourceType.OPENAPI);
        apiSpec.setSourceRef("openapi://fixture#/paths/~1api~1orders/post");
        apiSpec.setAuth(Map.of(
            "required", true,
            "type", "bearer",
            "tokenVariable", "authToken"
        ));
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("requestBody", Map.of(
            "required", true,
            "content", List.of(Map.of(
                "mediaType", "application/json",
                "schema", Map.of(
                    "type", "object",
                    "required", List.of("skuId", "quantity", "status"),
                    "properties", Map.of(
                        "skuId", Map.of("type", "string"),
                        "quantity", Map.of("type", "integer"),
                        "status", Map.of("type", "string", "enum", List.of("NEW", "PAID"))
                    )
                )
            ))
        ));
        parameters.put("responses", Map.of(
            "201", Map.of(
                "description", "Created",
                "content", List.of(Map.of(
                    "mediaType", "application/json",
                    "schema", Map.of("type", "object")
                ))
            ),
            "400", Map.of("description", "Bad Request")
        ));
        apiSpec.setParameters(parameters);
        apiSpec.setConstraints(Map.of(
            "required", List.of("requestBody.skuId", "requestBody.quantity", "requestBody.status"),
            "enums", Map.of("requestBody.status", List.of("NEW", "PAID")),
            "validations", Map.of("requestBody.quantity", Map.of("minimum", 1, "maximum", 99))
        ));
        return apiSpec;
    }

    private ApiSpec baseSpec(HttpMethod method, String path) {
        var apiSpec = new ApiSpec();
        apiSpec.setSystemName("orders");
        apiSpec.setModuleName("order");
        apiSpec.setHttpMethod(method);
        apiSpec.setPath(path);
        apiSpec.setSummary("contract smoke fixture");
        apiSpec.setSourceType(ApiSpecSourceType.OPENAPI);
        apiSpec.setParameters(new LinkedHashMap<>());
        apiSpec.setConstraints(new LinkedHashMap<>());
        apiSpec.setAuth(new LinkedHashMap<>());
        apiSpec.setSourceLocation(Map.of());
        apiSpec.setVersion(1);
        apiSpec.setRouteReady(true);
        apiSpec.setBasicParamReady(true);
        apiSpec.setDtoExpanded(true);
        apiSpec.setValidationReady(true);
        apiSpec.setAuthReady(true);
        apiSpec.setKnowledgeContextReady(false);
        apiSpec.setPresentInLatestAnalysis(true);
        return apiSpec;
    }

    @TestConfiguration
    static class FakeHttpConfig {
        @Bean
        @Primary
        FakeHttpClientGateway fakeHttpClientGateway() {
            return new FakeHttpClientGateway();
        }
    }
}
