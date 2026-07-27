package io.github.chada010.reposcout;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import io.github.chada010.reposcout.config.SecurityProperties;

@SpringBootApplication
@EnableConfigurationProperties(SecurityProperties.class)
public class RepoScoutApplication {

    public static void main(String[] args) {
        SpringApplication.run(RepoScoutApplication.class, args);
    }
}
