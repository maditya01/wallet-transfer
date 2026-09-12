package com.example.wallet_transfer.controller;


import com.example.wallet_transfer.dto.CreateWalletRequest;
import com.example.wallet_transfer.dto.CreditRequest;
import com.example.wallet_transfer.dto.WalletResponse;
import com.example.wallet_transfer.entity.Wallet;
import com.example.wallet_transfer.service.WalletService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/wallets")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping
    public ResponseEntity<WalletResponse> createWallet(@Valid @RequestBody CreateWalletRequest request) {
        Wallet wallet = walletService.getOrCreate(request.userId());
        return ResponseEntity.ok(WalletResponse.from(wallet));
    }

    @GetMapping("/{userId}")
    public ResponseEntity<WalletResponse> getWallet(@PathVariable String userId) {
        Wallet wallet = walletService.getOrCreate(userId);
        return ResponseEntity.ok(WalletResponse.from(wallet));
    }

    // Fund a wallet: POST /wallets/{userId}/credit  {"amountPaise": 100000}
    @PostMapping("/{userId}/credit")
    public ResponseEntity<WalletResponse> credit(@PathVariable String userId,
                                                 @Valid @RequestBody CreditRequest request) {
        Wallet wallet = walletService.credit(userId, request.amountPaise());
        return ResponseEntity.ok(WalletResponse.from(wallet));
    }
}
