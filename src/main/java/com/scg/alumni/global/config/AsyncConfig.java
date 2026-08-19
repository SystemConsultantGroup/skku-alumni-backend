package com.scg.alumni.global.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 푸시 발송처럼 응답을 붙잡을 이유가 없는 작업을 돌리는 풀.
 *
 * <p>회원이 수천 명이 되면 알림 발송에 몇 분이 걸린다. 글쓰기 응답이 그동안
 * 기다릴 이유가 없다.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("alumni-async-");
        executor.initialize();
        return executor;
    }
}
