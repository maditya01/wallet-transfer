package com.example.wallet_transfer.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

// No transfer exists for the given id.
@ResponseStatus(HttpStatus.NOT_FOUND)   // 404
public class TransferNotFoundException extends RuntimeException {
    public TransferNotFoundException(long transferId) {
        super("Transfer not found: " + transferId);
    }
}
