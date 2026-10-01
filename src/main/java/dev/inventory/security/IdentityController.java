package dev.inventory.security;

import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IdentityController {
  @GetMapping("/api/csrf")
  CsrfToken csrf(CsrfToken token) {
    return token;
  }

  @GetMapping("/api/me")
  Map<String, Object> me(Authentication authentication) {
    return Map.of(
        "username",
        authentication.getName(),
        "roles",
        authentication.getAuthorities().stream().map(Object::toString).toList());
  }
}
