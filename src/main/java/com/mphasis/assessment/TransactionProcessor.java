package com.mphasis.assessment;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Lightweight in-memory implementation for the assessment.
 *
 * Ordering policy: sequence numbers are expected to start at 1 independently
 * for each account. A request with a future sequence is buffered until the
 * missing sequence arrives. This is a demonstration implementation, not a
 * thread-safe or durable production service.
 */
public class TransactionProcessor {
    private static final int MAX_ATTEMPTS = 2;

    private final Map<String, BigDecimal> balances = new HashMap<>();
    private final Map<String, TransactionStatus> statuses = new LinkedHashMap<>();
    private final Map<String, TransactionRequest> pendingByAccountAndSequence = new HashMap<>();
    private final Map<String, TreeMap<Long, TransactionRequest>> pendingByAccount = new HashMap<>();
    private final Map<String, Integer> attemptsByTransaction = new HashMap<>();
    private final Set<String> processedTransactionIds = new HashSet<>();
    private final Set<String> seenRequestIds = new HashSet<>();
    private final Map<String, Long> nextSequenceByAccount = new HashMap<>();
    private final List<String> eventLog = new ArrayList<>();

    public synchronized List<TransactionResult> processBatch(List<TransactionRequest> requests) {
        List<TransactionRequest> orderedInput = new ArrayList<>(requests);
        // Stable ordering is not required for correctness; this preserves the
        // original arrival order so the out-of-order strategy is exercised.
        List<TransactionResult> results = new ArrayList<>();
        for (TransactionRequest request : orderedInput) {
            results.add(accept(request));
        }
        return results;
    }

    public synchronized TransactionResult accept(TransactionRequest request) {
        String id = request == null ? null : request.transactionId();
        String validationError = validate(request);
        if (validationError != null) {
            String safeId = id == null || id.isBlank() ? "<missing-id>" : id;
            statuses.put(safeId, TransactionStatus.FAILED);
            log("Rejected invalid transaction " + safeId + ": " + validationError);
            return new TransactionResult(safeId, TransactionStatus.FAILED, validationError, 0);
        }

        if (processedTransactionIds.contains(request.transactionId())) {
            log("Duplicate transaction ignored: " + request.transactionId());
            return new TransactionResult(request.transactionId(), TransactionStatus.DUPLICATE,
                    "Transaction has already been processed", attemptsByTransaction.getOrDefault(request.transactionId(), 1));
        }

        if (request.sequenceNumber() < nextSequenceByAccount.getOrDefault(request.accountId(), 1L)) {
            statuses.put(request.transactionId(), TransactionStatus.DUPLICATE);
            log("Stale sequence ignored for transaction " + request.transactionId());
            return new TransactionResult(request.transactionId(), TransactionStatus.DUPLICATE,
                    "Sequence number is older than the next expected sequence", 0);
        }

        TreeMap<Long, TransactionRequest> accountPending =
                pendingByAccount.computeIfAbsent(request.accountId(), ignored -> new TreeMap<>());
        TransactionRequest existingAtSequence = accountPending.get(request.sequenceNumber());
        if (existingAtSequence != null) {
            if (existingAtSequence.transactionId().equals(request.transactionId())) {
                log("Duplicate pending request ignored: " + request.transactionId());
                return new TransactionResult(request.transactionId(), TransactionStatus.DUPLICATE,
                        "Transaction is already waiting for an earlier sequence", 0);
            }
            statuses.put(request.transactionId(), TransactionStatus.FAILED);
            log("Conflicting transaction sequence for account " + request.accountId());
            return new TransactionResult(request.transactionId(), TransactionStatus.FAILED,
                    "Another transaction already uses this account sequence number", 0);
        }

        if (!seenRequestIds.add(request.requestId())) {
            statuses.put(request.transactionId(), TransactionStatus.DUPLICATE);
            log("Duplicate requestId ignored: " + request.requestId());
            return new TransactionResult(request.transactionId(), TransactionStatus.DUPLICATE,
                    "Request ID has already been seen", 0);
        }

        statuses.put(request.transactionId(), TransactionStatus.RECEIVED);
        accountPending.put(request.sequenceNumber(), request);
        pendingByAccountAndSequence.put(pendingKey(request.accountId(), request.sequenceNumber()), request);
        log("Received transaction " + request.transactionId());

        drainAccount(request.accountId());

        TransactionStatus status = statuses.getOrDefault(request.transactionId(), TransactionStatus.PENDING_SEQUENCE);
        if (status == TransactionStatus.RECEIVED) {
            status = TransactionStatus.PENDING_SEQUENCE;
            statuses.put(request.transactionId(), status);
        }
        String message = switch (status) {
            case PROCESSED -> "Transaction processed successfully";
            case FAILED -> "Transaction failed after validation or retry exhaustion";
            case RETRY_PENDING -> "Transaction is awaiting retry";
            case PENDING_SEQUENCE -> "Waiting for a missing earlier sequence";
            default -> status.toString();
        };
        return new TransactionResult(request.transactionId(), status, message,
                attemptsByTransaction.getOrDefault(request.transactionId(), 0));
    }

    private void drainAccount(String accountId) {
        TreeMap<Long, TransactionRequest> pending = pendingByAccount.get(accountId);
        if (pending == null) return;

        long expected = nextSequenceByAccount.getOrDefault(accountId, 1L);
        while (pending.containsKey(expected)) {
            TransactionRequest next = pending.get(expected);
            TransactionStatus finalStatus = executeWithRetry(next);
            statuses.put(next.transactionId(), finalStatus);
            pending.remove(expected);
            pendingByAccountAndSequence.remove(pendingKey(accountId, expected));
            // Advance the sequence after either a successful processing or a
            // terminal failure so one bad record does not block the account.
            nextSequenceByAccount.put(accountId, expected + 1);
            expected++;
        }
    }

    private TransactionStatus executeWithRetry(TransactionRequest request) {
        String id = request.transactionId();
        statuses.put(id, TransactionStatus.PROCESSING);

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            attemptsByTransaction.put(id, attempt);
            try {
                if (request.simulateTransientFailure() && attempt == 1) {
                    throw new TransientProcessingException("Simulated transient failure");
                }
                applyTransaction(request);
                processedTransactionIds.add(id);
                log("Processed transaction " + id + " on attempt " + attempt);
                return TransactionStatus.PROCESSED;
            } catch (TransientProcessingException ex) {
                log("Transient failure for " + id + " on attempt " + attempt);
                if (attempt < MAX_ATTEMPTS) {
                    statuses.put(id, TransactionStatus.RETRY_PENDING);
                } else {
                    log("Retry limit exhausted for " + id);
                    return TransactionStatus.FAILED;
                }
            } catch (RuntimeException ex) {
                log("Processing failure for " + id + ": " + ex.getMessage());
                return TransactionStatus.FAILED;
            }
        }
        return TransactionStatus.FAILED;
    }

    private void applyTransaction(TransactionRequest request) {
        BigDecimal current = balances.getOrDefault(request.accountId(), BigDecimal.ZERO);
        BigDecimal amount = request.amount();

        switch (request.transactionType()) {
            case CREDIT -> balances.put(request.accountId(), current.add(amount));
            case DEBIT -> {
                if (current.compareTo(amount) < 0) {
                    throw new IllegalStateException("Insufficient balance");
                }
                balances.put(request.accountId(), current.subtract(amount));
            }
            case REVERSAL -> balances.put(request.accountId(), current.add(amount));
        }
    }

    private String validate(TransactionRequest request) {
        if (request == null) return "Request must not be null";
        if (isBlank(request.transactionId())) return "transactionId is required";
        if (isBlank(request.requestId())) return "requestId is required";
        if (isBlank(request.accountId())) return "accountId is required";
        if (request.transactionType() == null) return "transactionType is required";
        if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            return "amount must be greater than zero";
        }
        if (isBlank(request.currency()) || request.currency().length() != 3) {
            return "currency must be a 3-character code";
        }
        if (request.sequenceNumber() <= 0) return "sequenceNumber must be greater than zero";
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String pendingKey(String accountId, long sequence) {
        return accountId + "::" + sequence;
    }

    private void log(String message) {
        eventLog.add(message);
        System.out.println("[TransactionProcessor] " + message);
    }

    public synchronized BigDecimal getBalance(String accountId) {
        return balances.getOrDefault(accountId, BigDecimal.ZERO);
    }

    public synchronized TransactionStatus getStatus(String transactionId) {
        return statuses.get(transactionId);
    }

    public synchronized Map<TransactionStatus, Long> getStatusSummary() {
        Map<TransactionStatus, Long> summary = new LinkedHashMap<>();
        for (TransactionStatus status : TransactionStatus.values()) {
            long count = statuses.values().stream().filter(status::equals).count();
            if (count > 0) summary.put(status, count);
        }
        return summary;
    }

    public synchronized List<String> getEventLog() {
        return List.copyOf(eventLog);
    }

    private static class TransientProcessingException extends RuntimeException {
        private TransientProcessingException(String message) {
            super(message);
        }
    }
}
