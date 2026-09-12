package com.example.wallet_transfer.service;

import com.example.wallet_transfer.entity.Wallet;
import com.example.wallet_transfer.exception.WalletNotFoundException;
import com.example.wallet_transfer.repository.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletService {
    private final WalletRepository walletRepository;

    public WalletService(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    @Transactional
    public Wallet getOrCreate(String userId) {
        // INSERT ... ON CONFLICT DO NOTHING (1=created, 0=already existed)
        walletRepository.insertIfAbsent(userId);
        return walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException(
                        "Wallet must exist after insertIfAbsent for userId=" + userId));
    }

    // Fund a wallet. Money enters the system here (in a real system this would be a
    // payment-gateway callback). Atomic UPDATE — the DB does balance = balance + amount,
    // so concurrent credits can't lose an update (no read-modify-write in Java).
    @Transactional
    public Wallet credit(String userId, long amountPaise) {
        Wallet wallet = getOrCreate(userId);           // ensure the wallet exists first
        int updated = walletRepository.credit(wallet.getWalletId(), amountPaise);
        if (updated == 0) {
            throw new WalletNotFoundException(userId);  // should not happen after getOrCreate
        }
        return walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException(userId));
    }
}
