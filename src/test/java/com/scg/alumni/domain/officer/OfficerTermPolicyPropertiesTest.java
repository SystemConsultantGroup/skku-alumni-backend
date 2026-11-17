package com.scg.alumni.domain.officer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class OfficerTermPolicyPropertiesTest {
    @Test
    void rejectsInvalidPolicy() {
        assertThatThrownBy(() -> new OfficerTermPolicyProperties(-1, ZoneId.of("Asia/Seoul")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
