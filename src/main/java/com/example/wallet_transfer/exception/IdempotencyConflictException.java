package com.example.wallet_transfer.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

// Same idempotency key reused with a DIFFERENT request body -> client error.
@ResponseStatus(HttpStatus.CONFLICT)   // 409
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String key) {
        super("Idempotency key reused with different request: " + key);
    }
}
