package com.probeflow.testagent.demorun;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v4/demo-runs")
public class DemoRunController {

    private static final Set<String> ALLOWED_RUN_PROFILES = Set.of(
        "local-demo",
        "manual-real-llm",
        "comparison-demo"
    );
    private static final Set<String> ALLOWED_OUTPUT_FORMATS = Set.of("JSON_REPORT", "MARKDOWN_REPORT");

    private final DemoRunApplicationService demoRuns;

    public DemoRunController(DemoRunApplicationService demoRuns) {
        this.demoRuns = demoRuns;
    }

    @PostMapping
    public ResponseEntity<?> run(@RequestBody(required = false) DemoRunApiRequest request) {
        if (request == null) {
            return badRequest(DemoRunApiErrorResponse.validation(
                "MISSING_REQUEST",
                "body",
                "Demo run request body is required."
            ));
        }
        if (isBlank(request.fixtureId())) {
            return badRequest(DemoRunApiErrorResponse.validation(
                "MISSING_FIXTURE_ID",
                "fixtureId",
                "fixtureId is required for the local demo API."
            ));
        }

        var providerMode = parseProviderMode(request.providerMode());
        if (providerMode == null) {
            return badRequest(new DemoRunApiErrorResponse(
                "INVALID_PROVIDER_MODE",
                "VALIDATION",
                "providerMode",
                "providerMode must be one of: FAKE, REAL, COMPARISON.",
                Map.of("requestedProviderMode", request.providerMode())
            ));
        }

        var runProfile = normalizeRunProfile(request.runProfile());
        if (!ALLOWED_RUN_PROFILES.contains(runProfile)) {
            return badRequest(new DemoRunApiErrorResponse(
                "INVALID_RUN_PROFILE",
                "VALIDATION",
                "runProfile",
                "runProfile must be one of: local-demo, manual-real-llm, comparison-demo.",
                Map.of("requestedRunProfile", request.runProfile())
            ));
        }

        var outputFormats = normalizeOutputFormats(request.outputFormats());
        var invalidOutputFormats = outputFormats.stream()
            .filter(format -> !ALLOWED_OUTPUT_FORMATS.contains(format))
            .toList();
        if (!invalidOutputFormats.isEmpty()) {
            return badRequest(new DemoRunApiErrorResponse(
                "INVALID_OUTPUT_FORMAT",
                "VALIDATION",
                "outputFormats",
                "outputFormats may contain only JSON_REPORT and MARKDOWN_REPORT.",
                Map.of("invalidOutputFormats", invalidOutputFormats)
            ));
        }

        var outputDirectory = outputDirectory(request.outputDirectory());
        if (outputDirectory == null) {
            return badRequest(new DemoRunApiErrorResponse(
                "INVALID_OUTPUT_DIRECTORY",
                "VALIDATION",
                "outputDirectory",
                "outputDirectory is not a valid local filesystem path.",
                Map.of("requestedOutputDirectory", request.outputDirectory())
            ));
        }

        var result = demoRuns.run(new DemoRunRequest(
            request.fixtureId(),
            providerMode,
            runProfile,
            outputDirectory,
            providerMode == DemoRunProviderMode.COMPARISON || Boolean.TRUE.equals(request.comparison()),
            Boolean.TRUE.equals(request.allowMemoryWrite()),
            outputFormats
        ));
        return ResponseEntity.ok(result);
    }

    private ResponseEntity<DemoRunApiErrorResponse> badRequest(DemoRunApiErrorResponse error) {
        return ResponseEntity.badRequest().body(error);
    }

    private DemoRunProviderMode parseProviderMode(String value) {
        if (isBlank(value)) {
            return DemoRunProviderMode.FAKE;
        }
        try {
            return DemoRunProviderMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String normalizeRunProfile(String runProfile) {
        return isBlank(runProfile) ? "local-demo" : runProfile.trim();
    }

    private List<String> normalizeOutputFormats(List<String> outputFormats) {
        if (outputFormats == null || outputFormats.isEmpty()) {
            return List.of("JSON_REPORT", "MARKDOWN_REPORT");
        }
        return outputFormats.stream()
            .filter(format -> !isBlank(format))
            .map(format -> format.trim().toUpperCase(Locale.ROOT))
            .toList();
    }

    private Path outputDirectory(String outputDirectory) {
        if (isBlank(outputDirectory)) {
            return Path.of("target", "v4-demo-run");
        }
        try {
            return Path.of(outputDirectory.trim());
        } catch (InvalidPathException exception) {
            return null;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
