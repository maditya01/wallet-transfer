package com.example.wallet_transfer.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

// Sender does not have enough balance -> unprocessable.
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)   // 422
public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(String userId) {
        super("Insufficient funds for user: " + userId);
    }
}
