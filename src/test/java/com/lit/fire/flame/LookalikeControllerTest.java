package com.lit.fire.flame;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the HTTP contract of {@link LookalikeController}: a resolution failure
 * surfaces as a 400 whose body carries the service's message verbatim (this is the
 * exact shape the gateway wrapped as {@code upstreamStatus:400, upstreamBody:...}),
 * and a successful lookup returns 200 with the result list.
 */
public class LookalikeControllerTest {

    private LookalikeDiscoveryService service;
    private LookalikeController controller;

    @BeforeEach
    public void setUp() {
        service = mock(LookalikeDiscoveryService.class);
        controller = new LookalikeController();
        ReflectionTestUtils.setField(controller, "lookalikeDiscoveryService", service);
    }

    @Test
    public void blankSeedIsRejectedBeforeHittingTheService() {
        ResponseEntity<?> resp = controller.findLookalikes(Map.of("seedAuthorId", "   "));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("seedAuthorId is required", resp.getBody());
    }

    @Test
    public void missingSeedKeyIsRejected() {
        ResponseEntity<?> resp = controller.findLookalikes(Map.of());
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("seedAuthorId is required", resp.getBody());
    }

    @Test
    public void unknownSeedSurfacesServiceMessageAs400Body() {
        String message = "Unknown seedAuthorId: KVN Productions. Did you mean: [KVN Productions Inc]?";
        when(service.findLookalikes(eq("KVN Productions"), anyInt()))
                .thenThrow(new IllegalArgumentException(message));

        ResponseEntity<?> resp = controller.findLookalikes(Map.of("seedAuthorId", "KVN Productions"));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals(message, resp.getBody());
    }

    @Test
    public void successfulLookupReturns200WithResults() {
        List<Map<String, Object>> lookalikes = List.of(Map.of("global_user_id", "Jane Doe"));
        when(service.findLookalikes(eq("KVN Productions"), anyInt())).thenReturn(lookalikes);

        ResponseEntity<?> resp = controller.findLookalikes(Map.of("seedAuthorId", "KVN Productions"));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(lookalikes, resp.getBody());
    }

    @Test
    public void diffEndpointSurfacesServiceMessageAs400Body() {
        when(service.diffLookalikes(eq("ghost"), anyInt()))
                .thenThrow(new IllegalArgumentException("Unknown seedAuthorId: ghost."));

        ResponseEntity<?> resp = controller.diffLookalikes("ghost", 25);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("Unknown seedAuthorId: ghost.", resp.getBody());
    }
}
