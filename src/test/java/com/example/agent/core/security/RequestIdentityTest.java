package com.example.agent.core.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

class RequestIdentityTest {

    private final MockHttpSession session = new MockHttpSession();

    private MockHttpServletRequest request() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setSession(session);
        return r;
    }

    @Test
    void anonymousRequestsAreRejectedUnlessDemoModeIsOn() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> new RequestIdentity(false, "C-1001").resolve(request(), "c1"));
        assertEquals(HttpStatus.UNAUTHORIZED.value(), e.getStatusCode().value());
    }

    @Test
    void demoModeActsAsTheDemoCustomer() {
        Caller caller = new RequestIdentity(true, "C-1001").resolve(request(), "c1");
        assertEquals("C-1001", caller.customerId());
        assertEquals(36, caller.scope().length(), "must fit Spring AI's VARCHAR(36) conversation_id");
        assertDoesNotThrow(() -> java.util.UUID.fromString(caller.scope()));
    }

    @Test
    void theCustomerComesFromThePrincipalNeverFromParameters() {
        MockHttpServletRequest r = request();
        r.setUserPrincipal(() -> "C-2002");
        assertEquals("C-2002", new RequestIdentity(true, "C-1001").resolve(r, "c1").customerId());
    }

    @Test
    void scopeIsStablePerConversationAndDifferentAcrossThem() {
        RequestIdentity identity = new RequestIdentity(true, "C-1001");
        assertEquals(identity.resolve(request(), "c1").scope(), identity.resolve(request(), "c1").scope());
        assertNotEquals(identity.resolve(request(), "c1").scope(), identity.resolve(request(), "c2").scope());
    }

    @Test
    void sameConversationNameInAnotherSessionIsAnotherScope() {
        RequestIdentity identity = new RequestIdentity(true, "C-1001");
        MockHttpServletRequest other = new MockHttpServletRequest();
        other.setSession(new MockHttpSession());
        assertNotEquals(identity.resolve(request(), "c1").scope(), identity.resolve(other, "c1").scope());
    }

    @Test
    void conversationIdsAreValidated() {
        assertThrows(ResponseStatusException.class, () -> new RequestIdentity(true, "C-1001").resolve(request(), "../etc"));
    }

    @Test
    void lengthPrefixingPreventsBoundaryCollisions() {
        assertNotEquals(RequestIdentity.scope("ab", "c"), RequestIdentity.scope("a", "bc"));
    }
}
