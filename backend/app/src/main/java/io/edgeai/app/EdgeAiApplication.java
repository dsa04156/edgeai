package io.edgeai.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class)
public class EdgeAiApplication {
    public static void main(String[] args) {
        SpringApplication.run(EdgeAiApplication.class, args);
    }
}
