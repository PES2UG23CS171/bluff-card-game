package com.bluffgame.engine;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Something that happened during a game, in the order it happened. Clients replay events to
 * drive animations before showing the resulting state. Serialises as a flat JSON object with a
 * {@code kind} field.
 */
public final class GameEvent {

    private final Map<String, Object> fields = new LinkedHashMap<>();

    public GameEvent(String kind) {
        fields.put("kind", kind);
    }

    public GameEvent with(String key, Object value) {
        fields.put(key, value);
        return this;
    }

    public String kind() {
        return (String) fields.get("kind");
    }

    public Object get(String key) {
        return fields.get(key);
    }

    @JsonValue
    public Map<String, Object> fields() {
        return Collections.unmodifiableMap(fields);
    }

    @Override
    public String toString() {
        return fields.toString();
    }
}
