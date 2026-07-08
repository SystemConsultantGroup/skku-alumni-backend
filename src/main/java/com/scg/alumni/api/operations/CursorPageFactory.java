package com.scg.alumni.api.operations;

import com.scg.alumni.api.common.CursorPageResponse;
import java.util.List;
import java.util.Map;

final class CursorPageFactory {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 50;

    private CursorPageFactory() {
    }

    static CursorPageResponse<Map<String, Object>> from(List<Map<String, Object>> fetchedRows, Integer requestedSize) {
        int pageSize = normalizeSize(requestedSize);
        boolean hasNext = fetchedRows.size() > pageSize;
        List<Map<String, Object>> items = hasNext ? fetchedRows.subList(0, pageSize) : fetchedRows;
        Long nextCursor = null;

        if (hasNext && !items.isEmpty()) {
            Object id = items.get(items.size() - 1).get("id");
            if (id instanceof Number number) {
                nextCursor = number.longValue();
            }
        }

        return new CursorPageResponse<>(items, nextCursor, hasNext);
    }

    static int queryLimit(Integer requestedSize) {
        return normalizeSize(requestedSize) + 1;
    }

    private static int normalizeSize(Integer size) {
        if (size == null) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }
}
