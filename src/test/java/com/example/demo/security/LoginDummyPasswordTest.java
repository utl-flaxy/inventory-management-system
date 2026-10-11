package com.example.demo.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoginDummyPasswordTest {
    @ParameterizedTest
    @ValueSource(ints = {4, 10, 11})
    void dummyUsesTheSameEncoderCostAndIsNeverRegeneratedOnAccess(int cost) {
        var encoder = spy(new BCryptPasswordEncoder(cost));
        var dummy = new LoginDummyPassword(encoder);
        String registrationHash = encoder.encode("registration-password");
        assertEquals(registrationHash.substring(0, 7), dummy.encoded().substring(0, 7));
        clearInvocations(encoder);
        String initial = dummy.encoded();
        for (int i = 0; i < 3; i++) {
            assertSame(initial, dummy.encoded());
            assertFalse(encoder.matches("wrong-password", dummy.encoded()));
        }
        verify(encoder, never()).encode(any());
        verify(encoder, times(3)).matches("wrong-password", initial);
    }

    @Test
    void separateStartupInstancesUseIndependentRandomDummyPasswords() {
        var encoder = new BCryptPasswordEncoder(4);
        assertNotEquals(new LoginDummyPassword(encoder).encoded(), new LoginDummyPassword(encoder).encoded());
    }
}
