package com.example.wallet_transfer.service;

import com.example.wallet_transfer.dto.TransferResponse;
import com.example.wallet_transfer.entity.Transfer;
import com.example.wallet_transfer.entity.Wallet;
import com.example.wallet_transfer.exception.IdempotencyConflictException;
import com.example.wallet_transfer.exception.InsufficientFundsException;
import com.example.wallet_transfer.exception.TransferNotFoundException;
import com.example.wallet_transfer.exception.TransferNotReversibleException;
import com.example.wallet_transfer.exception.WalletNotFoundException;
import com.example.wallet_transfer.repository.TransferRepository;
import com.example.wallet_transfer.repository.WalletRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final TransferRepository transferRepository;
    private final WalletRepository walletRepository;

    // Domain metrics — surfaced at /metrics for scraping/dashboards.
    private final Counter transfersCompleted;
    private final Counter transfersDeclinedInsufficient;
    private final Counter transfersIdempotentReplay;
    private final Counter transfersConflict;
    private final Counter transfersReversed;

    public TransferService(TransferRepository transferRepository,
                           WalletRepository walletRepository,
                           MeterRegistry metrics) {
        this.transferRepository = transferRepository;
        this.walletRepository = walletRepository;
        this.transfersCompleted = Counter.builder("transfers.completed")
                .description("transfers successfully completed").register(metrics);
        this.transfersDeclinedInsufficient = Counter.builder("transfers.declined.insufficient_funds")
                .description("transfers declined due to insufficient funds").register(metrics);
        this.transfersIdempotentReplay = Counter.builder("transfers.idempotent_replay")
                .description("transfers that were idempotent replays of an existing key").register(metrics);
        this.transfersConflict = Counter.builder("transfers.conflict")
                .description("same idempotency key reused with a different body").register(metrics);
        this.transfersReversed = Counter.builder("transfers.reversed")
                .description("transfers reversed").register(metrics);
    }

    @Transactional
    public TransferResponse transfer(String idempotencyKey, String fromUserId,
                                     String toUserId, long amountPaise) {

        // Resolve user ids -> wallets (throw if a wallet doesn't exist)
        Wallet from = walletRepository.findByUserId(fromUserId)
                .orElseThrow(() -> new WalletNotFoundException(fromUserId));
        Wallet to = walletRepository.findByUserId(toUserId)
                .orElseThrow(() -> new WalletNotFoundException(toUserId));

        long fromWalletId = from.getWalletId();
        long toWalletId = to.getWalletId();

        String requestHash = computeRequestHash(fromWalletId, toWalletId, amountPaise);

        // Gate 2 — record atomically. 0 means the idempotency key already exists.
        int inserted = transferRepository.insertIfAbsent(
                idempotencyKey, requestHash, fromWalletId, toWalletId, amountPaise);

        if (inserted == 0) {
            // Key already used
            Transfer existing = transferRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "Idempotency key conflicted but no row found: " + idempotencyKey));
            if (!existing.getRequestHash().equals(requestHash)) {
                // Same key, different body -> client error -> 409
                transfersConflict.increment();
                log.warn("transfer_conflict idempotencyKey={} from={} to={} amountPaise={}",
                        idempotencyKey, fromUserId, toUserId, amountPaise);
                throw new IdempotencyConflictException(idempotencyKey);
            }
            // Same key, same body -> genuine retry -> return the original result (idempotent)
            transfersIdempotentReplay.increment();
            log.info("transfer_idempotent_replay idempotencyKey={} transferId={}",
                    idempotencyKey, existing.getTransferId());
            return TransferResponse.from(existing);
        }

        // Gate 3 — move the money.
        // Deadlock avoidance: always touch the two wallet rows in a consistent order
        // (lowest wallet id first), regardless of who is sender/receiver. Two opposite
        // transfers (A->B and B->A) then acquire row locks in the same order and cannot deadlock.
        if (fromWalletId < toWalletId) {
            debitOrThrow(fromWalletId, amountPaise, fromUserId);
            walletRepository.credit(toWalletId, amountPaise);
        } else {
            // toWalletId is lower — credit it first so the lower id is touched first.
            walletRepository.credit(toWalletId, amountPaise);
            debitOrThrow(fromWalletId, amountPaise, fromUserId);
        }

        // Load the row we just inserted (now has DB id + created_at) and return it.
        Transfer saved = transferRepository.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "Transfer must exist after insert: " + idempotencyKey));
        transfersCompleted.increment();
        log.info("transfer_completed transferId={} idempotencyKey={} from={} to={} amountPaise={}",
                saved.getTransferId(), idempotencyKey, fromUserId, toUserId, amountPaise);
        return TransferResponse.from(saved);
    }

    // Read a transfer by id (GET /transfers/{id}).
    @Transactional(readOnly = true)
    public TransferResponse getTransfer(long transferId) {
        Transfer t = transferRepository.findById(transferId)
                .orElseThrow(() -> new TransferNotFoundException(transferId));
        return TransferResponse.from(t);
    }

    /**
     * Reverse a completed transfer (R3): move the exact amount back recipient -> sender.
     *
     * Design: a reversal IS a transfer with the roles swapped, so it reuses the SAME atomic
     * money-movement primitive (conditional debit + credit, deadlock-safe order). We record a
     * NEW transfer row for the reversal (its own idempotency key), linked to the original via
     * reverses_transfer_id, and flip the original's status to REVERSED — guarded so a transfer
     * can only be reversed once.
     */
    @Transactional
    public TransferResponse reverse(long originalTransferId, String idempotencyKey) {
        Transfer original = transferRepository.findById(originalTransferId)
                .orElseThrow(() -> new TransferNotFoundException(originalTransferId));

        // Guard: only a COMPLETED transfer can be reversed. This UPDATE ... WHERE status='COMPLETED'
        // is atomic — if 0 rows change, it was already reversed (or never completed) -> reject.
        int flipped = transferRepository.markReversedIfCompleted(originalTransferId);
        if (flipped == 0) {
            throw new TransferNotReversibleException(originalTransferId);
        }

        // Money flows back: original recipient -> original sender.
        long refundFrom = original.getToWalletId();     // recipient gives it back
        long refundTo = original.getFromWalletId();      // sender receives it back
        long amount = original.getAmountPaise();

        String requestHash = computeRequestHash(refundFrom, refundTo, amount);

        // Record the reversal as its own transfer row (idempotent on its own key), linked to the original.
        int inserted = transferRepository.insertReversal(
                idempotencyKey, requestHash, refundFrom, refundTo, amount, originalTransferId);
        if (inserted == 0) {
            // reversal key already used -> idempotent replay of the reversal
            Transfer existing = transferRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "Reversal key conflicted but no row found: " + idempotencyKey));
            transfersIdempotentReplay.increment();
            return TransferResponse.from(existing);
        }

        // Same atomic primitive as a normal transfer, deadlock-safe order.
        // Note: the refund debits the original recipient; if they've since spent the funds this
        // will decline (422) — an intentional policy choice (we do not allow the wallet to go negative).
        if (refundFrom < refundTo) {
            debitOrThrow(refundFrom, amount, "wallet:" + refundFrom);
            walletRepository.credit(refundTo, amount);
        } else {
            walletRepository.credit(refundTo, amount);
            debitOrThrow(refundFrom, amount, "wallet:" + refundFrom);
        }

        Transfer saved = transferRepository.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "Reversal must exist after insert: " + idempotencyKey));
        transfersReversed.increment();
        log.info("transfer_reversed reversalId={} originalTransferId={} amountPaise={}",
                saved.getTransferId(), originalTransferId, amount);
        return TransferResponse.from(saved);
    }

    // Conditional debit (Gate 3). If it affects 0 rows, the sender lacked funds -> abort.
    private void debitOrThrow(long walletId, long amountPaise, String fromUserId) {
        int debited = walletRepository.debit(walletId, amountPaise);
        if (debited == 0) {
            transfersDeclinedInsufficient.increment();
            log.warn("transfer_declined_insufficient_funds walletId={} amountPaise={} user={}",
                    walletId, amountPaise, fromUserId);
            throw new InsufficientFundsException(fromUserId);   // rolls back the whole @Transactional
        }
    }

    // Server-computed fingerprint of the request body. Distinguishes a genuine retry
    // (same hash) from a key-reuse-with-different-body error (different hash -> 409).
    private String computeRequestHash(long fromWalletId, long toWalletId, long amountPaise) {
        String canonical = fromWalletId + ":" + toWalletId + ":" + amountPaise;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available in a standard JVM; this should never happen.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
