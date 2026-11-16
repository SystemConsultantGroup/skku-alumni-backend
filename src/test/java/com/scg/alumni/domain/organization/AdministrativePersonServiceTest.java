package com.scg.alumni.domain.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@Transactional
class AdministrativePersonServiceTest {
    @Autowired AdministrativePersonService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;

    @Test
    void newPersonUsesPhoneSuffixAndHasHashedPassword() {
        long id = service.create("Example", null, "010-9999-4312", true);
        assertThat(service.loginId(id)).isEqualTo("Example4312");
        String password = jdbc.queryForObject("select password from users where id = ?", String.class, id);
        assertThat(password).isNotEqualTo("1111");
        assertThat(encoder.matches("1111", password)).isTrue();
        assertThat(service.candidates(null, null, "01099994312")).hasSize(1);
    }

    @Test
    void creationRequiresReviewAndRejectsKnownIdentifier() {
        assertThatThrownBy(() -> service.create("Example", null, null, false)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.create("Other", "1998310001", null, true)).isInstanceOf(ResponseStatusException.class);
    }
}
