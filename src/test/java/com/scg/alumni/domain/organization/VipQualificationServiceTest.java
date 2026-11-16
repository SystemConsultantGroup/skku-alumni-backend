package com.scg.alumni.domain.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@Transactional
class VipQualificationServiceTest {
    @Autowired VipQualificationService service;

    @Test
    void grantsAndRevocationsPreserveHistory() {
        service.grant(1L);
        assertThatThrownBy(() -> service.grant(1L)).isInstanceOf(ResponseStatusException.class);
        service.revoke(1L);
        service.grant(1L);
        assertThat(service.history(1L, null, 20)).hasSize(2);
    }
}
