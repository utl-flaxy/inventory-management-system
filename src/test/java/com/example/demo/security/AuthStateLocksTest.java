package com.example.demo.security;

import com.example.demo.exception.ServerFailureResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class AuthStateLocksTest {
    @ParameterizedTest @ValueSource(ints={1205,1213})
    void confirmedAuthenticationLockFailuresAre503InsteadOfBusiness409(int vendorCode) {
        var failure=new DataAccessResourceFailureException("Injected",new SQLException("Injected","40001",vendorCode));
        var result=ServerFailureResponse.from(AuthStateLocks.translate(failure));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,result.status());assertEquals("SERVICE_UNAVAILABLE",result.code());
    }

    @ParameterizedTest @ValueSource(strings={"28000","42000","HY000"})
    void unknownOrConfigurationErrorsAreNotTurnedIntoTransientFailures(String state) {
        var failure=new DataAccessResourceFailureException("Injected",new SQLException("Injected",state,0));
        assertSame(failure,AuthStateLocks.translate(failure));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,ServerFailureResponse.from(AuthStateLocks.translate(failure)).status());
    }
}
