package com.example.springload;

import com.example.springload.config.TaskProcessingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(TaskProcessingProperties.class)
public class SpringLoadApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringLoadApplication.class, args);
    }
}
