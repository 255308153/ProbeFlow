package com.probeflow.testagent.suiteruntime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class PathAccess {

    private PathAccess() {
    }

    static PathResult resolve(Object root, String path) {
        if (root == null) {
            return PathResult.missing("ROOT_MISSING");
        }
        if (path == null || path.isBlank()) {
            return PathResult.found(root);
        }
        var current = root;
        List<PathToken> tokens;
        try {
            tokens = parse(path);
        } catch (IllegalArgumentException exception) {
            return PathResult.missing("INVALID_PATH_SYNTAX");
        }
        for (var token : tokens) {
            if (!token.field().isBlank()) {
                if (!(current instanceof Map<?, ?> map) || !map.containsKey(token.field())) {
                    return PathResult.missing("PATH_MISSING");
                }
                current = map.get(token.field());
            }
            for (var index : token.indexes()) {
                if (!(current instanceof List<?> list)) {
                    return PathResult.missing("PATH_TYPE_MISMATCH");
                }
                if (index < 0 || index >= list.size()) {
                    return PathResult.missing("ARRAY_INDEX_OUT_OF_BOUNDS");
                }
                current = list.get(index);
            }
        }
        return PathResult.found(current);
    }

    private static List<PathToken> parse(String path) {
        var tokens = new ArrayList<PathToken>();
        for (var rawSegment : path.split("\\.")) {
            if (rawSegment.isBlank()) {
                continue;
            }
            var fieldEnd = rawSegment.indexOf('[');
            var field = fieldEnd < 0 ? rawSegment : rawSegment.substring(0, fieldEnd);
            var indexes = new ArrayList<Integer>();
            var cursor = fieldEnd < 0 ? rawSegment.length() : fieldEnd;
            while (cursor < rawSegment.length()) {
                var open = rawSegment.indexOf('[', cursor);
                var close = rawSegment.indexOf(']', open + 1);
                if (open < 0 || close < 0) {
                    throw new IllegalArgumentException("Invalid array selector");
                }
                indexes.add(Integer.parseInt(rawSegment.substring(open + 1, close)));
                cursor = close + 1;
            }
            tokens.add(new PathToken(field, indexes));
        }
        return tokens;
    }

    record PathResult(boolean found, Object value, String failureCode) {
        static PathResult found(Object value) {
            return new PathResult(true, value, null);
        }

        static PathResult missing(String failureCode) {
            return new PathResult(false, null, failureCode);
        }
    }

    private record PathToken(String field, List<Integer> indexes) {
    }
}
