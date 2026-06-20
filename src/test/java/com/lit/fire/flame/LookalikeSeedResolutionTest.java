package com.lit.fire.flame;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for seed-author resolution. {@code resolveSeedAuthorId} keys off the
 * in-memory profiles list only, so these run without Spring or a database.
 */
public class LookalikeSeedResolutionTest {

    private final LookalikeDiscoveryService service = new LookalikeDiscoveryService();

    private static List<Map<String, Object>> profiles(String... ids) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String id : ids) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("global_user_id", id);
            out.add(p);
        }
        return out;
    }

    @Test
    public void exactMatchReturnsStoredKeyUnchanged() {
        List<Map<String, Object>> profiles = profiles("KVN Productions", "Other Author");
        assertEquals("KVN Productions", service.resolveSeedAuthorId(profiles, "KVN Productions"));
    }

    @Test
    public void resolvesCaseWhitespaceAndPunctuationVariantsToStoredKey() {
        List<Map<String, Object>> profiles = profiles("KVN Productions");
        // Casing, an '@' prefix, and collapsed whitespace all normalize to the stored key.
        assertEquals("KVN Productions", service.resolveSeedAuthorId(profiles, "kvn productions"));
        assertEquals("KVN Productions", service.resolveSeedAuthorId(profiles, "@KVNProductions"));
        assertEquals("KVN Productions", service.resolveSeedAuthorId(profiles, "  KVN  Productions  "));
    }

    @Test
    public void unknownSeedThrowsWithDidYouMeanHint() {
        List<Map<String, Object>> profiles = profiles("KVN Productions");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.resolveSeedAuthorId(profiles, "KVN"));
        assertTrue(ex.getMessage().startsWith("Unknown seedAuthorId: KVN"), ex.getMessage());
        assertTrue(ex.getMessage().contains("KVN Productions"), ex.getMessage());
    }

    @Test
    public void fullyUnknownSeedThrowsWithoutSuggestions() {
        List<Map<String, Object>> profiles = profiles("KVN Productions");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.resolveSeedAuthorId(profiles, "Zzz Nobody"));
        assertEquals("Unknown seedAuthorId: Zzz Nobody.", ex.getMessage());
    }

    @Test
    public void ambiguousNormalizedFormIsReportedNotSilentlyPicked() {
        // Two distinct stored keys collapse to the same normalized form (split profiles).
        List<Map<String, Object>> profiles = profiles("@KVNProductions", "KVN Productions");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.resolveSeedAuthorId(profiles, "kvnproductions"));
        assertTrue(ex.getMessage().startsWith("Ambiguous seedAuthorId"), ex.getMessage());
        assertTrue(ex.getMessage().contains("@KVNProductions"), ex.getMessage());
        assertTrue(ex.getMessage().contains("KVN Productions"), ex.getMessage());
    }

    @Test
    public void exactMatchWinsEvenWhenAnotherKeySharesNormalizedForm() {
        // If the exact key exists it must be returned verbatim, never treated as ambiguous.
        List<Map<String, Object>> profiles = profiles("KVN Productions", "@KVNProductions");
        assertEquals("KVN Productions", service.resolveSeedAuthorId(profiles, "KVN Productions"));
    }

    @Test
    public void shortJunkKeysAreNotSuggested() {
        // Regression: a long seed used to surface every 1-2 char author as a
        // "Did you mean", producing garbage like "[c, i, C, D, N]" because each is
        // a substring of "kvnproductions". Those must never be suggested; the
        // genuinely-near "Kvn" is the only acceptable hint.
        List<Map<String, Object>> profiles = profiles("c", "i", "C", "D", "N", "Kvn", "Jane Doe");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.resolveSeedAuthorId(profiles, "KVN Productions"));
        String msg = ex.getMessage();
        assertTrue(msg.startsWith("Unknown seedAuthorId: KVN Productions"), msg);
        for (String junk : new String[]{"[c,", " c,", " c]", " i,", " D,", " N]"}) {
            assertFalse(msg.contains(junk), "junk single-char key leaked into suggestions: " + msg);
        }
        assertTrue(msg.contains("Kvn"), "the near match should still be offered: " + msg);
        assertFalse(msg.contains("Jane Doe"), "an unrelated author must not be suggested: " + msg);
    }

    @Test
    public void closeTypoIsSuggested() {
        // A near-miss (single transposed/dropped char) should still be offered.
        List<Map<String, Object>> profiles = profiles("KVN Productions", "Unrelated Person");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.resolveSeedAuthorId(profiles, "KVN Prodctions"));
        assertTrue(ex.getMessage().contains("KVN Productions"), ex.getMessage());
        assertFalse(ex.getMessage().contains("Unrelated Person"), ex.getMessage());
    }
}
