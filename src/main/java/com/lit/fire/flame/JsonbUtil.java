package com.lit.fire.flame;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;

/**
 * JdbcTemplate.queryForList() returns jsonb columns as org.postgresql.util.PGobject
 * (not String, not parsed). Spring's Jackson serializer then exposes the wrapper's
 * bean fields ({"type":"jsonb","value":"...","null":true}) instead of the JSON tree.
 * These helpers normalize PGobject vs raw-string returns at the boundary.
 */
final class JsonbUtil {

    private static final Type TREE_TYPE = new TypeToken<Object>() {}.getType();

    private JsonbUtil() {}

    /** Returns the underlying JSON text (PGobject.getValue() or the raw String), or null. */
    static String asJsonString(Object value) {
        if (value == null) return null;
        if (value instanceof String s) return s;
        // org.postgresql.util.PGobject is referenced by name to avoid a hard import here.
        String s = value.toString();
        return s == null || s.isEmpty() ? null : s;
    }

    /** Parses the underlying JSON text into a Map/List/primitive tree for response embedding. */
    static Object asTree(Object value, Gson gson) {
        String json = asJsonString(value);
        if (json == null || json.isBlank()) return null;
        return gson.fromJson(json, TREE_TYPE);
    }
}
