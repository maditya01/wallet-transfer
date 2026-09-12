package com.example.wallet_transfer.dto;

import jakarta.validation.constraints.Positive;

public record CreditRequest(
        @Positive(message = "amountPaise must be positive")
        long amountPaise
) {}
