package com.example.wallet_transfer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateWalletRequest(
        @NotBlank(message = "userId is required")
        @Size(max = 64, message = "userId must be at most 64 characters")
        String userId
) {}
