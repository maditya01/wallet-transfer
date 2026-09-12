package com.example.wallet_transfer.dto;

import com.example.wallet_transfer.entity.Transfer;

public record TransferResponse(
        long transferId,
        String status,
        long amountPaise
) {
    public static TransferResponse from(Transfer t) {
        return new TransferResponse(t.getTransferId(), t.getStatus().name(), t.getAmountPaise());
    }
}