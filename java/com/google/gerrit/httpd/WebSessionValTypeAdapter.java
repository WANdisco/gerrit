package com.google.gerrit.httpd;

import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Account;
import com.google.gerrit.server.account.externalids.ExternalId;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * This adapter has been written to be more generic and resilient to changes in the structure
 * of WebSessionManager.Val. Dynamic introspection of the fields of the class occurs during serialization
 * and deserialization. The benefit of this is that it removes the need to hardcode the fields,
 * making the adapter adaptable to future changes.
 **/
public class WebSessionValTypeAdapter extends TypeAdapter<WebSessionManager.Val> {

    @Override
    public void write(JsonWriter out, WebSessionManager.Val val) throws IOException {
        out.beginObject();
        for (Field field : WebSessionManager.Val.class.getDeclaredFields()) {
            field.setAccessible(true);
            try {
                Object value = field.get(val);
                out.name(field.getName());
                if (value == null) {
                    out.nullValue();
                } else {
                    out.value(value.toString());
                }
            } catch (IllegalAccessException e) {
                throw new IOException("Failed to serialize field: " + field.getName(), e);
            }
        }
        out.endObject();
    }

    @Override
    public WebSessionManager.Val read(JsonReader in) throws IOException {
        Map<String, Object> fieldValues = new HashMap<>();
        in.beginObject();
        while (in.hasNext()) {
            String name = in.nextName();
            JsonToken token = in.peek();
            Object value = null;

            switch (token) {
                case STRING:
                    value = in.nextString();
                    break;
                case NUMBER:
                    value = in.nextDouble();
                    break;
                case BOOLEAN:
                    value = in.nextBoolean();
                    break;
                case NULL:
                    in.nextNull();
                    break;
                default:
                    throw new IOException("Unexpected token: " + token);
            }
            fieldValues.put(name, value);
        }
        in.endObject();

        try {
            Constructor<?> constructor = WebSessionManager.Val.class.getDeclaredConstructors()[0];
            constructor.setAccessible(true);

            // This code map fields names dynamically to constructor parameters using the convertValueToFieldType method
            Object[] constructorArgs = Arrays.stream(constructor.getParameters())
                    .map(param -> convertValueToFieldType(param.getType(), fieldValues, param.getName()))
                    .toArray();

            return (WebSessionManager.Val) constructor.newInstance(constructorArgs);
        } catch (Exception e) {
            throw new IOException("Failed to deserialize WebSessionManager.Val", e);
        }
    }

    @Nullable
    private Object convertValueToFieldType(Class<?> fieldType, Map<String, Object> fieldValues, String fieldName) {
        Object value = fieldValues.get(fieldName);

        if (value == null) {
            return getDefaultForType(fieldType);
        }

        // There are some custom members of WebSessionManager.Val that we still need to handle.
        // Its possible that these may change in future in which case the, these blocks must also change.
        if (fieldType == Account.Id.class) {
            return Account.id(Integer.parseInt(value.toString()));
        }
        if (fieldType == ExternalId.Key.class) {
            return WebSessionManager.Val.externalIdKeyFactory.parse(value.toString());
        }
        if (fieldType == long.class || fieldType == Long.class) {
            return Long.parseLong(value.toString());
        }
        if (fieldType == boolean.class || fieldType == Boolean.class) {
            if (value instanceof Boolean) {
                return value;
            } else if (value instanceof String) {
                return Boolean.parseBoolean((String) value);
            }
        }
        if (fieldType == int.class || fieldType == Integer.class) {
            return Integer.parseInt(value.toString());
        }
        if (fieldType == String.class) {
            return value.toString();
        }

        throw new IllegalArgumentException("Unsupported field type: "
                + fieldType.getName() + " for field name" + fieldName);
    }

    @Nullable
    private Object getDefaultForType(Class<?> fieldType) {
        if (fieldType == boolean.class) {
            return false;
        }
        if (fieldType == int.class || fieldType == long.class) {
            return 0;
        }
        return null;
    }
}


