package com.example.wallet_transfer.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

// The transfer can't be reversed (already reversed, or not in a reversible state).
@ResponseStatus(HttpStatus.CONFLICT)   // 409
public class TransferNotReversibleException extends RuntimeException {
    public TransferNotReversibleException(long transferId) {
        super("Transfer is not reversible (already reversed or not completed): " + transferId);
    }
}
