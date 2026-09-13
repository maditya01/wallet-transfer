package com.example.wallet_transfer.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Public landing page at "/" — a self-describing index of the API so anyone hitting
 * the base URL sees how to use the service (instead of a bare 401). Open (no auth).
 */
@RestController
public class HomeController {

    @GetMapping("/")
    public Map<String, Object> index() {
        return Map.of(
                "service", "wallet-transfer",
                "description", "Wallet & P2P transfer service. Money is stored as integer paise.",
                "auth", "Most endpoints need a bearer token. Get one from POST /auth/token, then send "
                        + "Authorization: Bearer <token>.",
                "openEndpoints", List.of(
                        "POST /auth/token        -> { userId } returns a bearer token",
                        "GET  /actuator/health   -> health check",
                        "GET  /metrics           -> Prometheus metrics (domain counters + HTTP latency)"
                ),
                "authenticatedEndpoints", List.of(
                        "POST /wallets                   -> { userId } get-or-create a wallet",
                        "GET  /wallets/{userId}          -> current balance",
                        "POST /wallets/{userId}/credit   -> { amountPaise } fund a wallet",
                        "POST /transfers                 -> { idempotencyKey, fromUserId, toUserId, amountPaise }",
                        "GET  /transfers/{id}            -> transfer status",
                        "POST /transfers/{id}/reverse    -> reverse a completed transfer (optional Idempotency-Key header)"
                ),
                "repo", "https://github.com/maditya01/wallet-transfer"
        );
    }
}
