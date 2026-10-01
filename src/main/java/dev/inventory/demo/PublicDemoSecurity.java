package dev.inventory.demo;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@Profile("public-demo")
public class PublicDemoSecurity {
  @Bean
  @Order(0)
  SecurityFilterChain publicDemoFilterChain(HttpSecurity http) throws Exception {
    return http.authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        HttpMethod.GET,
                        "/",
                        "/demo.html",
                        "/demo.js",
                        "/demo-client.js",
                        "/demo.css",
                        "/api/csrf",
                        "/api/demo/status",
                        "/api/demo/manual",
                        "/actuator/health")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/demo/run/*",
                        "/api/demo/manual",
                        "/api/demo/manual/actions")
                    .permitAll()
                    .anyRequest()
                    .denyAll())
        .requestCache(cache -> cache.disable())
        .logout(logout -> logout.disable())
        .headers(
            headers ->
                headers.contentSecurityPolicy(
                    csp ->
                        csp.policyDirectives(
                            "default-src 'self'; script-src 'self'; style-src 'self'; connect-src"
                                + " 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri"
                                + " 'none'; form-action 'self'")))
        .build();
  }
}
