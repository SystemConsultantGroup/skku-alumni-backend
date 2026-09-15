package com.scg.alumni.global.security;

/**
 * 비밀번호가 지켜야 할 조건. 계정 만들기·본인 변경·사무처 초기화가 같은 규칙을 쓴다.
 *
 * <p>규칙이 세 군데에 따로 적혀 있으면 하나만 고쳐지고 나머지는 남는다. 계정
 * 만들기에서 막은 비밀번호를 초기화로는 넣을 수 있게 되면 규칙이 없는 것과 같다.
 *
 * <p>애너테이션 값으로 쓰이므로 상수여야 한다.
 */
public final class PasswordPolicy {

    public static final String PATTERN = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{8,}$";
    public static final String MESSAGE = "비밀번호는 8자 이상이며 영문, 숫자, 특수문자를 포함해야 합니다.";

    private PasswordPolicy() {
    }
}
