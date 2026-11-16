package com.scg.alumni.domain.access;

import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccessCacheInvalidationTest {
    @Test
    void invalidatesOnlyAfterCommit() {
        var cache = mock(AccessPermissionCache.class);
        var invalidation = new AccessCacheInvalidation(cache);
        TransactionSynchronizationManager.initSynchronization();
        try {
            invalidation.afterCommit(42L);
            var synchronization = TransactionSynchronizationManager.getSynchronizations().getFirst();
            verifyNoInteractions(cache);
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            verifyNoInteractions(cache);
            synchronization.afterCommit();
            verify(cache).invalidate(42L);
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }
}
