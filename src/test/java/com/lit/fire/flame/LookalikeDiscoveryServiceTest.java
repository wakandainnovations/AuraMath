package com.lit.fire.flame;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Drives the public {@code findLookalikes} / {@code findLookalikesL2Legacy} paths
 * over a mocked {@link JdbcTemplate} to prove seed resolution is wired into both
 * entry points — a caller-supplied display-string variant must resolve to the
 * stored profile rather than 400. Sentiment queries (the void
 * {@code query(String, RowCallbackHandler)} overload) are left unstubbed, so the
 * similarity reduces to the always-defined MOI block.
 */
public class LookalikeDiscoveryServiceTest {

    private JdbcTemplate jdbc;
    private LookalikeDiscoveryService service;

    private static Map<String, Object> profile(String id, String tribe, double moi, int postCount) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("global_user_id", id);
        p.put("tribe_label", tribe);
        p.put("moi_score", moi);
        p.put("top_genres", "{}");
        p.put("platform_handles", "{\"by_platform\":{\"x\":{\"post_count\":" + postCount + "}}}");
        return p;
    }

    @BeforeEach
    public void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new LookalikeDiscoveryService();
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbc);
    }

    private void stubProfiles(List<Map<String, Object>> profiles) {
        // findLookalikes / L2Legacy each issue exactly one queryForList(String) for the
        // profile table; the sentiment loads use the void query(...) overload instead.
        when(jdbc.queryForList(anyString())).thenReturn(profiles);
    }

    @Test
    public void findLookalikesResolvesVariantSeedAndExcludesItFromResults() {
        List<Map<String, Object>> profiles = new ArrayList<>(List.of(
                profile("KVN Productions", "Tribe_A", 2.0, 10),
                profile("Jane Doe", "Tribe_A", 1.8, 10)));
        stubProfiles(profiles);

        // Caller passes a lowercase, '@'-prefixed variant of the stored display name.
        List<Map<String, Object>> result = service.findLookalikes("@kvnproductions", 25);

        List<String> ids = result.stream().map(r -> (String) r.get("global_user_id")).toList();
        assertFalse(ids.contains("KVN Productions"), "seed must be excluded from its own lookalikes");
        assertTrue(ids.contains("Jane Doe"), "same-tribe candidate should be returned: " + ids);
    }

    @Test
    public void findLookalikesThrowsForGenuinelyUnknownSeed() {
        stubProfiles(new ArrayList<>(List.of(profile("KVN Productions", "Tribe_A", 2.0, 10))));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.findLookalikes("Totally Different Name", 25));
        assertTrue(ex.getMessage().startsWith("Unknown seedAuthorId: Totally Different Name"), ex.getMessage());
    }

    @Test
    public void findLookalikesReturnsEmptyWhenNoProfilesExist() {
        stubProfiles(new ArrayList<>());
        assertTrue(service.findLookalikes("anything", 25).isEmpty());
    }

    @Test
    public void legacyL2PathAlsoResolvesVariantSeed() {
        stubProfiles(new ArrayList<>(List.of(
                profile("KVN Productions", "Tribe_A", 2.0, 10),
                profile("Jane Doe", "Tribe_A", 1.8, 10))));

        // Must not throw "Unknown seedAuthorId" — resolution is wired into the legacy path too.
        List<Map<String, Object>> result = service.findLookalikesL2Legacy("  kvn productions  ", 25);
        List<String> ids = result.stream().map(r -> (String) r.get("global_user_id")).toList();
        assertFalse(ids.contains("KVN Productions"), "seed must be excluded from its own lookalikes");
    }

    @Test
    public void legacyL2PathThrowsForUnknownSeed() {
        stubProfiles(new ArrayList<>(List.of(profile("KVN Productions", "Tribe_A", 2.0, 10))));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.findLookalikesL2Legacy("nobody", 25));
        assertEquals("Unknown seedAuthorId: nobody.", ex.getMessage());
    }
}
