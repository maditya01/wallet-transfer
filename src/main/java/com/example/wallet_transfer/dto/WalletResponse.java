package com.example.wallet_transfer.dto;

import com.example.wallet_transfer.entity.Wallet;
// we are not exposing wallet entity to the outside world
public record WalletResponse(
        long walletId,
        String userId,
        long balancePaise
) {
    public static WalletResponse from(Wallet w) {
        return new WalletResponse(w.getWalletId(), w.getUserId(), w.getBalancePaise());
    }
}
