package com.probeflow.testagent.manualsuiteagent;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Locale;

public final class ManualSuiteAgentHarnessCli {

    private ManualSuiteAgentHarnessCli() {
    }

    public static void main(String[] args) {
        run(args, System.out);
    }

    static ManualSuiteAgentRunResult run(String[] args, PrintStream out) {
        var options = CliOptions.parse(args);
        var request = options.toRequest();
        var result = ManualSuiteAgentHarness.defaults().run(request);
        printSummary(result, out);
        return result;
    }

    private static void printSummary(ManualSuiteAgentRunResult result, PrintStream out) {
        out.println("ProbeFlow V3-1 Manual Suite Agent Harness");
        out.println("status=" + result.status().name());
        out.println("runId=" + result.runId());
        out.println("fixtureId=" + result.fixtureId());
        out.println("fixtureVersion=" + result.fixtureVersion());
        out.println("providerMode=" + result.providerMode().name());
        out.println("usesRealLlm=" + result.usesRealLlm());
        out.println("usesExternalHttp=" + result.usesExternalHttp());
        out.println("jsonReport=" + artifactPath(result, "JSON_REPORT"));
        out.println("markdownReport=" + artifactPath(result, "MARKDOWN_REPORT"));
        if (!result.diagnostics().isEmpty()) {
            out.println("diagnostics=");
            for (var diagnostic : result.diagnostics()) {
                out.println("- " + diagnostic.severity() + " " + diagnostic.code() + ": " + diagnostic.message());
            }
        }
    }

    private static String artifactPath(ManualSuiteAgentRunResult result, String artifactType) {
        return result.artifacts().stream()
            .filter(artifact -> artifact.artifactType().equals(artifactType))
            .findFirst()
            .map(ManualSuiteAgentArtifactReference::path)
            .orElse("<not-written>");
    }

    private record CliOptions(
        String fixtureId,
        Path outputDirectory,
        String providerMode,
        boolean allowManualRealLlm,
        String runProfile
    ) {

        static CliOptions parse(String[] args) {
            var fixtureId = "order-suite-demo";
            var outputDirectory = Path.of("target", "v3-manual-suite-agent");
            var providerMode = ManualSuiteAgentProviderMode.DETERMINISTIC_FAKE.name();
            var allowManualRealLlm = false;
            var runProfile = "local-demo";

            for (var arg : args == null ? new String[0] : args) {
                if (arg.startsWith("--fixture-id=")) {
                    fixtureId = arg.substring("--fixture-id=".length());
                } else if (arg.startsWith("--fixture=")) {
                    fixtureId = arg.substring("--fixture=".length());
                } else if (arg.startsWith("--output-dir=")) {
                    outputDirectory = Path.of(arg.substring("--output-dir=".length()));
                } else if (arg.startsWith("--provider-mode=")) {
                    providerMode = arg.substring("--provider-mode=".length());
                } else if (arg.startsWith("--run-profile=")) {
                    runProfile = arg.substring("--run-profile=".length());
                } else if ("--allow-manual-real-llm".equals(arg)) {
                    allowManualRealLlm = true;
                }
            }
            return new CliOptions(fixtureId, outputDirectory, providerMode, allowManualRealLlm, runProfile);
        }

        ManualSuiteAgentRunRequest toRequest() {
            var normalizedProviderMode = providerMode == null
                ? ManualSuiteAgentProviderMode.DETERMINISTIC_FAKE.name()
                : providerMode.trim().toUpperCase(Locale.ROOT);
            if (ManualSuiteAgentProviderMode.MANUAL_REAL_LLM.name().equals(normalizedProviderMode)) {
                return new ManualSuiteAgentRunRequest(
                    fixtureId,
                    ManualSuiteAgentProviderMode.MANUAL_REAL_LLM,
                    outputDirectory,
                    allowManualRealLlm,
                    false,
                    runProfile,
                    ManualSuiteAgentProviderMode.MANUAL_REAL_LLM.name()
                );
            }
            var provider = ManualSuiteAgentRunRequest.withProviderMode(fixtureId, providerMode, outputDirectory);
            return new ManualSuiteAgentRunRequest(
                provider.fixtureId(),
                provider.providerMode(),
                provider.outputDirectory(),
                provider.allowManualRealLlm(),
                provider.allowExternalHttp(),
                runProfile,
                provider.requestedProviderMode()
            );
        }
    }
}
