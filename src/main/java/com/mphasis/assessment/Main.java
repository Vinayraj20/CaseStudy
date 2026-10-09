package com.mphasis.assessment;

import java.util.List;

public class Main {
    public static void main(String[] args) {
        TransactionProcessor processor = new TransactionProcessor();

        List<TransactionRequest> requests = List.of(
                TransactionRequest.of("TXN-2002", "REQ-2", "ACC-201", TransactionType.DEBIT, "40", "INR", 2),
                TransactionRequest.of("TXN-2001", "REQ-1", "ACC-201", TransactionType.CREDIT, "100", "INR", 1),
                TransactionRequest.of("TXN-2002", "REQ-2-DUP", "ACC-201", TransactionType.DEBIT, "40", "INR", 2),
                TransactionRequest.of("TXN-2003", "REQ-3", "ACC-201", TransactionType.CREDIT, "25", "INR", 3),
                TransactionRequest.of("TXN-2004", "REQ-4", "ACC-202", TransactionType.DEBIT, "-10", "INR", 1),
                new TransactionRequest("TXN-2005", "REQ-5", "ACC-203", TransactionType.CREDIT,
                        new java.math.BigDecimal("500"), "INR", 1, java.time.Instant.now(), true)
        );

        System.out.println("=== Processing transactions ===");
        processor.processBatch(requests).forEach(System.out::println);
        System.out.println("\\n=== Final balances ===");
        System.out.println("ACC-201 = " + processor.getBalance("ACC-201"));
        System.out.println("ACC-202 = " + processor.getBalance("ACC-202"));
        System.out.println("ACC-203 = " + processor.getBalance("ACC-203"));
        System.out.println("\\n=== Status summary ===");
        System.out.println(processor.getStatusSummary());
    }
}
