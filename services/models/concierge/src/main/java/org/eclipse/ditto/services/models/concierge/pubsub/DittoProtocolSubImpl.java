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
package org.eclipse.ditto.services.models.concierge.pubsub;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.eclipse.ditto.model.base.acks.AcknowledgementLabel;
import org.eclipse.ditto.services.models.concierge.streaming.StreamingType;
import org.eclipse.ditto.services.models.things.ThingEventPubSubFactory;
import org.eclipse.ditto.services.utils.pubsub.DistributedAcks;
import org.eclipse.ditto.services.utils.pubsub.DistributedSub;

import akka.actor.ActorContext;
import akka.actor.ActorRef;

/**
 * Default implementation of {@link DittoProtocolSub}.
 */
final class DittoProtocolSubImpl implements DittoProtocolSub {

    private final DistributedSub liveSignalSub;
    private final DistributedSub twinEventSub;
    private final DistributedAcks distributedAcks;

    private DittoProtocolSubImpl(final DistributedSub liveSignalSub,
            final DistributedSub twinEventSub,
            final DistributedAcks distributedAcks) {
        this.liveSignalSub = liveSignalSub;
        this.twinEventSub = twinEventSub;
        this.distributedAcks = distributedAcks;
    }

    static DittoProtocolSubImpl of(final ActorContext context, final DistributedAcks distributedAcks) {
        final DistributedSub liveSignalSub =
                LiveSignalPubSubFactory.of(context, distributedAcks).startDistributedSub();
        final DistributedSub twinEventSub =
                ThingEventPubSubFactory.readSubjectsOnly(context, distributedAcks).startDistributedSub();
        return new DittoProtocolSubImpl(liveSignalSub, twinEventSub, distributedAcks);
    }

    @Override
    public CompletionStage<Void> subscribe(final Collection<StreamingType> types,
            final Collection<String> topics,
            final ActorRef subscriber) {
        final CompletionStage<?> nop = CompletableFuture.completedFuture(null);
        return partitionByStreamingTypes(types,
                liveTypes -> !liveTypes.isEmpty()
                        ? liveSignalSub.subscribeWithFilterAndAck(topics, subscriber, toFilter(liveTypes))
                        : nop,
                hasTwinEvents -> hasTwinEvents
                        ? twinEventSub.subscribeWithAck(topics, subscriber)
                        : nop
        );
    }

    @Override
    public void removeSubscriber(final ActorRef subscriber) {
        liveSignalSub.removeSubscriber(subscriber);
        twinEventSub.removeSubscriber(subscriber);
    }

    @Override
    public CompletionStage<Void> updateLiveSubscriptions(final Collection<StreamingType> types,
            final Collection<String> topics,
            final ActorRef subscriber) {

        return partitionByStreamingTypes(types,
                liveTypes -> !liveTypes.isEmpty()
                        ? liveSignalSub.subscribeWithFilterAndAck(topics, subscriber, toFilter(liveTypes))
                        : liveSignalSub.unsubscribeWithAck(topics, subscriber),
                hasTwinEvents -> CompletableFuture.completedFuture(null)
        );
    }

    @Override
    public CompletionStage<Void> removeTwinSubscriber(final ActorRef subscriber, final Collection<String> topics) {
        return twinEventSub.unsubscribeWithAck(topics, subscriber).thenApply(ack -> null);
    }

    @Override
    public CompletionStage<Void> declareAcknowledgementLabels(
            final Collection<AcknowledgementLabel> acknowledgementLabels,
            final ActorRef subscriber) {
        if (acknowledgementLabels.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        // don't complete the future with the exception this method emits as this is a bug in Ditto which we must escalate
        // via the actor supervision strategy
        ensureAcknowledgementLabelsAreFullyResolved(acknowledgementLabels);

        return distributedAcks.declareAcknowledgementLabels(acknowledgementLabels, subscriber).thenApply(ack -> null);
        // no need to declare the labels for liveSignalSub because acks distributed data does not start there
    }

    private static void ensureAcknowledgementLabelsAreFullyResolved(final Collection<AcknowledgementLabel> ackLabels) {
        ackLabels.stream()
                .filter(Predicate.not(AcknowledgementLabel::isFullyResolved))
                .findFirst()
                .ifPresent(ackLabel -> {
                    // if this happens, this is a bug in the Ditto codebase! at this point the AckLabel must be resolved
                    throw new IllegalArgumentException("AcknowledgementLabel was not fully resolved while " +
                            "trying to declare it: " + ackLabel);
                });
    }

    @Override
    public void removeAcknowledgementLabelDeclaration(final ActorRef subscriber) {
        distributedAcks.removeAcknowledgementLabelDeclaration(subscriber);
    }

    private CompletionStage<Void> partitionByStreamingTypes(final Collection<StreamingType> types,
            final Function<Set<StreamingType>, CompletionStage<?>> onLiveSignals,
            final Function<Boolean, CompletionStage<?>> onTwinEvents) {
        final Set<StreamingType> liveTypes;
        final boolean hasTwinEvents;
        if (types.isEmpty()) {
            liveTypes = Collections.emptySet();
            hasTwinEvents = false;
        } else {
            liveTypes = EnumSet.copyOf(types);
            hasTwinEvents = liveTypes.remove(StreamingType.EVENTS);
        }
        final CompletableFuture<?> liveStage = onLiveSignals.apply(liveTypes).toCompletableFuture();
        final CompletableFuture<?> twinStage = onTwinEvents.apply(hasTwinEvents).toCompletableFuture();
        return CompletableFuture.allOf(liveStage, twinStage);
    }

    private static Predicate<Collection<String>> toFilter(final Collection<StreamingType> streamingTypes) {
        final Set<String> streamingTypeTopics =
                streamingTypes.stream().map(StreamingType::getDistributedPubSubTopic).collect(Collectors.toSet());
        return topics -> topics.stream().anyMatch(streamingTypeTopics::contains);
    }

}
