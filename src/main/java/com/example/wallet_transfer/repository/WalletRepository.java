package com.example.wallet_transfer.repository;


import com.example.wallet_transfer.entity.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;


public interface WalletRepository extends JpaRepository<Wallet, Long> {

    public Optional<Wallet> findByUserId(String userId);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            INSERT INTO wallets (user_id, balance_paise)
            VALUES (:userId, 0)
            ON CONFLICT (user_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") String userId);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE wallets
               SET balance_paise = balance_paise + :amountPaise
             WHERE id = :walletId
            """, nativeQuery = true)
    int credit(@Param("walletId") long walletId, @Param("amountPaise") long amountPaise);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE wallets
               SET balance_paise = balance_paise - :amountPaise
             WHERE id = :walletId
               AND balance_paise >= :amountPaise
            """, nativeQuery = true)
    int debit(@Param("walletId") long walletId, @Param("amountPaise") long amountPaise);

}
