package com.rcszh.nettydemo;

import com.rcszh.nettydemo.tcp.NettyServerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Spring Boot 启动类。
 */
@SpringBootApplication
@EnableConfigurationProperties(NettyServerProperties.class)
public class NettyDemoApplication {

    /**
     * 应用入口。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(NettyDemoApplication.class, args);
    }
}
