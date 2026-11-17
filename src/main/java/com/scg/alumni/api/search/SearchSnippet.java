package com.scg.alumni.api.search;

/** Produces a plain-text excerpt without changing the stored body. */
final class SearchSnippet {
    private static final int MAX_CODE_POINTS = 200;
    private static final int CONTEXT_BEFORE = 60;

    private SearchSnippet() { }

    static String from(String body, String keyword) {
        if (body == null || body.isEmpty()) return "";
        int match = indexOfIgnoreCase(body, keyword);
        int length = body.codePointCount(0, body.length());
        int matchPosition = match < 0 ? 0 : body.codePointCount(0, match);
        int start = Math.max(0, matchPosition - CONTEXT_BEFORE);
        start = Math.min(start, Math.max(0, length - MAX_CODE_POINTS));
        int end = Math.min(length, start + MAX_CODE_POINTS);
        String excerpt = body.substring(body.offsetByCodePoints(0, start), body.offsetByCodePoints(0, end));
        return (start > 0 ? "…" : "") + excerpt + (end < length ? "…" : "");
    }

    private static int indexOfIgnoreCase(String body, String keyword) {
        if (keyword == null || keyword.isEmpty()) return -1;
        for (int offset = 0; offset <= body.length() - keyword.length();) {
            if (body.regionMatches(true, offset, keyword, 0, keyword.length())) return offset;
            offset += Character.charCount(body.codePointAt(offset));
        }
        return -1;
    }
}
