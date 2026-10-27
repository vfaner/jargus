package com.qqmu.jargus.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonFieldUtilsTest {

    @Test
    void asTextHandlesScalars() {
        assertEquals("alice", JsonFieldUtils.asText("alice"));
        assertEquals("true", JsonFieldUtils.asText(Boolean.TRUE));
        assertEquals("12", JsonFieldUtils.asText(12));
        assertNull(JsonFieldUtils.asText(null));
        assertNull(JsonFieldUtils.asText(java.util.Map.of("a", 1)));
    }

    @Test
    void matchesIgnoresCaseAndSpaces() {
        assertTrue(JsonFieldUtils.matches(Boolean.TRUE, "true"));
        assertTrue(JsonFieldUtils.matches(" TRUE ", "true"));
        assertTrue(JsonFieldUtils.matches(1, "1"));
        assertFalse(JsonFieldUtils.matches("false", "true"));
        assertFalse(JsonFieldUtils.matches(null, "true"));
        assertFalse(JsonFieldUtils.matches("true", null));
    }

    @Test
    void truthyAcceptsCommonAdminMarkers() {
        assertTrue(JsonFieldUtils.truthy(Boolean.TRUE));
        assertTrue(JsonFieldUtils.truthy(1));
        assertTrue(JsonFieldUtils.truthy(2.5));
        assertTrue(JsonFieldUtils.truthy("YES"));
        assertTrue(JsonFieldUtils.truthy(" administrator "));
        assertFalse(JsonFieldUtils.truthy(Boolean.FALSE));
        assertFalse(JsonFieldUtils.truthy(0));
        assertFalse(JsonFieldUtils.truthy("false"));
        assertFalse(JsonFieldUtils.truthy(null));
    }
}
