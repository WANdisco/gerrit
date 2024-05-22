package com.google.gerrit.server.replication.gson;

import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.inject.TypeLiteral;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Map;

/**
 * Generic deserializer for anything trying to parse a json entry into a Google ImmutableMap rather than a
 * built-in Map implementation.
 * (e.g. Project Delete Change Event, which we replicate as json, serialises an entire Project object
 * including the map of boolean config flags which has type {@code ImmutableMap<BooleanProjectConfig, InheritableBoolean>}.)
 *
 * N.B: Due to the entrySet() limitation of assuming the json key is a String, we should only use this to reconstruct
 * objects to the extent that we can read the properties we need as necessary for replication. It might be risky to use this to
 * 'update' the state of an object without ensuring the concrete types are correctly restored.
 */
public class ImmutableMapTypeAdapter implements JsonDeserializer<ImmutableMap<?,?>> {
    @Override
    public ImmutableMap<?,?> deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
            throws JsonParseException {

        if (!json.isJsonObject()) {
            throw new JsonParseException("Map element is not a json Object: " + json);
        }

        // Handle situation where ImmutableMap is used as a rawtype, equivalent of ImmutableMap<?,?>
        ParameterizedType parameterizedType =
                (ParameterizedType) new TypeLiteral<ImmutableMap<?,?>>() {}.getType();
        if (typeOfT instanceof ParameterizedType) {
            parameterizedType = (ParameterizedType) typeOfT;
            if (parameterizedType.getActualTypeArguments().length != 2) {
                throw new JsonParseException("Expected two parameter types in ImmutableMap.");
            }
        }

        // Erk, entrySet() has already decided that the key is a String; try to deserialize the value using the
        // context anyway. This might result in cases where the key isn't isomorphic if it's not trivially convertible from
        // a String.
        final Type valueType = parameterizedType.getActualTypeArguments()[1];

        return json.getAsJsonObject().entrySet().stream()
                .collect(ImmutableMap.toImmutableMap(Map.Entry::getKey, entry -> context.deserialize(entry.getValue(), valueType)));
    }
}
