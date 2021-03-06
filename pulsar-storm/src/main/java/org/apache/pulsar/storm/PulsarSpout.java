/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.storm;

import static java.lang.String.format;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import org.apache.pulsar.client.api.ClientBuilder;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.impl.Backoff;
import org.apache.pulsar.client.impl.ClientBuilderImpl;
import org.apache.pulsar.client.impl.conf.ClientConfigurationData;
import org.apache.pulsar.client.impl.conf.ConsumerConfigurationData;
import org.apache.storm.metric.api.IMetric;
import org.apache.storm.spout.SpoutOutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichSpout;
import org.apache.storm.tuple.Values;
import org.apache.storm.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PulsarSpout extends BaseRichSpout implements IMetric {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(PulsarSpout.class);

    public static final String NO_OF_PENDING_FAILED_MESSAGES = "numberOfPendingFailedMessages";
    public static final String NO_OF_MESSAGES_RECEIVED = "numberOfMessagesReceived";
    public static final String NO_OF_MESSAGES_EMITTED = "numberOfMessagesEmitted";
    public static final String NO_OF_PENDING_ACKS = "numberOfPendingAcks";
    public static final String CONSUMER_RATE = "consumerRate";
    public static final String CONSUMER_THROUGHPUT_BYTES = "consumerThroughput";

    private final ClientConfigurationData clientConf;
    private final ConsumerConfigurationData<byte[]> consumerConf;
    private final PulsarSpoutConfiguration pulsarSpoutConf;
    private final long failedRetriesTimeoutNano;
    private final int maxFailedRetries;
    private final ConcurrentMap<MessageId, MessageRetries> pendingMessageRetries = new ConcurrentHashMap<>();
    private final Queue<Message<byte[]>> failedMessages = new ConcurrentLinkedQueue<>();
    private final ConcurrentMap<String, Object> metricsMap = new ConcurrentHashMap<>();

    private SharedPulsarClient sharedPulsarClient;
    private String componentId;
    private String spoutId;
    private SpoutOutputCollector collector;
    private Consumer<byte[]> consumer;
    private volatile long messagesReceived = 0;
    private volatile long messagesEmitted = 0;
    private volatile long pendingAcks = 0;
    private volatile long messageSizeReceived = 0;

    public PulsarSpout(PulsarSpoutConfiguration pulsarSpoutConf, ClientBuilder clientBuilder) {
        Objects.requireNonNull(pulsarSpoutConf.getServiceUrl());
        Objects.requireNonNull(pulsarSpoutConf.getTopic());
        Objects.requireNonNull(pulsarSpoutConf.getSubscriptionName());
        Objects.requireNonNull(pulsarSpoutConf.getMessageToValuesMapper());

        this.clientConf = ((ClientBuilderImpl) clientBuilder).getClientConfigurationData().clone();
        this.clientConf.setServiceUrl(pulsarSpoutConf.getServiceUrl());
        this.consumerConf = new ConsumerConfigurationData<>();
        this.consumerConf.setTopicNames(Collections.singleton(pulsarSpoutConf.getTopic()));
        this.consumerConf.setSubscriptionName(pulsarSpoutConf.getSubscriptionName());
        this.consumerConf.setSubscriptionType(pulsarSpoutConf.getSubscriptionType());

        this.pulsarSpoutConf = pulsarSpoutConf;
        this.failedRetriesTimeoutNano = pulsarSpoutConf.getFailedRetriesTimeout(TimeUnit.NANOSECONDS);
        this.maxFailedRetries = pulsarSpoutConf.getMaxFailedRetries();
    }

    @Override
    public void close() {
        try {
            LOG.info("[{}] Closing Pulsar consumer for topic {}", spoutId, pulsarSpoutConf.getTopic());
            if (!pulsarSpoutConf.isSharedConsumerEnabled() && consumer != null) {
                consumer.close();
            }
            if (sharedPulsarClient != null) {
                sharedPulsarClient.close();
            }
            pendingMessageRetries.clear();
            failedMessages.clear();
        } catch (PulsarClientException e) {
            LOG.error("[{}] Error closing Pulsar consumer for topic {}", spoutId, pulsarSpoutConf.getTopic(), e);
        }
    }

    @Override
    public void ack(Object msgId) {
        if (msgId instanceof Message) {
            Message<?> msg = (Message<?>) msgId;
            if (LOG.isDebugEnabled()) {
                LOG.debug("[{}] Received ack for message {}", spoutId, msg.getMessageId());
            }
            consumer.acknowledgeAsync(msg);
            pendingMessageRetries.remove(msg.getMessageId());
            --pendingAcks;
        }
    }

    @Override
    public void fail(Object msgId) {
        if (msgId instanceof Message) {
            @SuppressWarnings("unchecked")
            Message<byte[]> msg = (Message<byte[]>) msgId;
            MessageId id = msg.getMessageId();
            LOG.warn("[{}] Error processing message {}", spoutId, id);

            // Since the message processing failed, we put it in the failed messages queue if there are more retries
            // remaining for the message
            MessageRetries messageRetries = pendingMessageRetries.computeIfAbsent(id, (k) -> new MessageRetries());
            if ((failedRetriesTimeoutNano < 0
                    || (messageRetries.getTimeStamp() + failedRetriesTimeoutNano) > System.nanoTime())
                    && (maxFailedRetries < 0 || messageRetries.numRetries < maxFailedRetries)) {
                // since we can retry again, we increment retry count and put it in the queue
                LOG.info("[{}] Putting message {} in the retry queue", spoutId, id);
                messageRetries.incrementAndGet();
                pendingMessageRetries.putIfAbsent(id, messageRetries);
                failedMessages.add(msg);
                --pendingAcks;

            } else {
                LOG.warn("[{}] Number of retries limit reached, dropping the message {}", spoutId, id);
                ack(msg);
            }
        }

    }

    /**
     * Emits a tuple received from the Pulsar consumer unless there are any failed messages
     */
    @Override
    public void nextTuple() {
        emitNextAvailableTuple();
    }

    /**
     * It makes sure that it emits next available non-tuple to topology unless consumer queue doesn't have any message
     * available. It receives message from consumer queue and converts it to tuple and emits to topology. if the
     * converted tuple is null then it tries to receives next message and perform the same until it finds non-tuple to
     * emit.
     */
    public void emitNextAvailableTuple() {
        Message<byte[]> msg;

        // check if there are any failed messages to re-emit in the topology
        msg = failedMessages.peek();
        if (msg != null) {
            MessageRetries messageRetries = pendingMessageRetries.get(msg.getMessageId());
            if (Backoff.shouldBackoff(messageRetries.getTimeStamp(), TimeUnit.NANOSECONDS,
                    messageRetries.getNumRetries(), clientConf.getDefaultBackoffIntervalNanos(), 
                    clientConf.getMaxBackoffIntervalNanos())) {
                Utils.sleep(TimeUnit.NANOSECONDS.toMillis(clientConf.getDefaultBackoffIntervalNanos()));
            } else {
                // remove the message from the queue and emit to the topology, only if it should not be backedoff
                LOG.info("[{}] Retrying failed message {}", spoutId, msg.getMessageId());
                failedMessages.remove();
                mapToValueAndEmit(msg);
            }
            return;
        }

        // receive from consumer if no failed messages
        if (consumer != null) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("[{}] Receiving the next message from pulsar consumer to emit to the collector", spoutId);
            }
            try {
                boolean done = false;
                while (!done) {
                    msg = consumer.receive(100, TimeUnit.MILLISECONDS);
                    if (msg != null) {
                        ++messagesReceived;
                        messageSizeReceived += msg.getData().length;
                        done = mapToValueAndEmit(msg);
                    } else {
                        // queue is empty and nothing to emit
                        done = true;
                    }
                }
            } catch (PulsarClientException e) {
                LOG.error("[{}] Error receiving message from pulsar consumer", spoutId, e);
            }
        }
    }

    @Override
    @SuppressWarnings({ "rawtypes" })
    public void open(Map conf, TopologyContext context, SpoutOutputCollector collector) {
        this.componentId = context.getThisComponentId();
        this.spoutId = String.format("%s-%s", componentId, context.getThisTaskId());
        this.collector = collector;
        pendingMessageRetries.clear();
        failedMessages.clear();
        try {
            sharedPulsarClient = SharedPulsarClient.get(componentId, clientConf);
            if (pulsarSpoutConf.isSharedConsumerEnabled()) {
                consumer = sharedPulsarClient.getSharedConsumer(consumerConf);
            } else {
                try {
                    consumer = sharedPulsarClient.getClient().subscribeAsync(consumerConf).join();
                } catch (CompletionException e) {
                    throw (PulsarClientException) e.getCause();
                }
            }
            LOG.info("[{}] Created a pulsar consumer on topic {} to receive messages with subscription {}", spoutId,
                    pulsarSpoutConf.getTopic(), pulsarSpoutConf.getSubscriptionName());
        } catch (PulsarClientException e) {
            LOG.error("[{}] Error creating pulsar consumer on topic {}", spoutId, pulsarSpoutConf.getTopic(), e);
            throw new IllegalStateException(format("Failed to initialize consumer for %s-%s : %s",
                    pulsarSpoutConf.getTopic(), pulsarSpoutConf.getSubscriptionName(), e.getMessage()), e);
        }
        context.registerMetric(String.format("PulsarSpoutMetrics-%s-%s", componentId, context.getThisTaskIndex()), this,
                pulsarSpoutConf.getMetricsTimeIntervalInSecs());
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        pulsarSpoutConf.getMessageToValuesMapper().declareOutputFields(declarer);

    }

    private boolean mapToValueAndEmit(Message<byte[]> msg) {
        if (msg != null) {
            Values values = pulsarSpoutConf.getMessageToValuesMapper().toValues(msg);
            ++pendingAcks;
            if (values == null) {
                // since the mapper returned null, we can drop the message and ack it immediately
                if (LOG.isDebugEnabled()) {
                    LOG.debug("[{}] Dropping message {}", spoutId, msg.getMessageId());
                }
                ack(msg);
            } else {
                collector.emit(values, msg);
                ++messagesEmitted;
                if (LOG.isDebugEnabled()) {
                    LOG.debug("[{}] Emitted message {} to the collector", spoutId, msg.getMessageId());
                }
                return true;
            }
        }
        return false;
    }

    public class MessageRetries {
        private final long timestampInNano;
        private int numRetries;

        public MessageRetries() {
            this.timestampInNano = System.nanoTime();
            this.numRetries = 0;
        }

        public long getTimeStamp() {
            return timestampInNano;
        }

        public int incrementAndGet() {
            return ++numRetries;
        }

        public int getNumRetries() {
            return numRetries;
        }
    }

    /**
     * Helpers for metrics
     */

    @SuppressWarnings({ "rawtypes" })
    ConcurrentMap getMetrics() {
        metricsMap.put(NO_OF_PENDING_FAILED_MESSAGES, (long) pendingMessageRetries.size());
        metricsMap.put(NO_OF_MESSAGES_RECEIVED, messagesReceived);
        metricsMap.put(NO_OF_MESSAGES_EMITTED, messagesEmitted);
        metricsMap.put(NO_OF_PENDING_ACKS, pendingAcks);
        metricsMap.put(CONSUMER_RATE, ((double) messagesReceived) / pulsarSpoutConf.getMetricsTimeIntervalInSecs());
        metricsMap.put(CONSUMER_THROUGHPUT_BYTES,
                ((double) messageSizeReceived) / pulsarSpoutConf.getMetricsTimeIntervalInSecs());
        return metricsMap;
    }

    void resetMetrics() {
        messagesReceived = 0;
        messagesEmitted = 0;
        messageSizeReceived = 0;
    }

    @SuppressWarnings("rawtypes")
    @Override
    public Object getValueAndReset() {
        ConcurrentMap metrics = getMetrics();
        resetMetrics();
        return metrics;
    }
}
