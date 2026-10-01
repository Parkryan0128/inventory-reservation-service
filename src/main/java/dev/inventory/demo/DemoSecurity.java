package dev.inventory.demo;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@Profile("demo & !public-demo")
public class DemoSecurity {
  @Bean
  @Order(1)
  SecurityFilterChain demoFilterChain(HttpSecurity http) throws Exception {
    return http.securityMatcher(
            "/", "/demo.html", "/demo.js", "/demo-client.js", "/demo.css", "/api/demo/**")
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
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
