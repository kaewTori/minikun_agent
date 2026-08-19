package com.minikun.guardian;

import java.util.List;

public interface GuardianAuditStore {
    void save(GuardianActionAudit audit);
    List<GuardianActionAudit> list(String ownerId, int limit);
}
