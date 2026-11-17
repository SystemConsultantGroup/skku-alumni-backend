package com.scg.alumni;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.scg.alumni.domain.officer.OfficerTermPolicyProperties;

@EnableConfigurationProperties(OfficerTermPolicyProperties.class)
@SpringBootApplication
public class AlumniApplication {

    public static void main(String[] args) {
        SpringApplication.run(AlumniApplication.class, args);
    }
}
