package com.minikun.computer;

import java.util.List;

public interface ComputerAuditStore {
    void save(ComputerAudit audit);
    List<ComputerAudit> list(String ownerId, int limit);
}
