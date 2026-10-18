package com.example.demo.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpStatus;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.Collections;
import java.util.IdentityHashMap;

/** Same safe server-failure classification for MVC and pre-controller authentication. */
@Slf4j
public record ServerFailureResponse(HttpStatus status, String code, String detail) {
    public static ServerFailureResponse from(Exception failure) {
        var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        Throwable root = failure;
        boolean temporary = false;
        boolean databaseConfigurationFailure = false;
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            root = cause;
            temporary |= cause instanceof SQLTransientConnectionException
                    || cause instanceof SQLRecoverableException || cause instanceof SQLTimeoutException
                    || cause instanceof QueryTimeoutException || cause instanceof TransientDataAccessResourceException;
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                // JDBC connection failures: includes broken sockets/refused connections, not SQL grammar/authentication.
                temporary |= sql.getSQLState().startsWith("08");
                databaseConfigurationFailure |= sql.getSQLState().startsWith("28") || sql.getSQLState().startsWith("42");
            }
        }
        if (temporary && !databaseConfigurationFailure) {
            log.atWarn().addKeyValue("event", "system.database_unavailable")
                    .addKeyValue("exceptionType", failure.getClass().getName())
                    .addKeyValue("rootCauseType", root.getClass().getName()).log("Database temporarily unavailable");
            return new ServerFailureResponse(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                    ApiProblemFactory.SERVICE_UNAVAILABLE_DETAIL);
        }
        StackTraceElement[] frames = root.getStackTrace();
        log.atError().addKeyValue("event", "system.unhandled_error")
                .addKeyValue("exceptionType", failure.getClass().getName())
                .addKeyValue("rootCauseType", root.getClass().getName())
                .addKeyValue("topFrame", frames.length == 0 ? "unknown" : frames[0].toString())
                .log("Unexpected system error");
        return new ServerFailureResponse(HttpStatus.INTERNAL_SERVER_ERROR, "SYSTEM_ERROR", ApiProblemFactory.SYSTEM_ERROR_DETAIL);
    }
}
