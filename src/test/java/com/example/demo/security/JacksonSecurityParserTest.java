package com.example.demo.security;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.async.ByteArrayFeeder;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.core.io.NumberInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class JacksonSecurityParserTest {
    interface PublicView { }
    interface AdminView extends PublicView { }
    static class Flags {
        public String role;
    }
    static class Account {
        @JsonView(AdminView.class)
        @JsonUnwrapped
        public Flags flags;
    }

    @Test
    void publicViewCannotPopulateRestrictedUnwrappedContainer() throws Exception {
        // Test-only model. Application request DTOs do not use these annotations.
        var account = new ObjectMapper().readerWithView(PublicView.class).forType(Account.class)
                .<Account>readValue("{\"role\":\"ADMIN\"}");
        assertNull(account.flags, "Restricted container must retain its outer JsonView gate");
    }

    @Test
    void dataInputInvalidTokenHasBoundedDiagnosticLength() throws Exception {
        byte[] input = ("a".repeat(8192) + " ").getBytes(StandardCharsets.UTF_8);
        try (var data = new DataInputStream(new ByteArrayInputStream(input));
             var parser = new JsonFactory().createParser((DataInput) data)) {
            var error = assertThrows(JsonProcessingException.class, parser::nextToken);
            assertTrue(error.getMessage().length() < 1024, "Invalid-token diagnostic must be bounded");
        }
    }

    @Test
    void invalidNumericLookingStringDoesNotTriggerQuadraticRegexWork() {
        // Bounded local synthetic input; no HTTP request or production load.
        String input = "1".repeat(32768) + "x";
        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertFalse(NumberInput.looksLikeValidNumber(input)));
        assertTrue(NumberInput.looksLikeValidNumber("123.5"));
    }

    @Test
    void asyncChunkedDigitsCannotGrowPastConfiguredNumberLimit() throws Exception {
        var factory = JsonFactory.builder().streamReadConstraints(
                StreamReadConstraints.builder().maxNumberLength(32).build()).build();
        try (var parser = factory.createNonBlockingByteArrayParser()) {
            var feeder = (ByteArrayFeeder) parser.getNonBlockingInputFeeder();
            byte[] digits = "1".repeat(16).getBytes(StandardCharsets.UTF_8);
            assertThrows(StreamConstraintsException.class, () -> {
                for (int i = 0; i < 16; i++) {
                    assertTrue(feeder.needMoreInput());
                    feeder.feedInput(digits, 0, digits.length);
                    parser.nextToken();
                }
            });
        }
    }
}
