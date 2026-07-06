package com.probeflow.testagent.agentpolicy;

public final class ToolNames {

    public static final ToolName API_ANALYZE_SOURCE = ToolName.of("api.analyze-source");
    public static final ToolName KNOWLEDGE_RETRIEVE_CONTEXT = ToolName.of("knowledge.retrieve-context");
    public static final ToolName MEMORY_BUILD_CONTEXT = ToolName.of("memory.build-context");
    public static final ToolName TESTCASE_GENERATE_DRAFTS = ToolName.of("testcase.generate-drafts");
    public static final ToolName TESTCASE_REVIEW_DRAFT = ToolName.of("testcase.review-draft");
    public static final ToolName HTTP_EXECUTE_APPROVED_CASE = ToolName.of("http.execute-approved-case");
    public static final ToolName FAILURE_ANALYZE_EXECUTION = ToolName.of("failure.analyze-execution");
    public static final ToolName REPORT_GENERATE_TASK = ToolName.of("report.generate-task");

    private ToolNames() {
    }
}
