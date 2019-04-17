/*
 * Copyright (c) 2019 Contributors to the Eclipse Foundation
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

package org.eclipse.ditto.services.connectivity.messaging.monitoring.logs;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import org.eclipse.ditto.model.base.headers.DittoHeaderDefinition;
import org.eclipse.ditto.services.connectivity.messaging.monitoring.ConnectionMonitor;
import org.eclipse.ditto.services.models.connectivity.ExternalMessage;
import org.eclipse.ditto.signals.base.Signal;
import org.eclipse.ditto.signals.base.WithThingId;

/**
 * Immutable implementation fo {@link org.eclipse.ditto.services.connectivity.messaging.monitoring.ConnectionMonitor.InfoProvider}.
 */
@Immutable
public final class ImmutableInfoProvider implements ConnectionMonitor.InfoProvider {

    private static final String FALLBACK_CORRELATION_ID = "<not-provided>";

    private final String correlationId;
    private final Instant timestamp;
    @Nullable private final String thingId;

    private ImmutableInfoProvider(final String correlationId, final Instant timestamp,
            @Nullable final String thingId) {
        this.correlationId = correlationId;
        this.timestamp = timestamp;
        this.thingId = thingId;
    }

    /**
     * Creates a new info provider that uses {@code externalMessage} for getting a correlation ID.
     * @param externalMessage the external message that might contain a correlation ID.
     * @return an info provider with a correlation ID.
     */
    public static ConnectionMonitor.InfoProvider forExternalMessage(final ExternalMessage externalMessage) {
        final String correlationId = extractCorrelationId(externalMessage.getHeaders());
        final Instant timestamp = Instant.now();

        return new ImmutableInfoProvider(correlationId, timestamp, null);
    }

    /**
     * Creates a new info provider that uses {@code signal} for getting a correlation ID and thing ID.
     * @param signal the signal that might contain a correlation ID and thing ID.
     * @return an info provider with a correlation ID and maybe a thing ID.
     */
    public static ConnectionMonitor.InfoProvider forSignal(final Signal<?> signal) {
        final String correlationId = extractCorrelationId(signal.getDittoHeaders());
        final Instant timestamp = Instant.now();
        final String thingId = extractThingId(signal);

        return new ImmutableInfoProvider(correlationId, timestamp, thingId);
    }

    /**
     * Creates a new info provider that uses {@code headers} for getting a correlation ID.
     * @param headers the headers that might contain a correlation ID.
     * @return an info provider with a correlation ID.
     */
    public static ConnectionMonitor.InfoProvider forHeaders(final Map<String, String> headers) {
        final String correlationId = extractCorrelationId(headers);
        final Instant timestamp = Instant.now();

        return new ImmutableInfoProvider(correlationId, timestamp, null);
    }

    private static String extractCorrelationId(final Map<String, String> headers) {
        return headers.getOrDefault(DittoHeaderDefinition.CORRELATION_ID.getKey(), FALLBACK_CORRELATION_ID);
    }

    @Nullable
    private static String extractThingId(final Signal<?> signal) {
        if (signal instanceof WithThingId) {
            return ((WithThingId) signal).getThingId();
        }
        return null;
    }

    public static ConnectionMonitor.InfoProvider empty() {
        return new ImmutableInfoProvider(FALLBACK_CORRELATION_ID, Instant.now(), null);
    }

    @Override
    public String getCorrelationId() {
        return correlationId;
    }

    @Override
    public Instant getTimestamp() {
        return timestamp;
    }

    @Nullable
    @Override
    public String getThingId() {
        return thingId;
    }

    @Override
    public boolean equals(@Nullable final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final ImmutableInfoProvider that = (ImmutableInfoProvider) o;
        return Objects.equals(correlationId, that.correlationId) &&
                Objects.equals(timestamp, that.timestamp) &&
                Objects.equals(thingId, that.thingId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(correlationId, timestamp, thingId);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + " [" +
                ", correlationId=" + correlationId +
                ", timestamp=" + timestamp +
                ", thingId=" + thingId +
                "]";
    }

}
