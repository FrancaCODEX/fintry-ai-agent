package com.fintry.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(
    exclude =
        org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
            .class)
public class FintryAgentApplication {
  public static void main(String[] args) {
    SpringApplication.run(FintryAgentApplication.class, args);
  }
}
