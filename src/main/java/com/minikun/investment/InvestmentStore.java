package com.minikun.investment;

import java.util.List;
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
}
