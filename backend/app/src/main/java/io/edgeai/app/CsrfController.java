package io.edgeai.app;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CsrfController {
    @GetMapping("/api/v1/csrf")
    public Token token(CsrfToken token) { return new Token(token.getToken()); }
    public record Token(String token) {}
}
