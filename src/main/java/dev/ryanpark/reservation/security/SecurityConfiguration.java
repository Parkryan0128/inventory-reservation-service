package dev.ryanpark.reservation.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;

@Configuration
public class SecurityConfiguration {
    @Bean InMemoryUserDetailsManager users(@Value("${app.security.alice-password}") String alice,
            @Value("${app.security.bob-password}") String bob, @Value("${app.security.admin-password}") String admin) {
        for (var password : new String[]{alice, bob, admin})
            if (password.length() < 12 || password.startsWith("${"))
                throw new IllegalArgumentException("Configure passwords of at least 12 characters");
        var encoder = new BCryptPasswordEncoder();
        return new InMemoryUserDetailsManager(
                User.withUsername("alice").password("{bcrypt}" + encoder.encode(alice)).roles("CUSTOMER").build(),
                User.withUsername("bob").password("{bcrypt}" + encoder.encode(bob)).roles("CUSTOMER").build(),
                User.withUsername("admin").password("{bcrypt}" + encoder.encode(admin)).roles("ADMIN", "CUSTOMER").build());
    }
    @Bean SecurityFilterChain security(HttpSecurity http, ObjectMapper json) throws Exception {
        // Identity is authenticated per request. A session stores only the CSRF token.
        http.securityContext(context -> context.securityContextRepository(new RequestAttributeSecurityContextRepository()))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/index.html", "/app.js", "/styles.css", "/api/csrf", "/actuator/health", "/error").permitAll()
                .requestMatchers("/api/admin/**", "/actuator/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/products").hasRole("ADMIN")
                .requestMatchers("/api/**").hasRole("CUSTOMER")
                .anyRequest().denyAll())
            .httpBasic(basic -> basic.authenticationEntryPoint((req, res, ex) -> problem(json, res, 401, "UNAUTHENTICATED")))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((req, res, ex) -> problem(json, res, 401, "UNAUTHENTICATED"))
                .accessDeniedHandler((req, res, ex) -> problem(json, res, 403, "FORBIDDEN")))
            .requestCache(cache -> cache.disable()).logout(logout -> logout.disable())
            .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")));
        // Basic authentication also needs CSRF protection; the demo fetches /api/csrf.
        return http.build();
    }
    private static void problem(ObjectMapper json, HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status); response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        var problem = ProblemDetail.forStatus(status); problem.setProperty("code", code);
        json.writeValue(response.getOutputStream(), problem);
    }
}
