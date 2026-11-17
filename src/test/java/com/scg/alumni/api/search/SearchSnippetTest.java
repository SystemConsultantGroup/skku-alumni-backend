package com.scg.alumni.api.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SearchSnippetTest {
    @Test void centersOnLaterMatchAndMarksBothTruncatedEnds() {
        String body = "앞".repeat(300) + "장학금" + "뒤".repeat(300);
        String result = SearchSnippet.from(body, "장학금");
        assertThat(result).startsWith("…").endsWith("…").contains("장학금");
        assertThat(result.codePointCount(0, result.length())).isEqualTo(202);
    }

    @Test void fallsBackToStartForTitleAuthorOrPartialNgramMatches() {
        String body = "가".repeat(220);
        assertThat(SearchSnippet.from(body, "없는 검색어")).isEqualTo("가".repeat(200) + "…");
        assertThat(SearchSnippet.from(null, "검색")).isEmpty();
    }

    @Test void ignoresCaseAndDoesNotSplitSupplementaryCharacters() {
        String body = "😀".repeat(300) + "Scholarship" + "🙂".repeat(300);
        String result = SearchSnippet.from(body, "sCHOLARSHIP");
        assertThat(result).contains("Scholarship").doesNotContain("\uFFFD");
        assertThat(result.codePoints().noneMatch(c -> c >= 0xD800 && c <= 0xDFFF)).isTrue();
    }

    @Test void showsEndContextWithoutUnnecessaryTrailingEllipsis() {
        String body = "앞".repeat(300) + "마지막";
        assertThat(SearchSnippet.from(body, "마지막")).startsWith("…").endsWith("마지막");
    }
}
