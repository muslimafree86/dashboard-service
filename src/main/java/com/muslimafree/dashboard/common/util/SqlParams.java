package com.muslimafree.dashboard.common.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Named parameters for SQL assembled in code: every value is bound under a unique name, so values never reach the SQL
 * text.
 */
public final class SqlParams {

    private final Map<String, Object> values = new LinkedHashMap<>();

    /** Binds {@code value} and returns its placeholder, e.g. {@code :city} (or {@code :city2} when taken). */
    public String add(String name, Object value) {
        String key = name;
        int i = 1;
        while (values.containsKey(key)) {
            key = name + (++i);
        }
        values.put(key, value);
        return ":" + key;
    }

    public Map<String, Object> values() {
        return values;
    }
}
