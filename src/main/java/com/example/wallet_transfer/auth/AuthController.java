package com.example.wallet_transfer.auth;

import com.example.wallet_transfer.dto.CreateWalletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Bootstrap endpoint: exchange a user id for a bearer token.
 * Open (no auth) so a client can obtain a token to begin with.
 * In production this would be a real login (password / OAuth); here it's a
 * deliberately minimal issuer for the exercise — noted as a trade-off in the write-up.
 */
@RestController
public class AuthController {

    private final TokenService tokenService;

    public AuthController(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @PostMapping("/auth/token")
    public ResponseEntity<Map<String, String>> issueToken(@Valid @RequestBody CreateWalletRequest request) {
        String token = tokenService.mint(request.userId());
        return ResponseEntity.ok(Map.of("userId", request.userId(), "token", token));
    }
}
