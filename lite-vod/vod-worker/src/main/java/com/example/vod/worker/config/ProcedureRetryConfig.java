package com.example.vod.worker.config;

import com.example.vod.common.messaging.ProcedureRetry;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 按 Worker 的 maxAttempts 声明延迟重试队列。等待队列不挂消费者。
 */
@Configuration
public class ProcedureRetryConfig {

    @Bean
    public Declarables procedureRetryDeclarables(WorkerProperties props) {
        return ProcedureRetry.declarables(props.maxAttempts());
    }
}
