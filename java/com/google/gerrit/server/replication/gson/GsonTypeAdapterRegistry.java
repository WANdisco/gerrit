package com.google.gerrit.server.replication.gson;

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import com.google.gerrit.entities.EntitiesAdapterFactory;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.EventDeserializer;
import com.google.gerrit.server.events.ProjectNameKeyAdapter;
import com.google.gerrit.server.events.SupplierDeserializer;
import com.google.gerrit.server.events.SupplierSerializer;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapterFactory;

import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GsonTypeAdapterRegistry {

    private static Map<Type, Object> typeAdapters = new HashMap<>();
    private static List<TypeAdapterFactory> typeAdapterFactories = new ArrayList<>();
    private static Map<Type, Object> typeHierarchyAdapters = new HashMap<>();


    public enum AdapterKind{
        TYPE_ADAPTER, FACTORY, TYPE_HIERARCHY_ADAPTER
    }


    // Register type adapters with a GsonBuilder instance.
    public static void registerAdapters(GsonBuilder gsonBuilder, Map<AdapterKind, Object> adapterKindObjectMap) {
        for (Map.Entry<AdapterKind, Object> entry : adapterKindObjectMap.entrySet()) {
            AdapterKind kind = entry.getKey();
            Object adapters = entry.getValue();

            switch (kind) {
                case TYPE_ADAPTER:
                    // Register type adapters
                    if (adapters instanceof Map<?, ?> typeAdapterMap) {
                        for (Map.Entry<?, ?> typeEntry : typeAdapterMap.entrySet()) {
                            Type type = (Type) typeEntry.getKey();
                            Object adapterList = typeEntry.getValue();

                            // The value may be a List.of(adapter instances) or it may be
                            // a single adapter instance. We handle both cases here.
                            if (adapterList instanceof List<?> adapterObjects) {
                                for (Object adapter : adapterObjects) {
                                    gsonBuilder.registerTypeAdapter(type, adapter);
                                }
                            } else {
                                gsonBuilder.registerTypeAdapter(type, adapterList);
                            }
                        }
                    }
                    break;

                case FACTORY:
                    // Register type adapter factories
                    if (adapters instanceof List<?> factoryList) {
                        for (Object factory : factoryList) {
                            if (factory instanceof TypeAdapterFactory) {
                                gsonBuilder.registerTypeAdapterFactory((TypeAdapterFactory) factory);
                            }
                        }
                    }
                    break;

                case TYPE_HIERARCHY_ADAPTER:
                    // Register type hierarchy adapters
                    if (adapters instanceof Map<?, ?> hierarchyAdapterMap) {
                        for (Map.Entry<?, ?> hierarchyEntry : hierarchyAdapterMap.entrySet()) {
                            Type type = (Type) hierarchyEntry.getKey();
                            Object adapter = hierarchyEntry.getValue();
                            gsonBuilder.registerTypeHierarchyAdapter(type.getClass(), adapter);
                        }
                    }
                    break;

                default:
                    throw new IllegalArgumentException("Unknown AdapterKind: " + kind);
            }
        }
    }


    // This method populates the collections associated with type adapters, then returns an immutable
    // Map with all adapters keyed by AdapterKind.
    //
    // Having an adapters map allows us to easily register new adapters but in order to
    // register a new adapter at runtime we need to know what kind of adapter it is. The enum
    // AdapterKind allows us to understand the type of adapter we need to register.
    public static Map<AdapterKind, Object> getDefaultAdaptersMap() {

        populateDefaultTypeAdaptersCollections();

        return Map.of(
                AdapterKind.TYPE_ADAPTER, typeAdapters,
                AdapterKind.FACTORY, typeAdapterFactories,
                AdapterKind.TYPE_HIERARCHY_ADAPTER, typeHierarchyAdapters
        );
    }

    // When registering a type adapter, please register it here.
    // * If registering for a specific type, then simply add it to the typeAdapters map.
    // * If you want to register for a base type and its subtypes then use the typeAdaptersHierarchy map. A
    //   TypeHierarchyAdapter operates on a type hierarchy,
    //   meaning it can handle the base type and any of its subtypes.
    // * Finally, factories should be placed in the typeAdpaterFactories map.
    //   A TypeAdapterFactory creates TypeAdapter instances dynamically based on the type being
    //   serialized or deserialized.
    private static void populateDefaultTypeAdaptersCollections() {

        // Need a key and a list as the value as some keys such as Supplier specify multiple classes for
        // serialization/de-serialization
        typeAdapters = Map.of(
            Instant.class, List.of(new InstantTypeAdapter()),
            Supplier.class, List.of(new SupplierSerializer(), new SupplierDeserializer()),
            Event.class, List.of(new EventDeserializer()),
            ImmutableMap.class, List.of(new ImmutableMapTypeAdapter()));

        typeAdapterFactories = List.of(EntitiesAdapterFactory.create());

        typeHierarchyAdapters = Map.of(
                Project.NameKey.class, new ProjectNameKeyAdapter()
        );
    }

}
