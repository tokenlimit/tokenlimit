package com.tokenlimit.server;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * TokenLimit Server 启动类.
 */
@SpringBootApplication
@MapperScan("com.tokenlimit.server.repository.mapper")
public class TokenLimitServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TokenLimitServerApplication.class, args);
    }
}
