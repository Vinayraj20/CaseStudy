package com.mphasis.assessment;

public record TransactionResult(
        String transactionId,
        TransactionStatus status,
        String message,
        int attempts
) {}
