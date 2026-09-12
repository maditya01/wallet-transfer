package com.example.wallet_transfer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record CreateTransferRequest(
        @NotBlank(message = "idempotencyKey is required")
        String idempotencyKey,

        @NotBlank(message = "fromUserId is required")
        String fromUserId,

        @NotBlank(message = "toUserId is required")
        String toUserId,

        @Positive(message = "amountPaise must be positive")
        long amountPaise
) {}
