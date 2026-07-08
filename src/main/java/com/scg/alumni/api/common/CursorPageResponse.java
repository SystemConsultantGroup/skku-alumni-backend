package com.scg.alumni.api.common;

import java.util.List;

public record CursorPageResponse<T>(
        List<T> items,
        Long nextCursor,
        boolean hasNext
) {
}
