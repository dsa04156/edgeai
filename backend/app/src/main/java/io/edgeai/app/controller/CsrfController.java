package io.edgeai.app.controller;

import io.edgeai.app.dto.CsrfTokenResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CsrfController {
    @GetMapping("/api/v1/csrf")
    public CsrfTokenResponse token(CsrfToken token) { return new CsrfTokenResponse(token.getToken()); }
}
