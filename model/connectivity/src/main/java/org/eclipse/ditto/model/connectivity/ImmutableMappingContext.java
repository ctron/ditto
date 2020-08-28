/*
 * Copyright (c) 2017 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.model.connectivity;

import static org.eclipse.ditto.model.base.common.ConditionChecker.checkNotNull;

import java.util.Objects;
import java.util.function.Predicate;

import javax.annotation.concurrent.Immutable;

import org.eclipse.ditto.json.JsonFactory;
import org.eclipse.ditto.json.JsonField;
import org.eclipse.ditto.json.JsonObject;
import org.eclipse.ditto.json.JsonObjectBuilder;
import org.eclipse.ditto.model.base.json.JsonSchemaVersion;

/**
 * Immutable implementation of {@link MappingContext}.
 */
@Immutable
final class ImmutableMappingContext implements MappingContext {

    private final String mappingEngine;
    private final JsonObject options;


    private ImmutableMappingContext(final String mappingEngine, final JsonObject options) {

        this.mappingEngine = mappingEngine;
        this.options = options;
    }

    /**
     * Creates a new {@code ImmutableMappingContext} instance.
     *
     * @param mappingEngine the mapping engine to use as fully qualified classname of an implementation of
     * {@code MessageMapper} interface.
     * @param options the mapping engine specific options to apply.
     * @return a new instance of ImmutableMappingContext.
     */
    public static ImmutableMappingContext of(final String mappingEngine, final JsonObject options) {
        checkNotNull(mappingEngine, "mapping Engine");
        checkNotNull(options, "options");

        return new ImmutableMappingContext(mappingEngine, options);
    }

    /**
     * Creates a new {@code MappingContext} object from the specified JSON object.
     *
     * @param jsonObject a JSON object which provides the data for the MappingContext to be created.
     * @return a new MappingContext which is initialised with the extracted data from {@code jsonObject}.
     * @throws NullPointerException if {@code jsonObject} is {@code null}.
     * @throws org.eclipse.ditto.json.JsonParseException if {@code jsonObject} is not an appropriate JSON object.
     */
    public static MappingContext fromJson(final JsonObject jsonObject) {
        final String mappingEngine = jsonObject.getValueOrThrow(JsonFields.MAPPING_ENGINE);
        final JsonObject options = jsonObject.getValueOrThrow(JsonFields.OPTIONS);

        return of(mappingEngine, options);
    }

    @Override
    public JsonObject toJson(final JsonSchemaVersion schemaVersion, final Predicate<JsonField> thePredicate) {
        final Predicate<JsonField> predicate = schemaVersion.and(thePredicate);
        final JsonObjectBuilder jsonObjectBuilder = JsonFactory.newObjectBuilder();

        jsonObjectBuilder.set(JsonFields.MAPPING_ENGINE, mappingEngine, predicate);
        jsonObjectBuilder.set(JsonFields.OPTIONS, options, predicate);

        return jsonObjectBuilder.build();
    }

    @Override
    public String getMappingEngine() {
        return mappingEngine;
    }

    @Override
    public JsonObject getOptionsAsJson() {
        return options;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ImmutableMappingContext)) {
            return false;
        }
        final ImmutableMappingContext that = (ImmutableMappingContext) o;
        return Objects.equals(mappingEngine, that.mappingEngine) &&
                Objects.equals(options, that.options);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mappingEngine, options);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + " [" +
                "mappingEngine=" + mappingEngine +
                ", options=" + options +
                "]";
    }
}
