package com.minikun.investment;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface InvestmentStore {
    Optional<InvestmentPolicy> findPolicy(String ownerId);

    InvestmentPolicy savePolicy(InvestmentPolicy policy);

    InvestmentTransaction addTransaction(InvestmentTransaction transaction);

    List<InvestmentTransaction> listTransactions(String ownerId);

    boolean voidTransaction(UUID id, String ownerId, java.time.Instant voidedAt);

    Optional<InvestmentThesis> findThesis(UUID id, String ownerId);

    InvestmentThesis saveThesis(InvestmentThesis thesis);

    List<InvestmentThesis> listTheses(String ownerId, InvestmentThesisStatus status);

    Map<String, InvestmentQuotePriority> listQuotePriorities(String ownerId);

    void saveQuotePriority(String ownerId, String symbol, InvestmentQuotePriority priority, Instant updatedAt);
}
