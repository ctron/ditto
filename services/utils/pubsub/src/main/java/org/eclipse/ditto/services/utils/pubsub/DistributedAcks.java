/*
 * Copyright (c) 2020 Contributors to the Eclipse Foundation
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
package org.eclipse.ditto.services.utils.pubsub;

import java.util.Collection;
import java.util.concurrent.CompletionStage;

import org.eclipse.ditto.model.base.acks.AcknowledgementLabel;
import org.eclipse.ditto.services.utils.pubsub.actors.AbstractUpdater;

import akka.actor.ActorContext;
import akka.actor.ActorRef;

/**
 * Interface to access the local and distributed data of declared acknowledgement labels.
 */
public interface DistributedAcks {

    /**
     * Receive a snapshot of local acknowledgement declarations on each update.
     * Subscription terminates when the receiver terminates.
     *
     * @param receiver receiver of local acknowledgement declarations.
     */
    void receiveLocalDeclaredAcks(final ActorRef receiver);

    /**
     * Receive a snapshot of the distributed data of acknowledgement label declarations on each change.
     * Subscription terminates when the receiver terminates.
     *
     * @param receiver receiver of distributed acknowledgement label declarations.
     */
    void receiveDistributedDeclaredAcks(final ActorRef receiver);


    /**
     * Remove a subscriber without waiting for acknowledgement.
     *
     * @param subscriber who is being removed.
     */
    void removeSubscriber(ActorRef subscriber);

    /**
     * Declare labels of acknowledgements that a subscriber may send.
     * Each subscriber's declared acknowledgment labels must be different from the labels declared by other subscribers.
     * Subscribers relinquish their declared labels when they terminate.
     *
     * @param acknowledgementLabels the acknowledgement labels to declare.
     * @param subscriber the subscriber.
     * @return a future SubAck if the declaration succeeded, or a failed future if it failed.
     */
    CompletionStage<AbstractUpdater.SubAck> declareAcknowledgementLabels(
            Collection<AcknowledgementLabel> acknowledgementLabels,
            ActorRef subscriber);

    /**
     * Remove the acknowledgement label declaration of a subscriber.
     *
     * @param subscriber the subscriber.
     */
    void removeAcknowledgementLabelDeclaration(ActorRef subscriber);

    /**
     * Start AcksSupervisor under an ActorContext and expose a DistributedAcks interface.
     * Precondition: the cluster member has the role {@code "acks-aware"}.
     *
     * @param context the actor context in which to start the AcksSupervisor.
     * @return the DistributedAcks interface.
     */
    static DistributedAcks create(final ActorContext context) {
        return DistributedAcksImpl.create(context);
    }

    /**
     * Create a dummy {@code DistributedAcks} interface not backed by a distributed data.
     * Useful for cluster members not participating in signal publication.
     *
     * @return an empty distributed acks.
     */
    static DistributedAcks empty() {
        return new DistributedAcksEmptyImpl();
    }
}
