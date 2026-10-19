package com.example.demo.security;

import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.dao.PessimisticLockingFailureException;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;

/** Only authentication row-lock contention is translated; business 409 handling is untouched. */
public final class AuthStateLocks {
    private AuthStateLocks() { }
    public static RuntimeException translate(RuntimeException failure) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof LockTimeoutException || cause instanceof PessimisticLockException
                    || cause instanceof PessimisticLockingFailureException
                    || cause instanceof SQLException sql && (sql.getErrorCode() == 1205 || sql.getErrorCode() == 1213))
                return new TransientDataAccessResourceException("Authentication state temporarily unavailable", failure);
        }
        return failure;
    }
}
