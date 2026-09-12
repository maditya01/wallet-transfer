package com.example.wallet_transfer.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

// No wallet exists for the given user id.
@ResponseStatus(HttpStatus.NOT_FOUND)   // 404
public class WalletNotFoundException extends RuntimeException {
    public WalletNotFoundException(String userId) {
        super("Wallet not found for user: " + userId);
    }
}
