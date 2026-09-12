package com.example.wallet_transfer.repository;

import com.example.wallet_transfer.entity.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TransferRepository extends JpaRepository<Transfer, Long> {

    // Gate 2 — record the transfer atomically. ON CONFLICT means a duplicate
    // idempotency_key is silently skipped. Returns 1 = recorded, 0 = key already used.
    @Modifying(clearAutomatically = true)
    @Query(value = """
            INSERT INTO transfers
                (idempotency_key, request_hash, from_wallet_id, to_wallet_id, amount_paise, status)
            VALUES
                (:idempotencyKey, :requestHash, :fromWalletId, :toWalletId, :amountPaise, 'COMPLETED')
            ON CONFLICT (idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("idempotencyKey") String idempotencyKey,
                       @Param("requestHash") String requestHash,
                       @Param("fromWalletId") long fromWalletId,
                       @Param("toWalletId") long toWalletId,
                       @Param("amountPaise") long amountPaise);

    // Record a reversal as its own transfer row, linked to the original via reverses_transfer_id.
    // Same ON CONFLICT idempotency guard as a normal transfer.
    @Modifying(clearAutomatically = true)
    @Query(value = """
            INSERT INTO transfers
                (idempotency_key, request_hash, from_wallet_id, to_wallet_id, amount_paise, status, reverses_transfer_id)
            VALUES
                (:idempotencyKey, :requestHash, :fromWalletId, :toWalletId, :amountPaise, 'COMPLETED', :reversesTransferId)
            ON CONFLICT (idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int insertReversal(@Param("idempotencyKey") String idempotencyKey,
                       @Param("requestHash") String requestHash,
                       @Param("fromWalletId") long fromWalletId,
                       @Param("toWalletId") long toWalletId,
                       @Param("amountPaise") long amountPaise,
                       @Param("reversesTransferId") long reversesTransferId);

    // Used for the retry path: fetch the original transfer for a given idempotency key.
    @Query(value = "SELECT * FROM transfers WHERE idempotency_key = :idempotencyKey", nativeQuery = true)
    Optional<Transfer> findByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    // Mark a transfer REVERSED, but ONLY if it is currently COMPLETED.
    // The status guard makes this atomic + idempotent: two concurrent reversal attempts,
    // only one flips COMPLETED->REVERSED (returns 1); the other sees status already changed
    // (returns 0). Prevents double-reversal.
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE transfers
               SET status = 'REVERSED'
             WHERE id = :transferId
               AND status = 'COMPLETED'
            """, nativeQuery = true)
    int markReversedIfCompleted(@Param("transferId") long transferId);
}
