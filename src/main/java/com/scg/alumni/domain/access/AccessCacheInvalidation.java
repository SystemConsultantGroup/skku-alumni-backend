package com.scg.alumni.domain.access;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
@Slf4j
public class AccessCacheInvalidation {
    private final AccessPermissionCache cache;

    public void afterCommit(long userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Permission changes require a transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { cache.invalidate(userId); }
                catch (RuntimeException failure) { log.error("Permission cache invalidation failed for user {} after commit; cached values expire within TTL", userId, failure); }
            }
        });
    }
}
