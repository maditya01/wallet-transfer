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

    // Used for the retry path: fetch the original transfer for a given idempotency key.
    @Query(value = "SELECT * FROM transfers WHERE idempotency_key = :idempotencyKey", nativeQuery = true)
    Optional<Transfer> findByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    // Reserved for R3 (reversal). Native query; column is `id`, not `transfer_id`.
    @Modifying
    @Query(value = "UPDATE transfers SET status = 'REVERSED' WHERE id = :transferId", nativeQuery = true)
    int markReversed(@Param("transferId") long transferId);
}
