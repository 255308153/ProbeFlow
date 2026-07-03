package com.probeflow.testagent.knowledge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeChunkingService {

    static final int TARGET_TOKEN_COUNT = 140;
    static final int OVERLAP_TOKEN_COUNT = 20;
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern MARKDOWN_LIST = Pattern.compile("^(?:[-*+]\\s+|\\d+\\.\\s+).+");

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
                chunk.setTags(request.tags());
                chunk.setApplicableStages(request.applicableStages());
                chunk.setMetadata(chunkMetadata(request.contentFormat(), segment));
                chunk.setTokenCount(estimateTokenCount(segment.content()));
                chunk.setEmbedding(zeroEmbedding());
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

    private Map<String, Object> chunkMetadata(KnowledgeContentFormat contentFormat, ChunkBlock block) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("contentFormat", contentFormat.name());
        metadata.put("chunkKind", block.kind().name());
        metadata.put("headerPath", block.headerPath());
        metadata.put("parentChunkId", null);
        metadata.put("childChunkReady", true);
        return metadata;
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

    private float[] zeroEmbedding() {
        return new float[1024];
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
    }
}
