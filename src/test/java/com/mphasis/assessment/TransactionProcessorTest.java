package com.mphasis.assessment;

import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public class TransactionProcessorTest {
    private TransactionProcessor processor;

    @BeforeMethod
    public void setUp() {
        processor = new TransactionProcessor();
    }

    @Test
    public void shouldProcessCreditAndDebitInSequenceOrderEvenWhenReceivedOutOfOrder() {
        TransactionRequest sequence2 = TransactionRequest.of(
                "TXN-2", "REQ-2", "ACC-1", TransactionType.DEBIT, "30", "INR", 2);
        TransactionRequest sequence1 = TransactionRequest.of(
                "TXN-1", "REQ-1", "ACC-1", TransactionType.CREDIT, "100", "INR", 1);

        TransactionResult firstResult = processor.accept(sequence2);
        Assert.assertEquals(firstResult.status(), TransactionStatus.PENDING_SEQUENCE);

        TransactionResult secondResult = processor.accept(sequence1);
        Assert.assertEquals(secondResult.status(), TransactionStatus.PROCESSED);
        Assert.assertEquals(processor.getStatus("TXN-2"), TransactionStatus.PROCESSED);
        Assert.assertEquals(processor.getBalance("ACC-1"), new BigDecimal("70"));
    }

    @Test
    public void shouldNotApplySameTransactionTwice() {
        TransactionRequest original = TransactionRequest.of(
                "TXN-1", "REQ-1", "ACC-1", TransactionType.CREDIT, "100", "INR", 1);
        processor.accept(original);

        TransactionRequest duplicate = TransactionRequest.of(
                "TXN-1", "REQ-1-DUP", "ACC-1", TransactionType.CREDIT, "100", "INR", 1);
        TransactionResult result = processor.accept(duplicate);

        Assert.assertEquals(result.status(), TransactionStatus.DUPLICATE);
        Assert.assertEquals(processor.getBalance("ACC-1"), new BigDecimal("100"));
    }

    @Test
    public void shouldRejectNegativeAmountWithoutChangingBalance() {
        TransactionRequest invalid = TransactionRequest.of(
                "TXN-BAD", "REQ-BAD", "ACC-1", TransactionType.CREDIT, "-10", "INR", 1);

        TransactionResult result = processor.accept(invalid);

        Assert.assertEquals(result.status(), TransactionStatus.FAILED);
        Assert.assertEquals(processor.getBalance("ACC-1"), BigDecimal.ZERO);
    }

    @Test
    public void shouldRetryTransientFailureAndProcessOnlyOnce() {
        TransactionRequest transientRequest = new TransactionRequest(
                "TXN-RETRY", "REQ-RETRY", "ACC-1", TransactionType.CREDIT,
                new BigDecimal("50"), "INR", 1, Instant.now(), true);

        TransactionResult result = processor.accept(transientRequest);

        Assert.assertEquals(result.status(), TransactionStatus.PROCESSED);
        Assert.assertEquals(result.attempts(), 2);
        Assert.assertEquals(processor.getBalance("ACC-1"), new BigDecimal("50"));
    }

    @Test
    public void shouldFailDebitWhenBalanceIsInsufficient() {
        TransactionRequest debit = TransactionRequest.of(
                "TXN-DEBIT", "REQ-DEBIT", "ACC-1", TransactionType.DEBIT, "25", "INR", 1);

        TransactionResult result = processor.accept(debit);

        Assert.assertEquals(result.status(), TransactionStatus.FAILED);
        Assert.assertEquals(processor.getBalance("ACC-1"), BigDecimal.ZERO);
    }

    @Test
    public void shouldProvideStatusSummary() {
        processor.accept(TransactionRequest.of(
                "TXN-1", "REQ-1", "ACC-1", TransactionType.CREDIT, "10", "INR", 1));

        Map<TransactionStatus, Long> summary = processor.getStatusSummary();
        Assert.assertEquals(summary.get(TransactionStatus.PROCESSED), Long.valueOf(1));
    }
}
