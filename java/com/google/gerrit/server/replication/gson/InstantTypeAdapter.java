package com.google.gerrit.server.replication.gson;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import java.lang.reflect.Type;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * Serializer for java.time.Instant instances which appear in replicated event json.
 * By default, Gson uses reflection to serialise objects, but it can't access the private fields of java.time.Instant,
 * so this is a custom serializer that uses the ISO-8601 (default) string representation of Instance.
 *
 * <p>Hopefully in future Gson will provide serializers for Java 8+ built-ins: https://github.com/google/gson/issues/1059
 */
public class InstantTypeAdapter implements JsonDeserializer<Instant>, JsonSerializer<Instant> {

    @Override
    public JsonElement serialize(Instant src, Type typeOfSrc, JsonSerializationContext context) {
        String isoInstant = DateTimeFormatter.ISO_INSTANT.format(src);
        return context.serialize(isoInstant);
    }

    @Override
    public Instant deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
            throws JsonParseException {

        if (!json.isJsonPrimitive()) {
            throw new JsonParseException("Instant element is not a primitive json value: " + json);
        }

        return Instant.parse(json.getAsString());
    }
}
