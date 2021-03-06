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
package org.eclipse.ditto.services.utils.pubsub;

import static java.util.Arrays.asList;
import static java.util.Collections.singleton;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.awaitility.Awaitility;
import org.eclipse.ditto.model.base.acks.AcknowledgementLabel;
import org.eclipse.ditto.model.base.acks.AcknowledgementLabelNotUniqueException;
import org.eclipse.ditto.model.base.acks.AcknowledgementRequest;
import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.model.things.ThingId;
import org.eclipse.ditto.services.utils.pubsub.actors.AbstractUpdater;
import org.eclipse.ditto.services.utils.pubsub.actors.AcksUpdater;
import org.eclipse.ditto.services.utils.pubsub.actors.SubUpdater;
import org.eclipse.ditto.services.utils.pubsub.extractors.AckExtractor;
import org.eclipse.ditto.signals.acks.base.Acknowledgements;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import akka.actor.AbstractActor;
import akka.actor.ActorContext;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.actor.Terminated;
import akka.cluster.Cluster;
import akka.cluster.ClusterEvent;
import akka.japi.pf.ReceiveBuilder;
import akka.stream.Attributes;
import akka.testkit.TestActor;
import akka.testkit.TestActorRef;
import akka.testkit.TestProbe;
import akka.testkit.javadsl.TestKit;
import scala.concurrent.duration.Duration;

/**
 * Tests Ditto pub-sub as a whole.
 */
public final class PubSubFactoryTest {

    private ActorSystem system1;
    private ActorSystem system2;
    private Cluster cluster1;
    private Cluster cluster2;
    private TestPubSubFactory factory1;
    private TestPubSubFactory factory2;
    private DistributedAcks distributedAcks1;
    private DistributedAcks distributedAcks2;
    private AckExtractor<String> ackExtractor;
    private Map<String, ThingId> thingIdMap;
    private Map<String, DittoHeaders> dittoHeadersMap;

    private Config getTestConf() {
        return ConfigFactory.load("pubsub-factory-test.conf");
    }

    @Before
    public void setUpCluster() throws Exception {
        final CountDownLatch latch = new CountDownLatch(2);
        system1 = ActorSystem.create("actorSystem", getTestConf());
        system2 = ActorSystem.create("actorSystem", getTestConf());
        cluster1 = Cluster.get(system1);
        cluster2 = Cluster.get(system2);
        cluster1.registerOnMemberUp(latch::countDown);
        cluster2.registerOnMemberUp(latch::countDown);
        cluster1.join(cluster1.selfAddress());
        cluster2.join(cluster1.selfAddress());
        final ActorContext context1 = newContext(system1);
        final ActorContext context2 = newContext(system2);
        distributedAcks1 = TestPubSubFactory.startDistributedAcks(context1);
        distributedAcks2 = TestPubSubFactory.startDistributedAcks(context2);
        thingIdMap = new ConcurrentHashMap<>();
        dittoHeadersMap = new ConcurrentHashMap<>();
        ackExtractor = AckExtractor.of(
                s -> thingIdMap.getOrDefault(s, ThingId.dummy()),
                s -> dittoHeadersMap.getOrDefault(s, DittoHeaders.empty())
        );
        factory1 = TestPubSubFactory.of(context1, ackExtractor, distributedAcks1);
        factory2 = TestPubSubFactory.of(context2, ackExtractor, distributedAcks2);
        // wait for both members to be UP
        latch.await();
    }

    @After
    public void shutdownCluster() {
        disableLogging();
        TestKit.shutdownActorSystem(system1);
        TestKit.shutdownActorSystem(system2);
    }

    @Test
    public void subscribeAndPublishAndUnsubscribe() {
        new TestKit(system2) {{
            final DistributedPub<String> pub = factory1.startDistributedPub();
            final DistributedSub sub = factory2.startDistributedSub();
            final TestProbe publisher = TestProbe.apply(system1);
            final TestProbe subscriber = TestProbe.apply(system2);

            // WHEN: actor subscribes to a topic with acknowledgement
            final AbstractUpdater.SubAck subAck =
                    sub.subscribeWithAck(singleton("hello"), subscriber.ref()).toCompletableFuture().join();

            // THEN: subscription is acknowledged
            assertThat(subAck.getRequest()).isInstanceOf(SubUpdater.Subscribe.class);
            assertThat(subAck.getRequest().getTopics()).containsExactlyInAnyOrder("hello");

            // WHEN: a message is published on the subscribed topic
            pub.publish("hello", publisher.ref());

            // THEN: the subscriber receives it from the original sender's address
            subscriber.expectMsg("hello");
            assertThat(subscriber.sender().path().address()).isEqualTo(cluster1.selfAddress());
            assertThat(subscriber.sender().path().toStringWithoutAddress())
                    .isEqualTo(publisher.ref().path().toStringWithoutAddress());

            // WHEN: subscription is relinquished
            final AbstractUpdater.SubAck unsubAck =
                    sub.unsubscribeWithAck(asList("hello", "world"), subscriber.ref()).toCompletableFuture().join();
            assertThat(unsubAck.getRequest()).isInstanceOf(SubUpdater.Unsubscribe.class);
            assertThat(unsubAck.getRequest().getTopics()).containsExactlyInAnyOrder("hello", "world");

            // THEN: the subscriber does not receive published messages any more
            pub.publish("hello", publisher.ref());
            pub.publish("hello world", publisher.ref());
            subscriber.expectNoMessage();
        }};
    }

    @Test
    public void broadcastMessageToManySubscribers() {
        new TestKit(system2) {{
            final DistributedPub<String> pub = factory1.startDistributedPub();
            final DistributedSub sub1 = factory1.startDistributedSub();
            final DistributedSub sub2 = factory2.startDistributedSub();
            final TestProbe publisher = TestProbe.apply(system1);
            final TestProbe subscriber1 = TestProbe.apply(system1);
            final TestProbe subscriber2 = TestProbe.apply(system2);
            final TestProbe subscriber3 = TestProbe.apply(system1);
            final TestProbe subscriber4 = TestProbe.apply(system2);

            // GIVEN: subscribers of different topics exist on both actor systems
            await(sub1.subscribeWithAck(asList("he", "av'n", "has", "no", "rage", "nor"), subscriber1.ref()));
            await(sub2.subscribeWithAck(asList("hell", "a", "fury"), subscriber2.ref()));
            await(sub1.subscribeWithAck(asList("like", "a", "woman", "scorn'd"), subscriber3.ref()));
            await(sub2.subscribeWithAck(asList("exeunt", "omnes"), subscriber4.ref()).toCompletableFuture());

            // WHEN: many messages are published
            final int messages = 100;
            IntStream.range(0, messages).forEach(i -> pub.publish("hello" + i, publisher.ref()));

            // THEN: subscribers with relevant topics get the messages in the order they were published.
            IntStream.range(0, messages).forEach(i -> {
                subscriber1.expectMsg("hello" + i);
                subscriber2.expectMsg("hello" + i);
            });

            // THEN: subscribers without relevant topics get no message.
            subscriber3.expectNoMsg(Duration.Zero());
            subscriber4.expectNoMsg(Duration.Zero());
        }};
    }

    @Test
    public void watchForLocalActorTermination() {
        new TestKit(system2) {{
            final DistributedPub<String> pub = factory1.startDistributedPub();
            final DistributedSub sub = factory2.startDistributedSub();
            final TestProbe publisher = TestProbe.apply(system1);
            final TestProbe subscriber = TestProbe.apply(system2);
            watch(subscriber.ref());

            // GIVEN: a pub-sub channel is set up
            sub.subscribeWithAck(singleton("hello"), subscriber.ref()).toCompletableFuture().join();
            pub.publish("hello", publisher.ref());
            subscriber.expectMsg("hello");

            // WHEN: subscriber terminates
            system2.stop(subscriber.ref());
            expectMsgClass(Terminated.class);

            // THEN: the subscriber is removed
            Awaitility.await().untilAsserted(() ->
                    assertThat(factory1.getSubscribers("hello").toCompletableFuture().join())
                            .describedAs("subscriber should be removed from ddata after termination")
                            .isEmpty()
            );
        }};
    }

    // Can't test recovery after disassociation---no actor system can join a cluster twice.
    @Test
    public void removeSubscriberOfRemovedClusterMember() {
        disableLogging();
        new TestKit(system1) {{
            final DistributedPub<String> pub = factory1.startDistributedPub();
            final DistributedSub sub = factory2.startDistributedSub();
            final TestProbe publisher = TestProbe.apply(system1);
            final TestProbe subscriber = TestProbe.apply(system2);
            cluster1.subscribe(getRef(), ClusterEvent.MemberRemoved.class);
            expectMsgClass(ClusterEvent.CurrentClusterState.class);

            // GIVEN: a pub-sub channel is set up
            sub.subscribeWithAck(singleton("hello"), subscriber.ref()).toCompletableFuture().join();
            pub.publish("hello", publisher.ref());
            subscriber.expectMsg("hello");

            // WHEN: remote actor system is removed from cluster
            cluster2.leave(cluster2.selfAddress());
            expectMsgClass(java.time.Duration.ofSeconds(10L), ClusterEvent.MemberRemoved.class);

            // THEN: the subscriber is removed
            Awaitility.await().untilAsserted(() ->
                    assertThat(factory1.getSubscribers("hello").toCompletableFuture().join())
                            .describedAs("subscriber should be removed from ddata")
                            .isEmpty());
        }};
    }

    @Test
    public void startSeveralTimes() {
        // This test simulates the situation where the root actor of a Ditto service restarts several times.
        new TestKit(system2) {{
            // GIVEN: many pub- and sub-factories start under different actors.
            for (int i = 0; i < 10; ++i) {
                TestPubSubFactory.of(newContext(system1), ackExtractor, distributedAcks1);
                TestPubSubFactory.of(newContext(system2), ackExtractor, distributedAcks2);
            }

            // WHEN: another pair of pub-sub factories were created.
            final DistributedPub<String> pub =
                    TestPubSubFactory.of(newContext(system1), ackExtractor, distributedAcks1).startDistributedPub();
            final DistributedSub sub =
                    TestPubSubFactory.of(newContext(system2), ackExtractor, distributedAcks2).startDistributedSub();
            final TestProbe publisher = TestProbe.apply(system1);
            final TestProbe subscriber = TestProbe.apply(system2);

            // THEN: they fulfill their function.
            final AbstractUpdater.SubAck subAck =
                    sub.subscribeWithAck(singleton("hello"), subscriber.ref()).toCompletableFuture().join();
            assertThat(subAck.getRequest()).isInstanceOf(SubUpdater.Subscribe.class);
            assertThat(subAck.getRequest().getTopics()).containsExactlyInAnyOrder("hello");

            pub.publish("hello", publisher.ref());
            subscriber.expectMsg(Duration.create(5, TimeUnit.SECONDS), "hello");
        }};
    }

    @Test
    public void failAckDeclarationDueToLocalConflict() {
        new TestKit(system1) {{
            // GIVEN: 2 subscribers exist in the same actor system
            final TestProbe subscriber1 = TestProbe.apply(system1);
            final TestProbe subscriber2 = TestProbe.apply(system1);

            // WHEN: the first subscriber declares ack labels
            // THEN: the declaration should succeed
            await(factory1.getDistributedAcks()
                    .declareAcknowledgementLabels(acks("lorem", "ipsum"), subscriber1.ref()));

            // WHEN: the second subscriber declares intersecting labels
            // THEN: the declaration should fail
            final CompletionStage<?> declareAckLabelFuture =
                    factory1.getDistributedAcks()
                            .declareAcknowledgementLabels(acks("ipsum", "lorem"), subscriber2.ref());
            assertThat(awaitSilently(system1, declareAckLabelFuture))
                    .hasFailedWithThrowableThat()
                    .isInstanceOf(AcknowledgementLabelNotUniqueException.class);
        }};
    }

    @Test
    public void removeAcknowledgementLabelDeclaration() {
        new TestKit(system1) {{
            // GIVEN: 2 subscribers exist in the same actor system
            final TestProbe subscriber1 = TestProbe.apply(system1);
            final TestProbe subscriber2 = TestProbe.apply(system1);

            // WHEN: the first subscriber declares ack labels then relinquishes them
            await(factory1.getDistributedAcks()
                    .declareAcknowledgementLabels(acks("lorem", "ipsum"), subscriber1.ref()));
            factory1.getDistributedAcks().removeAcknowledgementLabelDeclaration(subscriber1.ref());

            // THEN: another subscriber should be able to claim the ack labels right away
            await(factory1.getDistributedAcks()
                    .declareAcknowledgementLabels(acks("ipsum", "lorem"), subscriber2.ref()));
        }};
    }

    @Test
    public void receiveLocalDeclaredAcks() {
        new TestKit(system1) {{
            final TestProbe subscriber1 = TestProbe.apply("subscriber1", system1);
            await(factory1.getDistributedAcks()
                    .declareAcknowledgementLabels(acks("lorem"), subscriber1.ref()));
            factory1.getDistributedAcks().receiveLocalDeclaredAcks(getRef());
            final AcksUpdater.SubscriptionsChanged subscriptionsChanged =
                    expectMsgClass(java.time.Duration.ofSeconds(10L), AcksUpdater.SubscriptionsChanged.class);
            assertThat(subscriptionsChanged.getSubscriptionsReader().getSubscribers(List.of("lorem")))
                    .contains(subscriber1.ref());
        }};
    }

    @Test
    public void publisherSendsWeakAckForDeclaredAndUnauthorizedLabels() {
        new TestKit(system1) {{
            final TestProbe publisher = TestProbe.apply("publisher", system1);
            final TestProbe subscriber = TestProbe.apply("subscriber", system2);

            final DistributedPub<String> pub = factory1.startDistributedPub();
            final DistributedSub sub = factory2.startDistributedSub();

            // GIVEN: subscriber declares the requested acknowledgement
            await(factory2.getDistributedAcks().declareAcknowledgementLabels(acks("ack"), subscriber.ref()));
            await(sub.subscribeWithAck(List.of("subscriber-topic"), subscriber.ref()));

            // ensure ddata is replicated to publisher
            waitForHeartBeats(system2, factory2);

            // WHEN: message with the subscriber's declared ack and a different topic is published
            final String publisherTopic = "publisher-topic";
            thingIdMap.put(publisherTopic, ThingId.of("thing:id"));
            dittoHeadersMap.put(publisherTopic,
                    DittoHeaders.newBuilder().acknowledgementRequest(
                            AcknowledgementRequest.parseAcknowledgementRequest("ack"),
                            AcknowledgementRequest.parseAcknowledgementRequest("no-declaration")
                    ).build()
            );
            pub.publishWithAcks(publisherTopic, ackExtractor, publisher.ref());

            // THEN: the publisher receives a weak acknowledgement for the ack request with a declared label
            final Acknowledgements weakAcks = publisher.expectMsgClass(Acknowledgements.class);
            assertThat(weakAcks.getAcknowledgement(AcknowledgementLabel.of("ack")))
                    .isNotEmpty()
                    .satisfies(optional -> assertThat(optional.orElseThrow().isWeak())
                            .describedAs("Should be weak ack: " + optional.orElseThrow())
                            .isTrue());

            // THEN: the publisher does not receive a weak acknowledgement for the ack request without a declared label
            assertThat(weakAcks.getAcknowledgement(AcknowledgementLabel.of("no-declaration"))).isEmpty();
        }};
    }

    @Test
    public void subscriberSendsWeakAckToDeclaredAndUnauthorizedLabels() {
        new TestKit(system1) {{
            final TestProbe publisher = TestProbe.apply("publisher", system1);
            final TestProbe subscriber1 = TestProbe.apply("subscriber1", system2);
            final TestProbe subscriber2 = TestProbe.apply("subscriber2", system2);

            final DistributedPub<String> pub = factory1.startDistributedPub();
            final DistributedSub sub = factory2.startDistributedSub();

            // GIVEN: different subscribers declare the requested acknowledgement and subscribe for the publisher topic
            final String publisherTopic = "publisher-topic";
            await(factory2.getDistributedAcks().declareAcknowledgementLabels(acks("ack"), subscriber1.ref()));
            await(sub.subscribeWithAck(List.of(publisherTopic), subscriber2.ref()));

            // ensure ddata is replicated to publisher
            waitForHeartBeats(system2, factory2);

            // WHEN: message with the subscriber's declared ack and a different topic is published
            final ThingId thingId = ThingId.of("thing:id");
            final DittoHeaders dittoHeaders = DittoHeaders.newBuilder().acknowledgementRequest(
                    AcknowledgementRequest.parseAcknowledgementRequest("ack"),
                    AcknowledgementRequest.parseAcknowledgementRequest("no-declaration")
            ).build();
            thingIdMap.put(publisherTopic, thingId);
            dittoHeadersMap.put(publisherTopic, dittoHeaders);
            pub.publishWithAcks(publisherTopic, ackExtractor, publisher.ref());

            // THEN: the publisher receives a weak acknowledgement for the ack request with a declared label
            final Acknowledgements weakAcks = publisher.expectMsgClass(Acknowledgements.class);
            assertThat(weakAcks.getAcknowledgement(AcknowledgementLabel.of("ack")))
                    .isNotEmpty()
                    .satisfies(optional -> assertThat(optional.orElseThrow().isWeak())
                            .describedAs("Should be weak ack: " + optional.orElseThrow())
                            .isTrue());

            // THEN: the publisher does not receive a weak acknowledgement for the ack request without a declared label
            assertThat(weakAcks.getAcknowledgement(AcknowledgementLabel.of("no-declaration"))).isEmpty();
        }};
    }

    @Test
    public void failAckDeclarationDueToRemoteConflict() {
        new TestKit(system1) {{
            // GIVEN: 2 subscribers exist in different actor systems
            final TestProbe subscriber1 = TestProbe.apply("subscriber1", system1);
            final TestProbe subscriber2 = TestProbe.apply("subscriber2", system2);

            // GIVEN: "sit" is declared by a subscriber on system2
            await(factory2.getDistributedAcks().declareAcknowledgementLabels(acks("dolor", "sit"),
                    subscriber2.ref()));

            // GIVEN: the update is replicated to system1
            waitForHeartBeats(system2, factory2);

            // WHEN: another subscriber from system1 declares conflicting labels with the subscriber from system2
            // THEN: the declaration should fail
            final CompletionStage<?> declareAckLabelFuture =
                    factory1.getDistributedAcks()
                            .declareAcknowledgementLabels(acks("sit", "amet"), subscriber1.ref());
            assertThat(awaitSilently(system1, declareAckLabelFuture))
                    .hasFailedWithThrowableThat()
                    .isInstanceOf(AcknowledgementLabelNotUniqueException.class);
        }};
    }

    @Test
    public void raceAgainstLocalAckLabelDeclaration() {
        new TestKit(system1) {{
            // comment-out the next line to get future failure logs
            disableLogging();
            // repeat the test to catch timing issues
            for (int i = 0; i < 10; ++i) {
                // GIVEN: 2 subscribers exist in the same actor system
                final TestProbe subscriber1 = TestProbe.apply("subscriber1", system1);
                final TestProbe subscriber2 = TestProbe.apply("subscriber2", system1);

                // WHEN: 2 subscribers declare intersecting labels simultaneously
                final CompletionStage<?> future1 =
                        factory1.getDistributedAcks()
                                .declareAcknowledgementLabels(acks("lorem" + i, "ipsum" + i), subscriber1.ref());
                final CompletionStage<?> future2 =
                        factory1.getDistributedAcks()
                                .declareAcknowledgementLabels(acks("ipsum" + i, "dolor" + i), subscriber2.ref());

                // THEN: exactly one of them fails
                await(future1.handle((result1, error1) -> await(future2.handle((result2, error2) -> {
                    if (error1 == null) {
                        assertThat(error2).isNotNull();
                    } else {
                        assertThat(error2).isNull();
                    }
                    return null;
                }))));
            }
        }};
    }

    @Test
    public void raceAgainstRemoteAckLabelDeclaration() {
        new TestKit(system1) {{
            // comment-out the next line to get future failure logs
            disableLogging();

            // run the test many times to catch timing issues
            for (int i = 0; i < 10; ++i) {
                final TestProbe eitherSubscriberProbe = TestProbe.apply(system1);
                final TestActor.AutoPilot autoPilot = new TestActor.AutoPilot() {

                    @Override
                    public TestActor.AutoPilot run(final ActorRef sender, final Object msg) {
                        eitherSubscriberProbe.ref().tell(msg, sender);
                        return this;
                    }
                };

                // GIVEN: 2 subscribers exist in different actor systems
                final TestProbe subscriber1 = TestProbe.apply("subscriber1", system1);
                final TestProbe subscriber2 = TestProbe.apply("subscriber2", system2);
                subscriber1.setAutoPilot(autoPilot);
                subscriber2.setAutoPilot(autoPilot);

                // WHEN: 2 subscribers declare intersecting labels simultaneously
                final CompletionStage<?> future1 =
                        factory1.getDistributedAcks()
                                .declareAcknowledgementLabels(acks("lorem" + i, "ipsum" + i), subscriber1.ref());
                final CompletionStage<?> future2 =
                        factory2.getDistributedAcks()
                                .declareAcknowledgementLabels(acks("ipsum" + i, "dolor" + i), subscriber2.ref());

                // THEN: exactly one of them fails, or both succeeds and one subscriber gets an exception later.
                await(future1.handle((result1, error1) -> await(future2.handle((result2, error2) -> {
                    if (error1 == null && error2 == null) {
                        eitherSubscriberProbe.expectMsgClass(AcknowledgementLabelNotUniqueException.class);
                    } else if (error1 != null) {
                        assertThat(error2).isNull();
                    }
                    return null;
                }))));
            }
        }};
    }

    private void disableLogging() {
        system1.eventStream().setLogLevel(Attributes.logLevelOff());
        system2.eventStream().setLogLevel(Attributes.logLevelOff());
    }

    private static ActorContext newContext(final ActorSystem actorSystem) {
        return TestActorRef.create(actorSystem, Props.create(NopActor.class)).underlyingActor().context();
    }

    private static Set<AcknowledgementLabel> acks(final String... labels) {
        return Arrays.stream(labels).map(AcknowledgementLabel::of).collect(Collectors.toSet());
    }

    private static <T> CompletionStage<T> await(final CompletionStage<T> stage) {
        final CompletableFuture<Object> future = stage.toCompletableFuture().thenApply(x -> x);
        future.completeOnTimeout(new TimeoutException(), 30L, TimeUnit.SECONDS);
        future.thenCompose(x -> {
            if (x instanceof Throwable) {
                return CompletableFuture.failedStage((Throwable) x);
            } else {
                return CompletableFuture.completedStage(x);
            }
        }).join();
        return stage;
    }

    private static <T> CompletionStage<T> awaitSilently(final ActorSystem system, final CompletionStage<T> stage) {
        try {
            return await(stage);
        } catch (final Throwable e) {
            system.log().info("Future failed: {}", e);
        }
        return stage;
    }

    private static final class NopActor extends AbstractActor {

        @Override
        public Receive createReceive() {
            return ReceiveBuilder.create().build();
        }
    }

    private static void waitForHeartBeats(final ActorSystem system, final TestPubSubFactory factory) {
        final int howManyHeartBeats = 5;
        final TestProbe probe = TestProbe.apply(system);
        factory.getDistributedAcks().receiveLocalDeclaredAcks(probe.ref());
        for (int i = 0; i < howManyHeartBeats; ++i) {
            probe.expectMsgClass(AcksUpdater.SubscriptionsChanged.class);
        }
        system.stop(probe.ref());
    }
}
