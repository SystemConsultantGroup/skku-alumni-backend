package com.scg.alumni.domain.academic;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 회원에게 보여줄 학과명과, 같은 학과인지 판단할 열쇠.
 *
 * <p>학과 이름은 두 가지 이유로 저장된 값 그대로 쓸 수 없다.
 *
 * <p>하나. 이름이 바뀐 학과는 현재 이름으로 보여야 한다. 산업공학과를 나온 동문의
 * 졸업장에는 산업공학과가 찍히지만, 앱에서 서로를 찾을 때는 지금 이름으로 묶여야 한다.
 *
 * <p>둘. 야간 표기는 지운다. 주간·야간은 같은 학과이고, 동문끼리 보는 화면에 야간
 * 여부를 드러낼 이유가 없다. 총동창회에서도 이 표기는 내보내지 않기로 했다.
 *
 * <p>규칙을 자바에 둔다. 같은 규칙을 JPA 로 읽는 자리({@link Major#getDisplayName()})와
 * JDBC 로 읽는 자리가 함께 쓰는데, SQL 쪽에만 적어두면 두 벌이 되어 갈라진다 —
 * 실제로 동문 검색 목록만 이어받은 학과를 따르고 내 정보·동문 상세·동호회 명단은
 * 저장된 이름을 그대로 내보내고 있었다.
 */
public final class MajorNames {

    /** 데이터에 섞여 들어오는 야간 표기. 괄호 형태가 제각각이라 모두 지운다. */
    private static final List<String> NIGHT_MARKERS = List.of("(야)", "(야간)", "（야）", "(야간과정)");

    /** 조회 결과에서 학과명이 담기는 칸. */
    private static final String MAJOR_NAME = "majorName";

    private MajorNames() {
    }

    /**
     * 학과명을 읽어올 SQL 조각. 이어받은 학과를 따른다.
     *
     * <p>야간 표기는 여기서 지우지 않는다 — {@link #hideNightMarkers} 로 마무리한다.
     *
     * @param major        학과 테이블 별칭
     * @param displayMajor 그 학과가 이어받게 한 학과의 별칭 (left join 되어 있어야 한다)
     */
    public static String displayNameColumn(String major, String displayMajor) {
        return "coalesce(" + displayMajor + ".name, " + major + ".name)";
    }

    /**
     * 같은 학과인지 판단할 열쇠.
     *
     * <p>이어받은 학과를 따른 이름에서 야간 표기와 공백을 지우고 대소문자를 맞춘다.
     * 이렇게 해야 '산업공학과'와 '시스템경영공학과', '(야)법학과'와 '법학과'가 같은
     * 학과로 묶인다.
     *
     * <p>화면에 보여줄 이름과 같은 규칙으로 계산해야 한다. 보이는 것과 찾아지는 것이
     * 다르면, 목록에서 고른 학과로 본인이 안 찾아진다.
     */
    public static String canonicalKey(String displayName) {
        return stripNightMarkers(displayName, displayName)
                .replaceAll("\\s+", "")
                .toLowerCase(java.util.Locale.ROOT);
    }

    /** 조회 결과 한 줄의 학과명에서 야간 표기를 지운다. */
    public static Map<String, Object> hideNightMarkers(Map<String, Object> row) {
        if (row != null && row.get(MAJOR_NAME) instanceof String majorName) {
            row.put(MAJOR_NAME, stripNightMarkers(majorName, majorName));
        }
        return row;
    }

    /** 조회 결과 여러 줄의 학과명에서 야간 표기를 지운다. */
    public static <T extends Collection<Map<String, Object>>> T hideNightMarkers(T rows) {
        if (rows != null) {
            rows.forEach(MajorNames::hideNightMarkers);
        }
        return rows;
    }

    /** 이름에서 야간 표기를 지운다. 지우고 나면 아무것도 남지 않는 경우 fallback 을 쓴다. */
    public static String stripNightMarkers(String name, String fallback) {
        if (name == null) {
            return fallback;
        }
        String stripped = name;
        for (String marker : NIGHT_MARKERS) {
            stripped = stripped.replace(marker, "");
        }
        stripped = stripped.trim();
        return stripped.isEmpty() ? fallback : stripped;
    }
}
