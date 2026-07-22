package com.scg.alumni.api.common;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MarkdownImageExtractor {

    private static final Pattern IMAGE_PATTERN = Pattern.compile(
            "!\\[[^]]*]\\(([^\\s)]+)(?:\\s+[\\\"'][^\\\"']*[\\\"'])?\\)");
    private static final Pattern SAFE_URL_PATTERN = Pattern.compile("^(https?://|/).+", Pattern.CASE_INSENSITIVE);

    private MarkdownImageExtractor() {
    }

    public static String firstImageUrl(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return null;
        }
        Matcher matcher = IMAGE_PATTERN.matcher(markdown);
        if (!matcher.find()) {
            return null;
        }
        String url = matcher.group(1).replaceAll("^<|>$", "");
        return SAFE_URL_PATTERN.matcher(url).matches() ? url : null;
    }
}
