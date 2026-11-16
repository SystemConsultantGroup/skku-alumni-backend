package com.scg.alumni.domain.officer;

import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("officer-term")
public record OfficerTermPolicyProperties(@DefaultValue("5") int startMonth, @DefaultValue("1") int startDay,
        @DefaultValue("2") int phasesPerGeneration, @DefaultValue("7") int accessGraceDays,
        @DefaultValue("Asia/Seoul") ZoneId zone) {
    public OfficerTermPolicyProperties {
        MonthDay.of(startMonth, startDay);
        if (phasesPerGeneration < 1 || accessGraceDays < 0 || zone == null) {
            throw new IllegalArgumentException("Invalid officer term policy");
        }
    }

    public LocalDate nextEnd(LocalDate startsAt) {
        MonthDay start = MonthDay.of(startMonth, startDay);
        LocalDate nextBoundary = start.atYear(startsAt.getYear());
        if (!nextBoundary.isAfter(startsAt)) nextBoundary = start.atYear(startsAt.getYear() + 1);
        return nextBoundary.minusDays(1);
    }
}
