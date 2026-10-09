package com.mphasis.assessment;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionRequest(
        String transactionId,
        String requestId,
        String accountId,
        TransactionType transactionType,
        BigDecimal amount,
        String currency,
        long sequenceNumber,
        Instant eventTime,
        boolean simulateTransientFailure
) {
    public static TransactionRequest of(
            String transactionId,
            String requestId,
            String accountId,
            TransactionType type,
            String amount,
            String currency,
            long sequenceNumber
    ) {
        return new TransactionRequest(
                transactionId, requestId, accountId, type,
                new BigDecimal(amount), currency, sequenceNumber,
                Instant.now(), false
        );
    }
}
