package com.probeflow.testagent.knowledge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeChunkingService {

    static final int TARGET_TOKEN_COUNT = 140;
    static final int OVERLAP_TOKEN_COUNT = 20;
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern MARKDOWN_LIST = Pattern.compile("^(?:[-*+]\\s+|\\d+\\.\\s+).+");
    private static final Pattern API_PATH = Pattern.compile("/(?:[A-Za-z0-9._~-]+|\\{[^}]+\\})(?:/(?:[A-Za-z0-9._~-]+|\\{[^}]+\\}))*");
    private static final Pattern HTTP_METHOD = Pattern.compile("\\b(GET|POST|PUT|PATCH|DELETE)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SYMBOLIC_ERROR_CODE = Pattern.compile("\\b[A-Z]{2,}_[A-Z0-9]{2,}\\b");
    private static final Pattern NUMERIC_ERROR_CODE = Pattern.compile("\\b\\d{3,5}\\b");

    public List<KnowledgeChunk> chunk(
        String documentId,
        String revisionId,
        KnowledgeIngestRequest request
    ) {
        var blocks = request.contentFormat() == KnowledgeContentFormat.MARKDOWN
            ? parseMarkdownBlocks(request)
            : parsePlainTextBlocks(request);

        var chunks = new ArrayList<KnowledgeChunk>();
        var order = 1;
        for (var block : blocks) {
            for (var segment : splitOversizedBlock(block)) {
                var chunk = new KnowledgeChunk();
                chunk.setDocumentId(documentId);
                chunk.setDocumentRevisionId(revisionId);
                chunk.setChunkStatus(ChunkStatus.ACTIVE);
                chunk.setChunkTitle(segment.title());
                chunk.setChunkContent(segment.content());
                chunk.setChunkOrder(order++);
                chunk.setTags(enrichTags(request, segment));
                chunk.setApplicableStages(enrichStages(request));
                chunk.setMetadata(chunkMetadata(request, segment));
                chunk.setTokenCount(estimateTokenCount(segment.content()));
                chunks.add(chunk);
            }
        }
        return chunks;
    }

    private List<ChunkBlock> parseMarkdownBlocks(KnowledgeIngestRequest request) {
        var lines = request.content().split("\\R", -1);
        var headerStack = new ArrayList<String>();
        var blocks = new ArrayList<ChunkBlock>();
        var paragraphBuffer = new ArrayList<String>();
        var listBuffer = new ArrayList<String>();
        var tableBuffer = new ArrayList<String>();
        var codeBuffer = new ArrayList<String>();
        var insideCodeBlock = false;

        for (String line : lines) {
            if (insideCodeBlock) {
                codeBuffer.add(line);
                if (line.startsWith("```")) {
                    blocks.add(createBlock(ChunkKind.CODE_BLOCK, headerStack, joinLines(codeBuffer), request.title()));
                    codeBuffer.clear();
                    insideCodeBlock = false;
                }
                continue;
            }

            var headingMatcher = MARKDOWN_HEADING.matcher(line);
            if (headingMatcher.matches()) {
                flushMarkdownBuffers(blocks, paragraphBuffer, listBuffer, tableBuffer, headerStack, request.title());
                var level = headingMatcher.group(1).length();
                while (headerStack.size() >= level) {
                    headerStack.removeLast();
                }
                headerStack.add(headingMatcher.group(2).trim());
                continue;
            }

            if (line.startsWith("```")) {
                flushMarkdownBuffers(blocks, paragraphBuffer, listBuffer, tableBuffer, headerStack, request.title());
                insideCodeBlock = true;
                codeBuffer.add(line);
                continue;
            }

            if (line.isBlank()) {
                flushMarkdownBuffers(blocks, paragraphBuffer, listBuffer, tableBuffer, headerStack, request.title());
                continue;
            }

            if (isMarkdownTableLine(line)) {
                flushList(blocks, listBuffer, headerStack, request.title());
                flushParagraph(blocks, paragraphBuffer, headerStack, request.title());
                tableBuffer.add(line);
                continue;
            }

            if (MARKDOWN_LIST.matcher(line).matches()) {
                flushTable(blocks, tableBuffer, headerStack, request.title());
                flushParagraph(blocks, paragraphBuffer, headerStack, request.title());
                listBuffer.add(line);
                continue;
            }

            flushList(blocks, listBuffer, headerStack, request.title());
            flushTable(blocks, tableBuffer, headerStack, request.title());
            paragraphBuffer.add(line);
        }

        flushMarkdownBuffers(blocks, paragraphBuffer, listBuffer, tableBuffer, headerStack, request.title());
        if (!codeBuffer.isEmpty()) {
            blocks.add(createBlock(ChunkKind.CODE_BLOCK, headerStack, joinLines(codeBuffer), request.title()));
        }
        return blocks;
    }

    private List<ChunkBlock> parsePlainTextBlocks(KnowledgeIngestRequest request) {
        var blocks = new ArrayList<ChunkBlock>();
        var parts = request.content().split("(?:\\R){2,}");
        for (var part : parts) {
            if (!part.isBlank()) {
                blocks.add(createBlock(ChunkKind.TEXT, List.of(request.title()), part.trim(), request.title()));
            }
        }
        return blocks;
    }

    private List<ChunkBlock> splitOversizedBlock(ChunkBlock block) {
        if (estimateTokenCount(block.content()) <= TARGET_TOKEN_COUNT) {
            return List.of(block);
        }

        var paragraphSegments = splitByDelimiter(block, "\n\n");
        if (paragraphSegments.size() > 1) {
            return flattenOversizedSegments(paragraphSegments);
        }

        var lineSegments = splitByDelimiter(block, "\n");
        if (lineSegments.size() > 1) {
            return flattenOversizedSegments(lineSegments);
        }

        var sentenceSegments = splitBySentence(block);
        if (sentenceSegments.size() > 1) {
            return flattenOversizedSegments(sentenceSegments);
        }

        return splitByFixedTokenWindow(block);
    }

    private List<ChunkBlock> flattenOversizedSegments(List<ChunkBlock> segments) {
        var flattened = new ArrayList<ChunkBlock>();
        for (var segment : segments) {
            flattened.addAll(splitOversizedBlock(segment));
        }
        return flattened;
    }

    private List<ChunkBlock> splitByDelimiter(ChunkBlock block, String delimiter) {
        var pieces = block.content().split(Pattern.quote(delimiter));
        if (pieces.length <= 1) {
            return List.of(block);
        }

        var result = new ArrayList<ChunkBlock>();
        var current = new StringBuilder();
        for (var piece : pieces) {
            var candidate = current.isEmpty() ? piece.trim() : current + delimiter + piece.trim();
            if (!current.isEmpty() && estimateTokenCount(candidate) > TARGET_TOKEN_COUNT) {
                result.add(block.withContent(current.toString().trim()));
                current = new StringBuilder(piece.trim());
                continue;
            }
            if (!candidate.isBlank()) {
                current = new StringBuilder(candidate);
            }
        }
        if (!current.isEmpty()) {
            result.add(block.withContent(current.toString().trim()));
        }
        return result.isEmpty() ? List.of(block) : result;
    }

    private List<ChunkBlock> splitBySentence(ChunkBlock block) {
        var sentences = block.content().split("(?<=[.!?])\\s+");
        if (sentences.length <= 1) {
            return List.of(block);
        }

        var result = new ArrayList<ChunkBlock>();
        var current = new StringBuilder();
        for (var sentence : sentences) {
            var trimmed = sentence.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            var candidate = current.isEmpty() ? trimmed : current + " " + trimmed;
            if (!current.isEmpty() && estimateTokenCount(candidate) > TARGET_TOKEN_COUNT) {
                result.add(block.withContent(current.toString().trim()));
                current = new StringBuilder(trimmed);
                continue;
            }
            current = new StringBuilder(candidate);
        }
        if (!current.isEmpty()) {
            result.add(block.withContent(current.toString().trim()));
        }
        return result.isEmpty() ? List.of(block) : result;
    }

    private List<ChunkBlock> splitByFixedTokenWindow(ChunkBlock block) {
        var words = Arrays.stream(block.content().trim().split("\\s+"))
            .filter(word -> !word.isBlank())
            .toList();
        if (words.size() <= TARGET_TOKEN_COUNT) {
            return List.of(block);
        }

        var result = new ArrayList<ChunkBlock>();
        var step = TARGET_TOKEN_COUNT - OVERLAP_TOKEN_COUNT;
        for (int start = 0; start < words.size(); start += step) {
            var end = Math.min(words.size(), start + TARGET_TOKEN_COUNT);
            result.add(block.withContent(String.join(" ", words.subList(start, end))));
            if (end == words.size()) {
                break;
            }
        }
        return result;
    }

    private Map<String, Object> chunkMetadata(KnowledgeIngestRequest request, ChunkBlock block) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.putAll(request.metadata());
        metadata.putIfAbsent("contentFormat", request.contentFormat().name());
        metadata.putIfAbsent("chunkKind", block.kind().name());
        metadata.putIfAbsent("headerPath", block.headerPath());
        metadata.putIfAbsent("parentChunkId", null);
        metadata.putIfAbsent("childChunkReady", true);
        metadata.putIfAbsent("apiPathHints", extractApiPathHints(block));
        metadata.putIfAbsent("httpMethodHints", extractHttpMethodHints(block));
        metadata.putIfAbsent("errorCodeHints", extractErrorCodeHints(block));
        metadata.putIfAbsent("bizEntityHints", extractBizEntityHints(request, block));
        metadata.putIfAbsent("docTypeTag", docTypeTag(request.documentType()));
        metadata.putIfAbsent("keywordTags", extractKeywordTags(request, block));
        return metadata;
    }

    private List<String> enrichTags(KnowledgeIngestRequest request, ChunkBlock block) {
        var tags = new LinkedHashSet<String>();
        tags.addAll(request.tags());
        tags.addAll(extractKeywordTags(request, block));
        var docTypeTag = docTypeTag(request.documentType());
        if (docTypeTag != null) {
            tags.add(docTypeTag);
        }
        return tags.stream().sorted().toList();
    }

    private List<String> enrichStages(KnowledgeIngestRequest request) {
        var stages = new LinkedHashSet<String>();
        stages.addAll(request.applicableStages());
        stages.addAll(defaultStages(request.documentType()));
        return stages.stream().sorted().toList();
    }

    private List<String> extractApiPathHints(ChunkBlock block) {
        return collectMatches(API_PATH, block.searchText());
    }

    private List<String> extractHttpMethodHints(ChunkBlock block) {
        if (extractApiPathHints(block).isEmpty()) {
            return List.of();
        }
        return collectMatches(HTTP_METHOD, block.searchText()).stream()
            .map(value -> value.toUpperCase(Locale.ROOT))
            .toList();
    }

    private List<String> extractErrorCodeHints(ChunkBlock block) {
        var hints = new LinkedHashSet<String>();
        hints.addAll(collectMatches(SYMBOLIC_ERROR_CODE, block.searchText()));
        hints.addAll(collectMatches(NUMERIC_ERROR_CODE, block.searchText()));
        return hints.stream().sorted().toList();
    }

    private List<String> extractBizEntityHints(KnowledgeIngestRequest request, ChunkBlock block) {
        var hints = new LinkedHashSet<String>();
        if (request.bizEntity() != null) {
            hints.add(request.bizEntity());
        }
        var lower = block.searchText().toLowerCase(Locale.ROOT);
        if (lower.contains("order")) {
            hints.add("order");
        }
        if (lower.contains("payment")) {
            hints.add("payment");
        }
        return hints.stream().sorted().toList();
    }

    private List<String> extractKeywordTags(KnowledgeIngestRequest request, ChunkBlock block) {
        var tags = new LinkedHashSet<String>();
        var lower = (request.title() + "\n" + block.searchText()).toLowerCase(Locale.ROOT);
        addTagWhen(tags, lower.contains("auth") || lower.contains("signature") || lower.contains("token"), "auth");
        addTagWhen(tags, lower.contains("payment") || lower.contains("/pay"), "payment");
        addTagWhen(tags, lower.contains("order"), "order");
        addTagWhen(tags, lower.contains("risk"), "risk");
        addTagWhen(tags, !extractErrorCodeHints(block).isEmpty() || request.documentType() == DocumentType.ERROR_CODE_GUIDE, "error-code");
        addTagWhen(tags, request.documentType() == DocumentType.TEST_SPEC, "test-spec");
        return tags.stream().sorted().toList();
    }

    private void addTagWhen(LinkedHashSet<String> tags, boolean condition, String tag) {
        if (condition) {
            tags.add(tag);
        }
    }

    private List<String> defaultStages(DocumentType documentType) {
        return switch (documentType) {
            case BUSINESS_FLOW, API_NOTE, DOMAIN_RULE -> List.of("api_analysis", "case_generation");
            case TEST_SPEC -> List.of("case_generation");
            case ENV_GUIDE, ERROR_CODE_GUIDE, INCIDENT_POSTMORTEM -> List.of("failure_analysis");
        };
    }

    private String docTypeTag(DocumentType documentType) {
        return switch (documentType) {
            case ERROR_CODE_GUIDE -> "error-code";
            case TEST_SPEC -> "test-spec";
            case ENV_GUIDE -> "env-guide";
            case INCIDENT_POSTMORTEM -> "incident";
            case BUSINESS_FLOW -> "business-flow";
            case API_NOTE -> "api-note";
            case DOMAIN_RULE -> "domain-rule";
        };
    }

    private List<String> collectMatches(Pattern pattern, String input) {
        var matches = new LinkedHashSet<String>();
        var matcher = pattern.matcher(input);
        while (matcher.find()) {
            matches.add(matcher.group());
        }
        return matches.stream().sorted().toList();
    }

    private int estimateTokenCount(String content) {
        var trimmed = content.trim();
        if (trimmed.isEmpty()) {
            return 0;
        }
        return (int) Arrays.stream(trimmed.split("\\s+"))
            .filter(token -> !token.isBlank())
            .count();
    }

    private void flushMarkdownBuffers(
        List<ChunkBlock> blocks,
        List<String> paragraphBuffer,
        List<String> listBuffer,
        List<String> tableBuffer,
        List<String> headerStack,
        String documentTitle
    ) {
        flushParagraph(blocks, paragraphBuffer, headerStack, documentTitle);
        flushList(blocks, listBuffer, headerStack, documentTitle);
        flushTable(blocks, tableBuffer, headerStack, documentTitle);
    }

    private void flushParagraph(
        List<ChunkBlock> blocks,
        List<String> paragraphBuffer,
        List<String> headerStack,
        String documentTitle
    ) {
        if (!paragraphBuffer.isEmpty()) {
            blocks.add(createBlock(ChunkKind.PARAGRAPH, headerStack, joinLines(paragraphBuffer), documentTitle));
            paragraphBuffer.clear();
        }
    }

    private void flushList(
        List<ChunkBlock> blocks,
        List<String> listBuffer,
        List<String> headerStack,
        String documentTitle
    ) {
        if (!listBuffer.isEmpty()) {
            blocks.add(createBlock(ChunkKind.LIST, headerStack, joinLines(listBuffer), documentTitle));
            listBuffer.clear();
        }
    }

    private void flushTable(
        List<ChunkBlock> blocks,
        List<String> tableBuffer,
        List<String> headerStack,
        String documentTitle
    ) {
        if (!tableBuffer.isEmpty()) {
            blocks.add(createBlock(ChunkKind.TABLE, headerStack, joinLines(tableBuffer), documentTitle));
            tableBuffer.clear();
        }
    }

    private ChunkBlock createBlock(ChunkKind kind, List<String> headerStack, String content, String documentTitle) {
        var headerPath = headerStack.isEmpty() ? List.of(documentTitle) : List.copyOf(headerStack);
        var title = headerPath.getLast();
        return new ChunkBlock(kind, title, content.trim(), headerPath);
    }

    private boolean isMarkdownTableLine(String line) {
        return line.contains("|");
    }

    private String joinLines(List<String> lines) {
        return String.join("\n", lines);
    }

    private enum ChunkKind {
        PARAGRAPH,
        LIST,
        TABLE,
        CODE_BLOCK,
        TEXT
    }

    private record ChunkBlock(
        ChunkKind kind,
        String title,
        String content,
        List<String> headerPath
    ) {
        private ChunkBlock withContent(String nextContent) {
            return new ChunkBlock(kind, title, nextContent, headerPath);
        }

        private String searchText() {
            return String.join("\n", headerPath) + "\n" + content;
        }
    }
}
