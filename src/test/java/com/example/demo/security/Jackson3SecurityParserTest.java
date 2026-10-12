package com.example.demo.security;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.annotation.JsonView;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.async.ByteArrayFeeder;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.core.io.NumberInput;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/** Verify the new HTTP JSON family separately from JJWT's retained Jackson 2 family. */
class Jackson3SecurityParserTest {
    interface PublicView { }
    interface AdminView extends PublicView { }
    static class Flags { public String role; }
    static class Account {
        @JsonView(AdminView.class) @JsonUnwrapped public Flags flags;
    }

    @Test
    void publicViewCannotPopulateRestrictedUnwrappedContainer() {
        var account = new JsonMapper().readerWithView(PublicView.class).forType(Account.class)
                .<Account>readValue("{\"role\":\"ADMIN\"}");
        assertNull(account.flags);
    }

    @Test
    void dataInputInvalidTokenHasBoundedDiagnosticLength() throws Exception {
        byte[] input = ("a".repeat(8192) + " ").getBytes(StandardCharsets.UTF_8);
        try (var data = new DataInputStream(new ByteArrayInputStream(input));
             var parser = new JsonMapper().createParser((DataInput) data)) {
            var error = assertThrows(JacksonException.class, parser::nextToken);
            assertTrue(error.getMessage().length() < 1024);
        }
    }

    @Test
    void invalidNumericLookingStringHasBoundedProcessingTime() {
        String input = "1".repeat(32768) + "x";
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertFalse(NumberInput.looksLikeValidNumber(input)));
        assertTrue(NumberInput.looksLikeValidNumber("123.5"));
    }

    @Test
    void asyncChunkedDigitsCannotGrowPastConfiguredNumberLimit() {
        var factory = JsonFactory.builder().streamReadConstraints(
                StreamReadConstraints.builder().maxNumberLength(32).build()).build();
        try (var parser = JsonMapper.builder(factory).build().createNonBlockingByteArrayParser()) {
            var feeder = (ByteArrayFeeder) parser.nonBlockingInputFeeder();
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
