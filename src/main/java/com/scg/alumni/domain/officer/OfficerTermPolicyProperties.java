package com.scg.alumni.domain.officer;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("officer-term")
public record OfficerTermPolicyProperties(@DefaultValue("7") int accessGraceDays,
                                          @DefaultValue("Asia/Seoul") ZoneId zone) {
    public OfficerTermPolicyProperties {
        if (accessGraceDays < 0 || zone == null) {
            throw new IllegalArgumentException("Invalid officer term policy");
        }
    }

}
