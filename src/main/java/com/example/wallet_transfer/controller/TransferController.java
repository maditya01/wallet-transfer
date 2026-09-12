package com.example.wallet_transfer.controller;

import com.example.wallet_transfer.dto.CreateTransferRequest;
import com.example.wallet_transfer.dto.TransferResponse;
import com.example.wallet_transfer.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> transfer(@Valid @RequestBody CreateTransferRequest request) {
        TransferResponse result = transferService.transfer(
                request.idempotencyKey(),
                request.fromUserId(),
                request.toUserId(),
                request.amountPaise());
        return ResponseEntity.ok(result);
    }
}
