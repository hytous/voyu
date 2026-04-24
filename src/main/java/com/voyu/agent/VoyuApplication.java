package com.voyu.agent;

import com.voyu.agent.config.MiddlewareProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(MiddlewareProperties.class)
public class VoyuApplication {

    public static void main(String[] args) {
        SpringApplication.run(VoyuApplication.class, args);
    }
}
