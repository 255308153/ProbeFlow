package com.probeflow.testagent.contractsmoke;

import com.probeflow.testagent.apispec.ApiSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class ContractEligibilityEvaluator {

    ContractEligibilityDecision evaluate(ApiSpec apiSpec, OpenApiContractSmokeRunRequest request) {
        var origin = contractOrigin(apiSpec);
        if (apiSpec.getHttpMethod() == null || !StringUtils.hasText(apiSpec.getPath())) {
            return ContractEligibilityDecision.terminal(
                ContractSmokeOutcome.SKIPPED,
                origin,
                ContractSmokeDiagnostic.skipped(
                    "CONTRACT_ROUTE_MISSING",
                    "ApiSpec is missing HTTP method or path contract information.",
                    "Provide an ApiSpec with method and path before running contract smoke."
                )
            );
        }
        if (!apiSpec.isRouteReady() || !apiSpec.isBasicParamReady()) {
            return ContractEligibilityDecision.terminal(
                ContractSmokeOutcome.BLOCKED,
                origin,
                ContractSmokeDiagnostic.blocked(
                    "CONTRACT_READINESS_BLOCKED",
                    "ApiSpec readiness is incomplete for contract smoke (routeReady/basicParamReady).",
                    "Re-analyze the source material until route and basic parameter readiness are true."
                )
            );
        }

        var responses = responses(apiSpec);
        if (responses.isEmpty()) {
            return ContractEligibilityDecision.terminal(
                ContractSmokeOutcome.SKIPPED,
                origin,
                ContractSmokeDiagnostic.skipped(
                    "CONTRACT_RESPONSES_MISSING",
                    "ApiSpec does not declare response status contracts required for smoke validation.",
                    "Add declared response status codes to the OpenAPI/ApiSpec contract."
                )
            );
        }

        var expectedStatus = preferredSuccessStatus(responses);
        if (expectedStatus == null) {
            return ContractEligibilityDecision.terminal(
                ContractSmokeOutcome.SKIPPED,
                origin,
                ContractSmokeDiagnostic.skipped(
                    "CONTRACT_SUCCESS_STATUS_MISSING",
                    "ApiSpec does not declare a concrete success response status for smoke validation.",
                    "Declare at least one numeric 2xx response status in the contract."
                )
            );
        }

        var unsupported = unsupportedSchemaDiagnostics(apiSpec);
        if (!unsupported.isEmpty()) {
            return ContractEligibilityDecision.terminal(
                ContractSmokeOutcome.UNSUPPORTED,
                origin,
                unsupported.getFirst()
            );
        }

        if (!hasBaseUrl(request)) {
            return ContractEligibilityDecision.terminal(
                ContractSmokeOutcome.BLOCKED,
                origin,
                ContractSmokeDiagnostic.blocked(
                    "EXECUTION_BASE_URL_REQUIRED",
                    "Contract smoke requires environmentVariables.baseUrl before sending HTTP traffic.",
                    "Configure an explicit execution environment baseUrl for the target service."
                )
            );
        }

        if (authRequired(apiSpec) && !hasAuthCredentials(apiSpec, request)) {
            return ContractEligibilityDecision.terminal(
                ContractSmokeOutcome.BLOCKED,
                origin,
                ContractSmokeDiagnostic.blocked(
                    "EXECUTION_AUTH_REQUIRED",
                    "Contract smoke requires authentication credentials for this ApiSpec.",
                    "Provide authVariables for the required auth scheme before running smoke."
                )
            );
        }

        return ContractEligibilityDecision.ready(
            expectedStatus,
            expectedContentType(responses, expectedStatus),
            origin
        );
    }

    private Map<String, Object> contractOrigin(ApiSpec apiSpec) {
        var origin = new LinkedHashMap<String, Object>();
        origin.put("apiSpecId", apiSpec.getApiSpecId());
        if (apiSpec.getSourceType() != null) {
            origin.put("sourceType", apiSpec.getSourceType().name());
        }
        if (StringUtils.hasText(apiSpec.getSourceRef())) {
            origin.put("sourceRef", apiSpec.getSourceRef());
        }
        if (StringUtils.hasText(apiSpec.getSourceMaterialId())) {
            origin.put("sourceMaterialId", apiSpec.getSourceMaterialId());
        }
        if (apiSpec.getHttpMethod() != null) {
            origin.put("httpMethod", apiSpec.getHttpMethod().name());
        }
        if (StringUtils.hasText(apiSpec.getPath())) {
            origin.put("path", apiSpec.getPath());
        }
        origin.put("apiSpecVersion", apiSpec.getVersion());
        origin.put("contractSource", "API_SPEC");
        return origin;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> responses(ApiSpec apiSpec) {
        var parameters = apiSpec.getParameters();
        if (parameters == null) {
            return Map.of();
        }
        var responses = parameters.get("responses");
        if (responses instanceof Map<?, ?> map) {
            var normalized = new LinkedHashMap<String, Object>();
            map.forEach((key, value) -> {
                if (key != null) {
                    normalized.put(String.valueOf(key), value);
                }
            });
            return normalized;
        }
        return Map.of();
    }

    private Integer preferredSuccessStatus(Map<String, Object> responses) {
        var successCodes = new ArrayList<Integer>();
        for (var key : responses.keySet()) {
            if ("default".equalsIgnoreCase(key)) {
                continue;
            }
            try {
                var status = Integer.parseInt(key.trim());
                if (status >= 200 && status < 300) {
                    successCodes.add(status);
                }
            } catch (NumberFormatException ignored) {
                // ignore non-numeric response keys
            }
        }
        successCodes.sort(Integer::compareTo);
        return successCodes.isEmpty() ? null : successCodes.getFirst();
    }

    @SuppressWarnings("unchecked")
    private String expectedContentType(Map<String, Object> responses, Integer expectedStatus) {
        var response = responses.get(String.valueOf(expectedStatus));
        if (!(response instanceof Map<?, ?> responseMap)) {
            return null;
        }
        var content = responseMap.get("content");
        if (content instanceof List<?> list) {
            for (var item : list) {
                if (item instanceof Map<?, ?> contentItem) {
                    var mediaType = contentItem.get("mediaType");
                    if (mediaType != null && StringUtils.hasText(String.valueOf(mediaType))) {
                        return String.valueOf(mediaType);
                    }
                }
            }
        }
        if (content instanceof Map<?, ?> mediaMap && !mediaMap.isEmpty()) {
            return String.valueOf(mediaMap.keySet().iterator().next());
        }
        return null;
    }

    private List<ContractSmokeDiagnostic> unsupportedSchemaDiagnostics(ApiSpec apiSpec) {
        var diagnostics = new ArrayList<ContractSmokeDiagnostic>();
        walkUnsupported(apiSpec.getParameters(), "parameters", diagnostics);
        walkUnsupported(apiSpec.getConstraints(), "constraints", diagnostics);
        return diagnostics;
    }

    @SuppressWarnings("unchecked")
    private void walkUnsupported(Object node, String path, List<ContractSmokeDiagnostic> diagnostics) {
        if (diagnostics.size() >= 1) {
            return;
        }
        if (node instanceof Map<?, ?> map) {
            if (map.containsKey("oneOf") || map.containsKey("anyOf") || map.containsKey("allOf") || map.containsKey("not")) {
                diagnostics.add(ContractSmokeDiagnostic.unsupported(
                    "CONTRACT_SCHEMA_COMPOSITION_UNSUPPORTED",
                    "Unsupported schema composition at " + path + " (oneOf/anyOf/allOf/not).",
                    "Simplify the schema to primitive, object, array, or enum types supported by smoke generation."
                ));
                return;
            }
            var ref = map.get("ref");
            var type = map.get("type");
            if (ref != null && !StringUtils.hasText(type == null ? null : String.valueOf(type)) && !map.containsKey("properties") && !map.containsKey("enum")) {
                diagnostics.add(ContractSmokeDiagnostic.unsupported(
                    "CONTRACT_SCHEMA_REF_UNSUPPORTED",
                    "Unresolved schema $ref at " + path + " without expanded type information.",
                    "Resolve or expand referenced schemas before running contract smoke."
                ));
                return;
            }
            var schemaType = type == null ? null : String.valueOf(type).toLowerCase(Locale.ROOT);
            if ("file".equals(schemaType) || "binary".equals(schemaType)) {
                diagnostics.add(ContractSmokeDiagnostic.unsupported(
                    "CONTRACT_SCHEMA_TYPE_UNSUPPORTED",
                    "Unsupported schema type '" + schemaType + "' at " + path + ".",
                    "Contract smoke currently supports string, number, integer, boolean, object and array only."
                ));
                return;
            }
            for (var entry : map.entrySet()) {
                walkUnsupported(entry.getValue(), path + "." + entry.getKey(), diagnostics);
                if (!diagnostics.isEmpty()) {
                    return;
                }
            }
        } else if (node instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                walkUnsupported(list.get(index), path + "[" + index + "]", diagnostics);
                if (!diagnostics.isEmpty()) {
                    return;
                }
            }
        }
    }

    private boolean hasBaseUrl(OpenApiContractSmokeRunRequest request) {
        var env = request.environmentVariables();
        return firstPresent(env, "baseUrl", "base_url", "BASE_URL") != null;
    }

    private boolean authRequired(ApiSpec apiSpec) {
        var auth = apiSpec.getAuth();
        if (auth == null || auth.isEmpty()) {
            return false;
        }
        if (Boolean.TRUE.equals(auth.get("required"))) {
            return true;
        }
        var type = auth.get("type");
        if (type != null && !"none".equalsIgnoreCase(String.valueOf(type))) {
            return true;
        }
        var schemes = auth.get("schemes");
        return schemes instanceof List<?> list && !list.isEmpty();
    }

    private boolean hasAuthCredentials(ApiSpec apiSpec, OpenApiContractSmokeRunRequest request) {
        var auth = apiSpec.getAuth() == null ? Map.<String, Object>of() : apiSpec.getAuth();
        var tokenVariable = String.valueOf(auth.getOrDefault("tokenVariable", "authToken"));
        if (request.authVariables().containsKey(tokenVariable)
            && request.authVariables().get(tokenVariable) != null
            && StringUtils.hasText(String.valueOf(request.authVariables().get(tokenVariable)))) {
            return true;
        }
        // OpenAPI scheme list without concrete token variable still requires authToken by convention.
        return request.authVariables().containsKey("authToken")
            && request.authVariables().get("authToken") != null
            && StringUtils.hasText(String.valueOf(request.authVariables().get("authToken")));
    }

    private Object firstPresent(Map<String, Object> values, String... keys) {
        for (var key : keys) {
            var value = values.get(key);
            if (value != null && StringUtils.hasText(String.valueOf(value))) {
                return value;
            }
        }
        return null;
    }
}
