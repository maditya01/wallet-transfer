package com.example.wallet_transfer.controller;

import com.example.wallet_transfer.dto.CreateTransferRequest;
import com.example.wallet_transfer.dto.TransferResponse;
import com.example.wallet_transfer.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

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

    // GET /transfers/{id} — transfer status (required by the spec).
    @GetMapping("/{id}")
    public ResponseEntity<TransferResponse> getTransfer(@PathVariable long id) {
        return ResponseEntity.ok(transferService.getTransfer(id));
    }

    // POST /transfers/{id}/reverse — reverse a completed transfer (R3).
    // The reversal needs its own idempotency key: honor an Idempotency-Key header if given,
    // otherwise derive a deterministic one from the original id so repeat calls are idempotent.
    @PostMapping("/{id}/reverse")
    public ResponseEntity<TransferResponse> reverse(
            @PathVariable long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        String key = (idempotencyKey != null && !idempotencyKey.isBlank())
                ? idempotencyKey
                : "reverse-of-" + id;   // deterministic default -> reversing twice is idempotent
        return ResponseEntity.ok(transferService.reverse(id, key));
    }
}
