package com.example.vod;

import com.example.vod.config.PlaySignProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * vod-api 主类。
 *
 * <p>不显式加 @MapperScan：MyBatis Spring Boot Starter 的自动扫描会以
 * @SpringBootApplication 所在包（com.example.vod）为基包递归扫描 @Mapper 接口，
 * com.example.vod.common.domain.media 在其子包下，会被自动注册。
 * 显式 @MapperScan 反而会破坏 @WebMvcTest 这类切片测试
 * （切片上下文没有 DataSource / SqlSessionFactory，mapper bean 会初始化失败）。
 */
@SpringBootApplication
@EnableConfigurationProperties(PlaySignProperties.class)
public class VodApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(VodApiApplication.class, args);
    }
}
