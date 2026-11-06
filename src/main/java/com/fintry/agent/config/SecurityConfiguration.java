package com.fintry.agent.config;

import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
  @Bean
  SecurityFilterChain security(HttpSecurity http) throws Exception {
    // Authentication of financial queries is delegated to the validated Fintry session.
    // Agent CSRF protection remains active even though the downstream tools are read-only.
    return http.authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        HttpMethod.GET,
                        "/",
                        "/index.html",
                        "/favicon.svg",
                        "/style.css",
                        "/app.js",
                        "/api/context",
                        "/api/csrf",
                        "/health")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/chat")
                    .permitAll()
                    .anyRequest()
                    .denyAll())
        .headers(
            h ->
                h.contentSecurityPolicy(
                    c ->
                        c.policyDirectives(
                            "default-src 'self'; script-src 'self'; style-src 'self'; connect-src"
                                + " 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri"
                                + " 'self'; form-action 'self'")))
        .exceptionHandling(
            e ->
                e.accessDeniedHandler(
                    (req, res, ex) -> {
                      res.setStatus(403);
                      res.setContentType("application/json;charset=UTF-8");
                      res.getWriter()
                          .write(
                              "{\"message\":\"Recarregue a página e confira sua sessão de"
                                  + " segurança.\"}");
                    }))
        .build();
  }
}
