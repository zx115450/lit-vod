package com.example.vod.service;

import com.example.vod.config.InternalProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InternalTokenValidatorTest {

    @Test
    void shouldAcceptMatchingToken() {
        InternalTokenValidator v = new InternalTokenValidator(
                new InternalProperties("secret-token", true));
        assertDoesNotThrow(() -> v.requireValid("secret-token"));
    }

    @Test
    void shouldRejectMissingOrWrong() {
        InternalTokenValidator v = new InternalTokenValidator(
                new InternalProperties("secret-token", true));
        assertEquals(401, assertThrows(ResponseStatusException.class,
                () -> v.requireValid(null)).getStatusCode().value());
        assertEquals(401, assertThrows(ResponseStatusException.class,
                () -> v.requireValid("")).getStatusCode().value());
        assertEquals(401, assertThrows(ResponseStatusException.class,
                () -> v.requireValid("wrong")).getStatusCode().value());
    }

    @Test
    void shouldSkipWhenDisabled() {
        InternalTokenValidator v = new InternalTokenValidator(
                new InternalProperties("secret-token", false));
        assertDoesNotThrow(() -> v.requireValid(null));
    }
}
