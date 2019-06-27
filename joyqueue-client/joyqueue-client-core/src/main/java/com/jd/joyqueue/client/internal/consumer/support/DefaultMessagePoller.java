/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jd.joyqueue.client.internal.consumer.support;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.jd.joyqueue.client.internal.cluster.ClusterClientManager;
import com.jd.joyqueue.client.internal.cluster.ClusterManager;
import com.jd.joyqueue.client.internal.consumer.BrokerLoadBalance;
import com.jd.joyqueue.client.internal.consumer.ConsumerIndexManager;
import com.jd.joyqueue.client.internal.consumer.MessageFetcher;
import com.jd.joyqueue.client.internal.consumer.MessagePoller;
import com.jd.joyqueue.client.internal.consumer.callback.ConsumerListener;
import com.jd.joyqueue.client.internal.consumer.config.ConsumerConfig;
import com.jd.joyqueue.client.internal.consumer.config.FetcherConfig;
import com.jd.joyqueue.client.internal.consumer.coordinator.ConsumerCoordinator;
import com.jd.joyqueue.client.internal.consumer.coordinator.domain.BrokerAssignment;
import com.jd.joyqueue.client.internal.consumer.coordinator.domain.BrokerAssignments;
import com.jd.joyqueue.client.internal.consumer.coordinator.domain.BrokerAssignmentsHolder;
import com.jd.joyqueue.client.internal.consumer.domain.ConsumeMessage;
import com.jd.joyqueue.client.internal.consumer.domain.ConsumeReply;
import com.jd.joyqueue.client.internal.consumer.exception.ConsumerException;
import com.jd.joyqueue.client.internal.consumer.transport.ConsumerClientManager;
import com.jd.joyqueue.client.internal.metadata.domain.PartitionMetadata;
import com.jd.joyqueue.client.internal.metadata.domain.TopicMetadata;
import com.jd.joyqueue.client.internal.nameserver.NameServerConfig;
import com.jd.joyqueue.exception.JoyQueueCode;
import com.jd.joyqueue.toolkit.service.Service;
import com.jd.joyqueue.toolkit.time.SystemClock;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * DefaultMessagePoller
 * author: gaohaoxiang
 * email: gaohaoxiang@jd.com
 * date: 2018/12/11
 */
public class DefaultMessagePoller extends Service implements MessagePoller {

    private static final int CUSTOM_BATCH_SIZE = -1;

    protected static final Logger logger = LoggerFactory.getLogger(DefaultMessagePoller.class);

    private ConsumerConfig config;
    private NameServerConfig nameServerConfig;
    private ClusterManager clusterManager;
    private ClusterClientManager clusterClientManager;
    private ConsumerClientManager consumerClientManager;

    private ConsumerCoordinator consumerCoordinator;
    private FetcherConfig fetcherConfig;
    private MessageFetcher messageFetcher;
    private ConsumerIndexManager consumerIndexManager;
    private MessagePollerInner messagePollerInner;
    private BrokerAssignmentsHolder brokerAssignmentCache;

    public DefaultMessagePoller(ConsumerConfig config, NameServerConfig nameServerConfig, ClusterManager clusterManager,
                                ClusterClientManager clusterClientManager, ConsumerClientManager consumerClientManager) {
        Preconditions.checkArgument(config != null, "consumer can not be null");
        Preconditions.checkArgument(nameServerConfig != null, "nameServer can not be null");
        Preconditions.checkArgument(clusterManager != null, "clusterManager can not be null");
        Preconditions.checkArgument(clusterClientManager != null, "clusterClientManager can not be null");
        Preconditions.checkArgument(consumerClientManager != null, "consumerClientManager can not be null");
        Preconditions.checkArgument(StringUtils.isNotBlank(config.getApp()), "consumer.app not blank");
        Preconditions.checkArgument(config.getPollTimeout() > config.getLongPollTimeout(), "consumer.pollTimeout must be greater than consumer.longPullTimeout");

        this.config = config;
        this.nameServerConfig = nameServerConfig;
        this.clusterManager = clusterManager;
        this.clusterClientManager = clusterClientManager;
        this.consumerClientManager = consumerClientManager;
    }

    @Override
    protected void validate() throws Exception {
        consumerCoordinator = new ConsumerCoordinator(clusterClientManager);
        fetcherConfig = new FetcherConfig();
        messageFetcher = new DefaultMessageFetcher(consumerClientManager, fetcherConfig);
        consumerIndexManager = new DefaultConsumerIndexManager(clusterManager, consumerClientManager);
        messagePollerInner = new MessagePollerInner(config, nameServerConfig, clusterManager, consumerClientManager, messageFetcher);
    }

    @Override
    protected void doStart() throws Exception {
        messageFetcher.start();
        consumerCoordinator.start();
        messagePollerInner.start();
    }

    @Override
    protected void doStop() {
        if (messagePollerInner != null) {
            messagePollerInner.stop();
        }
        if (consumerCoordinator != null) {
            consumerCoordinator.stop();
        }
        if (messageFetcher != null) {
            messageFetcher.stop();
        }
    }

    @Override
    public ConsumeMessage pollOnce(String topic) {
        return pollOnce(topic, config.getPollTimeout(), TimeUnit.MILLISECONDS);
    }

    @Override
    public ConsumeMessage pollOnce(String topic, long timeout, TimeUnit timeoutUnit) {
        List<ConsumeMessage> consumeMessages = doPoll(topic, 1, timeout, timeoutUnit, null);
        if (CollectionUtils.isEmpty(consumeMessages)) {
            return null;
        }
        return consumeMessages.get(0);
    }

    @Override
    public List<ConsumeMessage> poll(String topic) {
        return poll(topic, config.getPollTimeout(), TimeUnit.MILLISECONDS);
    }

    @Override
    public List<ConsumeMessage> poll(String topic, long timeout, TimeUnit timeoutUnit) {
        return doPoll(topic, CUSTOM_BATCH_SIZE, timeout, timeoutUnit, null);
    }

    @Override
    public void pollAsync(String topic, ConsumerListener listener) {
        Preconditions.checkArgument(listener != null, "listener not null");
        pollAsync(topic, config.getPollTimeout(), TimeUnit.MILLISECONDS, listener);
    }

    @Override
    public void pollAsync(String topic, long timeout, TimeUnit timeoutUnit, ConsumerListener listener) {
        Preconditions.checkArgument(listener != null, "listener not null");
        doPoll(topic, CUSTOM_BATCH_SIZE, timeout, timeoutUnit, listener);
    }

    @Override
    public ConsumeMessage pollPartitionOnce(String topic, short partition) {
        return pollPartitionOnce(topic, partition, config.getPollTimeout(), TimeUnit.MILLISECONDS);
    }

    @Override
    public ConsumeMessage pollPartitionOnce(String topic, short partition, long timeout, TimeUnit timeoutUnit) {
        List<ConsumeMessage> consumeMessages = doPollPartition(topic, partition, 1, timeout, timeoutUnit, null);
        if (CollectionUtils.isEmpty(consumeMessages)) {
            return null;
        }
        return consumeMessages.get(0);
    }

    @Override
    public ConsumeMessage pollPartitionOnce(String topic, short partition, long index) {
        return pollPartitionOnce(topic, partition, index, config.getPollTimeout(), TimeUnit.MILLISECONDS);
    }

    @Override
    public ConsumeMessage pollPartitionOnce(String topic, short partition, long index, long timeout, TimeUnit timeoutUnit) {
        List<ConsumeMessage> consumeMessages = doPollPartition(topic, partition, index, 1, config.getPollTimeout(), TimeUnit.MILLISECONDS, null);
        if (CollectionUtils.isEmpty(consumeMessages)) {
            return null;
        }
        return consumeMessages.get(0);
    }

    @Override
    public List<ConsumeMessage> pollPartition(String topic, short partition) {
        return pollPartition(topic, partition, config.getPollTimeout(), TimeUnit.MILLISECONDS);
    }

    @Override
    public List<ConsumeMessage> pollPartition(String topic, short partition, long timeout, TimeUnit timeoutUnit) {
        return doPollPartition(topic, partition, CUSTOM_BATCH_SIZE, timeout, timeoutUnit, null);
    }

    @Override
    public List<ConsumeMessage> pollPartition(String topic, short partition, long index) {
        return pollPartition(topic, partition, index, config.getPollTimeout(), TimeUnit.MILLISECONDS);
    }

    @Override
    public List<ConsumeMessage> pollPartition(String topic, short partition, long index, long timeout, TimeUnit timeoutUnit) {
        return doPollPartition(topic, partition, index, CUSTOM_BATCH_SIZE, config.getPollTimeout(), TimeUnit.MILLISECONDS, null);
    }

    @Override
    public void pollPartitionAsync(String topic, short partition, ConsumerListener listener) {
        pollPartitionAsync(topic, partition, config.getPollTimeout(), TimeUnit.MILLISECONDS, listener);
    }

    @Override
    public void pollPartitionAsync(String topic, short partition, long timeout, TimeUnit timeoutUnit, ConsumerListener listener) {
        Preconditions.checkArgument(listener != null, "listener not null");
        doPollPartition(topic, partition, CUSTOM_BATCH_SIZE, timeout, timeoutUnit, listener);
    }

    @Override
    public void pollPartitionAsync(String topic, short partition, long index, ConsumerListener listener) {
        pollPartitionAsync(topic, partition, index, config.getPollTimeout(), TimeUnit.MILLISECONDS, listener);
    }

    @Override
    public void pollPartitionAsync(String topic, short partition, long index, long timeout, TimeUnit timeoutUnit, ConsumerListener listener) {
        Preconditions.checkArgument(listener != null, "listener not null");
        doPollPartition(topic, partition, index, CUSTOM_BATCH_SIZE, timeout, timeoutUnit, listener);
    }

    protected List<ConsumeMessage> doPollPartition(String topic, short partition, int batchSize, long timeout, TimeUnit timeoutUnit, ConsumerListener listener) {
        return doPollPartition(topic, partition, MessagePollerInner.FETCH_PARTITION_NONE_INDEX, batchSize, timeout, timeoutUnit, listener);
    }

    protected List<ConsumeMessage> doPollPartition(String topic, short partition, long index, int batchSize, long timeout, TimeUnit timeoutUnit, ConsumerListener listener) {
        checkState();
        Preconditions.checkArgument(StringUtils.isNotBlank(topic), "topic not blank");
        Preconditions.checkArgument(timeoutUnit != null, "timeoutUnit not null");

        TopicMetadata topicMetadata = messagePollerInner.getAndCheckTopicMetadata(topic);
        PartitionMetadata partitionMetadata = topicMetadata.getPartition(partition);

        if (partitionMetadata == null) {
            throw new ConsumerException(String.format("partition not exist, topic: %s, partition: %s", topic, partition), JoyQueueCode.FW_TOPIC_NO_PARTITIONGROUP.getCode());
        }

        if (partitionMetadata.getLeader() == null) {
            throw new ConsumerException(String.format("partition not available, topic: %s, partition: %s", topic, partition), JoyQueueCode.FW_TOPIC_NO_PARTITIONGROUP.getCode());
        }

        if (!partitionMetadata.getLeader().isReadable()) {
            throw new ConsumerException(String.format("partition no permission, topic: %s, partition: %s", topic, partition), JoyQueueCode.FW_TOPIC_NO_PARTITIONGROUP.getCode());
        }

        if (batchSize == CUSTOM_BATCH_SIZE) {
            batchSize = (config.getBatchSize() == ConsumerConfig.NONE_BATCH_SIZE ? topicMetadata.getConsumerPolicy().getBatchSize() : config.getBatchSize());
        }
        return messagePollerInner.fetchPartition(partitionMetadata.getLeader(), topicMetadata, partition, index, batchSize, timeout, timeoutUnit, listener);
    }

    protected List<ConsumeMessage> doPoll(String topic, int batchSize, long timeout, TimeUnit timeoutUnit, ConsumerListener listener) {
        checkState();
        Preconditions.checkArgument(StringUtils.isNotBlank(topic), "topic not blank");
        Preconditions.checkArgument(timeoutUnit != null, "timeoutUnit not null");

        TopicMetadata topicMetadata = messagePollerInner.getAndCheckTopicMetadata(topic);
        BrokerLoadBalance brokerBalance = messagePollerInner.getBrokerLoadBalance(topic);

        BrokerAssignments brokerAssignments = fetchBrokerAssignment(topicMetadata);
        brokerAssignments = messagePollerInner.filterNotAvailableBrokers(brokerAssignments);

        if (CollectionUtils.isEmpty(brokerAssignments.getAssignments())) {
            logger.warn("no broker available, topic: {}", topicMetadata.getTopic());
            return messagePollerInner.buildPollEmptyResult(listener);
        }

        if (batchSize == CUSTOM_BATCH_SIZE) {
            batchSize = (config.getBatchSize() != ConsumerConfig.NONE_BATCH_SIZE ? config.getBatchSize() : topicMetadata.getConsumerPolicy().getBatchSize());
        }

        BrokerAssignment brokerAssignment = brokerBalance.loadBalance(brokerAssignments);
        return messagePollerInner.fetchTopic(brokerAssignment.getBroker(), topicMetadata, batchSize, timeout, timeoutUnit, listener);
    }

    protected BrokerAssignments fetchBrokerAssignment(TopicMetadata topicMetadata) {
        if (brokerAssignmentCache != null && !brokerAssignmentCache.isExpired(config.getSessionTimeout())) {
            return brokerAssignmentCache.getBrokerAssignments();
        }

        BrokerAssignments brokerAssignments = null;
        if (config.isLoadBalance()) {
            brokerAssignments = consumerCoordinator.fetchBrokerAssignment(topicMetadata, messagePollerInner.getAppFullName(), config.getSessionTimeout());
            brokerAssignments = messagePollerInner.filterNotAvailableBrokers(brokerAssignments);
            if (brokerAssignments == null || CollectionUtils.isEmpty(brokerAssignments.getAssignments())) {
                if (config.isFailover()) {
                    logger.debug("no assignment available, assign all broker, topic: {}", topicMetadata.getTopic());
                    brokerAssignments = messagePollerInner.buildAllBrokerAssignments(topicMetadata);
                }
            }
        } else {
            brokerAssignments = messagePollerInner.buildAllBrokerAssignments(topicMetadata);
        }

        brokerAssignments = messagePollerInner.filterRegionBrokers(topicMetadata, brokerAssignments);

        if (topicMetadata.isAllAvailable()) {
            brokerAssignmentCache = new BrokerAssignmentsHolder(brokerAssignments, SystemClock.now());
        }
        return brokerAssignments;
    }

    @Override
    public synchronized JoyQueueCode reply(String topic, List<ConsumeReply> replyList) {
        checkState();
        Preconditions.checkArgument(StringUtils.isNotBlank(topic), "topic not blank");
        TopicMetadata topicMetadata = messagePollerInner.getAndCheckTopicMetadata(topic);

        if (CollectionUtils.isEmpty(replyList)) {
            throw new IllegalArgumentException(String.format("topic %s reply is empty", topic));
        }

        JoyQueueCode result = consumerIndexManager.commitReply(topicMetadata.getTopic(), replyList, messagePollerInner.getAppFullName(), config.getTimeout());
        if (!result.equals(JoyQueueCode.SUCCESS)) {
            // TODO 临时日志
            logger.warn("commit ack error, topic : {}, code: {}, error: {}", topic, result.getCode(), result.getMessage());
        }
        return result;
    }

    @Override
    public JoyQueueCode replyOnce(String topic, ConsumeReply reply) {
        return reply(topic, Lists.newArrayList(reply));
    }

    @Override
    public TopicMetadata getTopicMetadata(String topic) {
        checkState();
        Preconditions.checkArgument(StringUtils.isNotBlank(topic), "topic not blank");

        String topicFullName = messagePollerInner.getTopicFullName(topic);
        return clusterManager.fetchTopicMetadata(topicFullName, messagePollerInner.getAppFullName());
    }

    protected void checkState() {
        if (!isStarted()) {
            throw new ConsumerException("consumer is not started", JoyQueueCode.CN_SERVICE_NOT_AVAILABLE.getCode());
        }
    }
}