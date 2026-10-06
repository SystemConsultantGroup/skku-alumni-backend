package com.scg.alumni.domain.inquiry;

import java.util.Arrays;
import java.util.Optional;

/**
 * 문의 분류. 서비스의 실제 기능(계정·회비·임원 명부·커뮤니티·앱)에 맞춰 여섯 개로 묶었다.
 * 코드값은 DB 에 저장되고 화면 드롭다운과 알림 메일이 같은 이름을 쓴다.
 */
public enum InquiryCategory {
    ACCOUNT("가입·로그인·계정"),
    DUES("회비·납부"),
    MEMBER_INFO("임원 정보·프로필"),
    COMMUNITY("동호회·커뮤니티·비즈니스"),
    APP_ERROR("앱 오류·사용 불편"),
    OTHER("기타·건의");

    private final String label;

    InquiryCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static Optional<InquiryCategory> from(String code) {
        return code == null ? Optional.empty()
                : Arrays.stream(values()).filter(category -> category.name().equals(code.trim())).findFirst();
    }

    /** 저장된 코드를 사람이 읽는 이름으로. 모르는 값은 그대로 둔다. */
    public static String labelOf(String code) {
        return from(code).map(InquiryCategory::label).orElse(code);
    }
}
