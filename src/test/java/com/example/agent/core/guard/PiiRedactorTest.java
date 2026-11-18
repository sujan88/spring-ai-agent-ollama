package com.example.agent.core.guard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PiiRedactorTest {

    @Test
    void masksCardNumbersThatPassLuhnInAnyCommonFormat() {
        assertEquals("Card [REDACTED CARD] on file", PiiRedactor.redact("Card 4111111111111111 on file"));
        assertEquals("Card [REDACTED CARD].", PiiRedactor.redact("Card 4111 1111 1111 1111."));
        assertEquals("[REDACTED CARD]", PiiRedactor.redact("5555-5555-5555-4444"));
    }

    @Test
    void leavesNumbersThatAreNotCards() {
        assertEquals("1234 5678 9012 3456", PiiRedactor.redact("1234 5678 9012 3456"), "fails Luhn");
        assertEquals("Order ORD-1002 costs 125.50, call 201-555-0123 by 2026-01-15",
                PiiRedactor.redact("Order ORD-1002 costs 125.50, call 201-555-0123 by 2026-01-15"));
    }

    @Test
    void masksSocialSecurityNumbers() {
        assertEquals("SSN [REDACTED SSN].", PiiRedactor.redact("SSN 123-45-6789."));
    }

    @Test
    void theUnsafeTailIsTheTrailingRunThatCouldBePartOfANumber() {
        assertEquals(11, PiiRedactor.unsafeTail("card 4111 1111 "), "\" 4111 1111 \" waits for the next chunk");
        assertEquals(0, PiiRedactor.unsafeTail("hello "), "spaces alone are safe");
        assertEquals(0, PiiRedactor.unsafeTail("done."));
    }
}
