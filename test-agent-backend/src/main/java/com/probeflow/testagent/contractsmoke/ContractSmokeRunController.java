package com.probeflow.testagent.contractsmoke;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/contract-smoke-runs")
public class ContractSmokeRunController {

    private final OpenApiContractSmokeRunApplicationService smokeRuns;

    public ContractSmokeRunController(OpenApiContractSmokeRunApplicationService smokeRuns) {
        this.smokeRuns = smokeRuns;
    }

    /**
     * Minimal internal trial entry for a single ApiSpec contract smoke run.
     *
     * <pre>
     * POST /api/contract-smoke-runs
     * {
     *   "apiSpecId": "...",
     *   "environment": "test",
     *   "profile": "smoke-valid",
     *   "environmentVariables": { "baseUrl": "https://api.example.test" },
     *   "authVariables": { "authToken": "..." },
     *   "requestedBy": "trial-user"
     * }
     * </pre>
     */
    @PostMapping
    public ResponseEntity<?> run(@RequestBody(required = false) OpenApiContractSmokeRunRequest request) {
        try {
            if (request == null) {
                return ResponseEntity.badRequest().body(error(
                    "CONTRACT_SMOKE_REQUEST_REQUIRED",
                    "VALIDATION",
                    "body",
                    "Request body is required for contract smoke run."
                ));
            }
            return ResponseEntity.ok(smokeRuns.run(request));
        } catch (IllegalArgumentException exception) {
            var message = exception.getMessage() == null ? "Invalid contract smoke request." : exception.getMessage();
            if (message.toLowerCase().contains("not found")) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error(
                    "CONTRACT_SMOKE_NOT_FOUND",
                    "NOT_FOUND",
                    fieldFromMessage(message),
                    message
                ));
            }
            return ResponseEntity.badRequest().body(error(
                "CONTRACT_SMOKE_INVALID_REQUEST",
                "VALIDATION",
                fieldFromMessage(message),
                message
            ));
        }
    }

    private Map<String, Object> error(String code, String type, String field, String message) {
        var body = new LinkedHashMap<String, Object>();
        body.put("code", code);
        body.put("type", type);
        body.put("field", field);
        body.put("message", message);
        return body;
    }

    private String fieldFromMessage(String message) {
        if (message == null) {
            return "request";
        }
        var lower = message.toLowerCase();
        if (lower.contains("apispec")) {
            return "apiSpecId";
        }
        if (lower.contains("task")) {
            return "taskId";
        }
        return "request";
    }
}
