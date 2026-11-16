package com.scg.alumni.domain.officer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class OfficerTermPolicyPropertiesTest {
    @Test
    void createsNextBoundaryWithoutRewritingExistingTerm() {
        OfficerTermPolicyProperties normal = new OfficerTermPolicyProperties(5, 1, 2, 7, ZoneId.of("Asia/Seoul"));
        assertThat(normal.nextEnd(LocalDate.of(2027, 5, 1))).isEqualTo(LocalDate.of(2028, 4, 30));
        OfficerTermPolicyProperties changed = new OfficerTermPolicyProperties(7, 1, 2, 7, ZoneId.of("Asia/Seoul"));
        assertThat(changed.nextEnd(LocalDate.of(2027, 5, 1))).isEqualTo(LocalDate.of(2027, 6, 30));
    }

    @Test
    void rejectsInvalidPolicy() {
        assertThatThrownBy(() -> new OfficerTermPolicyProperties(5, 1, 0, 7, ZoneId.of("Asia/Seoul")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
