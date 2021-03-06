/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.security.support;

import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.Version;
import org.elasticsearch.action.admin.indices.template.put.PutIndexTemplateRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlocks;
import org.elasticsearch.cluster.health.ClusterHealthStatus;
import org.elasticsearch.cluster.metadata.AliasMetadata;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.IndexTemplateMetadata;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.IndexRoutingTable;
import org.elasticsearch.cluster.routing.IndexShardRoutingTable;
import org.elasticsearch.cluster.routing.RecoverySource;
import org.elasticsearch.cluster.routing.RoutingTable;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.UnassignedInfo;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.gateway.GatewayService;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.core.security.index.RestrictedIndicesNames;
import org.elasticsearch.xpack.core.template.TemplateUtils;
import org.elasticsearch.xpack.security.Security;
import org.elasticsearch.xpack.security.test.SecurityTestUtils;
import org.hamcrest.Matchers;
import org.junit.Before;

import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.elasticsearch.xpack.security.support.SecurityIndexManager.TEMPLATE_VERSION_VARIABLE;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SecurityIndexManagerTests extends ESTestCase {

    private static final ClusterName CLUSTER_NAME = new ClusterName("security-index-manager-tests");
    private static final ClusterState EMPTY_CLUSTER_STATE = new ClusterState.Builder(CLUSTER_NAME).build();
    private static final String TEMPLATE_NAME = "SecurityIndexManagerTests-template";
    private SecurityIndexManager manager;

    @Before
    public void setUpManager() {
        final Client mockClient = mock(Client.class);
        final ThreadPool threadPool = mock(ThreadPool.class);
        when(threadPool.getThreadContext()).thenReturn(new ThreadContext(Settings.EMPTY));
        when(threadPool.generic()).thenReturn(EsExecutors.newDirectExecutorService());
        when(mockClient.threadPool()).thenReturn(threadPool);
        when(mockClient.settings()).thenReturn(Settings.EMPTY);
        final ClusterService clusterService = mock(ClusterService.class);

        manager = SecurityIndexManager.buildSecurityIndexManager(mockClient, clusterService, Security.SECURITY_MAIN_INDEX_DESCRIPTOR);
    }

    public void testIndexWithUpToDateMappingAndTemplate() throws IOException {
        assertInitialState();

        final ClusterState.Builder clusterStateBuilder = createClusterState(RestrictedIndicesNames.INTERNAL_SECURITY_MAIN_INDEX_7,
                RestrictedIndicesNames.SECURITY_MAIN_ALIAS, TEMPLATE_NAME);
        markShardsAvailable(clusterStateBuilder);
        manager.clusterChanged(event(clusterStateBuilder));

        assertThat(manager.indexExists(), Matchers.equalTo(true));
        assertThat(manager.isAvailable(), Matchers.equalTo(true));
    }

    public void testIndexWithoutPrimaryShards() throws IOException {
        assertInitialState();

        final ClusterState.Builder clusterStateBuilder = createClusterState(RestrictedIndicesNames.INTERNAL_SECURITY_MAIN_INDEX_7,
                RestrictedIndicesNames.SECURITY_MAIN_ALIAS, TEMPLATE_NAME);
        Index index = new Index(RestrictedIndicesNames.INTERNAL_SECURITY_MAIN_INDEX_7, UUID.randomUUID().toString());
        ShardRouting shardRouting = ShardRouting.newUnassigned(new ShardId(index, 0), true,
            RecoverySource.ExistingStoreRecoverySource.INSTANCE, new UnassignedInfo(UnassignedInfo.Reason.INDEX_CREATED, ""));
        String nodeId = ESTestCase.randomAlphaOfLength(8);
        IndexShardRoutingTable table = new IndexShardRoutingTable.Builder(new ShardId(index, 0))
                .addShard(shardRouting.initialize(nodeId, null, shardRouting.getExpectedShardSize())
                        .moveToUnassigned(new UnassignedInfo(UnassignedInfo.Reason.ALLOCATION_FAILED, "")))
                .build();
        clusterStateBuilder.routingTable(RoutingTable.builder()
                .add(IndexRoutingTable.builder(index).addIndexShard(table).build())
                .build());
        manager.clusterChanged(event(clusterStateBuilder));

        assertIndexUpToDateButNotAvailable();
    }

    private ClusterChangedEvent event(ClusterState.Builder clusterStateBuilder) {
        return new ClusterChangedEvent("test-event", clusterStateBuilder.build(), EMPTY_CLUSTER_STATE);
    }

    public void testIndexHealthChangeListeners() throws Exception {
        final AtomicBoolean listenerCalled = new AtomicBoolean(false);
        final AtomicReference<SecurityIndexManager.State> previousState = new AtomicReference<>();
        final AtomicReference<SecurityIndexManager.State> currentState = new AtomicReference<>();
        final BiConsumer<SecurityIndexManager.State, SecurityIndexManager.State> listener = (prevState, state) -> {
            previousState.set(prevState);
            currentState.set(state);
            listenerCalled.set(true);
        };
        manager.addIndexStateListener(listener);

        // index doesn't exist and now exists
        final ClusterState.Builder clusterStateBuilder = createClusterState(RestrictedIndicesNames.INTERNAL_SECURITY_MAIN_INDEX_7,
                RestrictedIndicesNames.SECURITY_MAIN_ALIAS, TEMPLATE_NAME);
        markShardsAvailable(clusterStateBuilder);
        final ClusterState clusterState = clusterStateBuilder.build();
        manager.clusterChanged(event(ClusterState.builder(clusterState)));

        assertTrue(listenerCalled.get());
        assertNull(previousState.get().indexHealth);
        assertEquals(ClusterHealthStatus.GREEN, currentState.get().indexHealth);

        // reset and call with no change to the index
        listenerCalled.set(false);
        previousState.set(null);
        currentState.set(null);
        ClusterChangedEvent event = new ClusterChangedEvent("same index health", clusterState, clusterState);
        manager.clusterChanged(event);

        assertFalse(listenerCalled.get());
        assertNull(previousState.get());
        assertNull(currentState.get());

        // index with different health
        listenerCalled.set(false);
        previousState.set(null);
        currentState.set(null);
        Index prevIndex = clusterState.getRoutingTable().index(RestrictedIndicesNames.INTERNAL_SECURITY_MAIN_INDEX_7).getIndex();
        final ClusterState newClusterState = ClusterState.builder(clusterState).routingTable(RoutingTable.builder()
                .add(IndexRoutingTable.builder(prevIndex)
                        .addIndexShard(new IndexShardRoutingTable.Builder(new ShardId(prevIndex, 0))
                                .addShard(ShardRouting.newUnassigned(new ShardId(prevIndex, 0), true,
                                    RecoverySource.ExistingStoreRecoverySource.INSTANCE,
                                        new UnassignedInfo(UnassignedInfo.Reason.INDEX_CREATED, ""))
                                        .initialize(UUIDs.randomBase64UUID(random()), null, 0L)
                                        .moveToUnassigned(new UnassignedInfo(UnassignedInfo.Reason.ALLOCATION_FAILED, "")))
                                .build()))
                .build()).build();

        event = new ClusterChangedEvent("different index health", newClusterState, clusterState);
        manager.clusterChanged(event);
        assertTrue(listenerCalled.get());
        assertEquals(ClusterHealthStatus.GREEN, previousState.get().indexHealth);
        assertEquals(ClusterHealthStatus.RED, currentState.get().indexHealth);

        // swap prev and current
        listenerCalled.set(false);
        previousState.set(null);
        currentState.set(null);
        event = new ClusterChangedEvent("different index health swapped", clusterState, newClusterState);
        manager.clusterChanged(event);
        assertTrue(listenerCalled.get());
        assertEquals(ClusterHealthStatus.RED, previousState.get().indexHealth);
        assertEquals(ClusterHealthStatus.GREEN, currentState.get().indexHealth);
    }

    public void testWriteBeforeStateNotRecovered() throws Exception {
        final AtomicBoolean prepareRunnableCalled = new AtomicBoolean(false);
        final AtomicReference<Exception> prepareException = new AtomicReference<>(null);
        manager.prepareIndexIfNeededThenExecute(ex -> {
            prepareException.set(ex);
        }, () -> {
            prepareRunnableCalled.set(true);
        });
        assertThat(prepareException.get(), is(notNullValue()));
        assertThat(prepareException.get(), instanceOf(ElasticsearchStatusException.class));
        assertThat(((ElasticsearchStatusException)prepareException.get()).status(), is(RestStatus.SERVICE_UNAVAILABLE));
        assertThat(prepareRunnableCalled.get(), is(false));
        prepareException.set(null);
        prepareRunnableCalled.set(false);
        // state not recovered
        final ClusterBlocks.Builder blocks = ClusterBlocks.builder().addGlobalBlock(GatewayService.STATE_NOT_RECOVERED_BLOCK);
        manager.clusterChanged(event(new ClusterState.Builder(CLUSTER_NAME).blocks(blocks)));
        manager.prepareIndexIfNeededThenExecute(ex -> {
            prepareException.set(ex);
        }, () -> {
            prepareRunnableCalled.set(true);
        });
        assertThat(prepareException.get(), is(notNullValue()));
        assertThat(prepareException.get(), instanceOf(ElasticsearchStatusException.class));
        assertThat(((ElasticsearchStatusException)prepareException.get()).status(), is(RestStatus.SERVICE_UNAVAILABLE));
        assertThat(prepareRunnableCalled.get(), is(false));
        prepareException.set(null);
        prepareRunnableCalled.set(false);
        // state recovered with index
        ClusterState.Builder clusterStateBuilder = createClusterState(RestrictedIndicesNames.INTERNAL_SECURITY_MAIN_INDEX_7,
                RestrictedIndicesNames.SECURITY_MAIN_ALIAS, TEMPLATE_NAME, SecurityIndexManager.INTERNAL_MAIN_INDEX_FORMAT);
        markShardsAvailable(clusterStateBuilder);
        manager.clusterChanged(event(clusterStateBuilder));
        manager.prepareIndexIfNeededThenExecute(ex -> {
            prepareException.set(ex);
        }, () -> {
            prepareRunnableCalled.set(true);
        });
        assertThat(prepareException.get(), is(nullValue()));
        assertThat(prepareRunnableCalled.get(), is(true));
    }

    public void testListenerNotCalledBeforeStateNotRecovered() throws Exception {
        final AtomicBoolean listenerCalled = new AtomicBoolean(false);
        manager.addIndexStateListener((prev, current) -> {
            listenerCalled.set(true);
        });
        final ClusterBlocks.Builder blocks = ClusterBlocks.builder().addGlobalBlock(GatewayService.STATE_NOT_RECOVERED_BLOCK);
        // state not recovered
        manager.clusterChanged(event(new ClusterState.Builder(CLUSTER_NAME).blocks(blocks)));
        assertThat(manager.isStateRecovered(), is(false));
        assertThat(listenerCalled.get(), is(false));
        // state recovered with index
        ClusterState.Builder clusterStateBuilder = createClusterState(RestrictedIndicesNames.INTERNAL_SECURITY_MAIN_INDEX_7,
                RestrictedIndicesNames.SECURITY_MAIN_ALIAS, TEMPLATE_NAME, SecurityIndexManager.INTERNAL_MAIN_INDEX_FORMAT);
        markShardsAvailable(clusterStateBuilder);
        manager.clusterChanged(event(clusterStateBuilder));
        assertThat(manager.isStateRecovered(), is(true));
        assertThat(listenerCalled.get(), is(true));
    }

    public void testIndexOutOfDateListeners() throws Exception {
        final AtomicBoolean listenerCalled = new AtomicBoolean(false);
        manager.clusterChanged(event(new ClusterState.Builder(CLUSTER_NAME)));
        AtomicBoolean upToDateChanged = new AtomicBoolean();
        manager.addIndexStateListener((prev, current) -> {
            listenerCalled.set(true);
            upToDateChanged.set(prev.isIndexUpToDate != current.isIndexUpToDate);
        });
        assertTrue(manager.isIndexUpToDate());

        manager.clusterChanged(event(new ClusterState.Builder(CLUSTER_NAME)));
        assertFalse(listenerCalled.get());
        assertTrue(manager.isIndexUpToDate());

        // index doesn't exist and now exists with wrong format
        ClusterState.Builder clusterStateBuilder = createClusterState(RestrictedIndicesNames.INTERNAL_SECURITY_MAIN_INDEX_7,
                RestrictedIndicesNames.SECURITY_MAIN_ALIAS, TEMPLATE_NAME, SecurityIndexManager.INTERNAL_MAIN_INDEX_FORMAT - 1);
        markShardsAvailable(clusterStateBuilder);
        manager.clusterChanged(event(clusterStateBuilder));
        assertTrue(listenerCalled.get());
        assertTrue(upToDateChanged.get());
        assertFalse(manager.isIndexUpToDate());

        listenerCalled.set(false);
        assertFalse(listenerCalled.get());
        manager.clusterChanged(event(new ClusterState.Builder(CLUSTER_NAME)));
        assertTrue(listenerCalled.get());
        assertTrue(upToDateChanged.get());
        assertTrue(manager.isIndexUpToDate());

        listenerCalled.set(false);
        // index doesn't exist and now exists with correct format
        clusterStateBuilder = createClusterState(RestrictedIndicesNames.INTERNAL_SECURITY_MAIN_INDEX_7,
                RestrictedIndicesNames.SECURITY_MAIN_ALIAS, TEMPLATE_NAME, SecurityIndexManager.INTERNAL_MAIN_INDEX_FORMAT);
        markShardsAvailable(clusterStateBuilder);
        manager.clusterChanged(event(clusterStateBuilder));
        assertTrue(listenerCalled.get());
        assertFalse(upToDateChanged.get());
        assertTrue(manager.isIndexUpToDate());
    }

    public void testProcessClosedIndexState() throws Exception {
        // Index initially exists
        final ClusterState.Builder indexAvailable = createClusterState(RestrictedIndicesNames.INTERNAL_SECURITY_MAIN_INDEX_7,
            RestrictedIndicesNames.SECURITY_MAIN_ALIAS, TEMPLATE_NAME, IndexMetadata.State.OPEN);
        markShardsAvailable(indexAvailable);

        manager.clusterChanged(event(indexAvailable));
        assertThat(manager.indexExists(), is(true));
        assertThat(manager.isAvailable(), is(true));

        // Now close it
        final ClusterState.Builder indexClosed = createClusterState(RestrictedIndicesNames.INTERNAL_SECURITY_MAIN_INDEX_7,
            RestrictedIndicesNames.SECURITY_MAIN_ALIAS, TEMPLATE_NAME, IndexMetadata.State.CLOSE);
        if (randomBoolean()) {
            // In old/mixed cluster versions closed indices have no routing table
            indexClosed.routingTable(RoutingTable.EMPTY_ROUTING_TABLE);
        } else {
            markShardsAvailable(indexClosed);
        }

        manager.clusterChanged(event(indexClosed));
        assertThat(manager.indexExists(), is(true));
        assertThat(manager.isAvailable(), is(false));
    }

    private void assertInitialState() {
        assertThat(manager.indexExists(), Matchers.equalTo(false));
        assertThat(manager.isAvailable(), Matchers.equalTo(false));
        assertThat(manager.isStateRecovered(), Matchers.equalTo(false));
    }

    private void assertIndexUpToDateButNotAvailable() {
        assertThat(manager.indexExists(), Matchers.equalTo(true));
        assertThat(manager.isAvailable(), Matchers.equalTo(false));
        assertThat(manager.isStateRecovered(), Matchers.equalTo(true));
    }

    public static ClusterState.Builder createClusterState(String indexName, String aliasName, String templateName) throws IOException {
        return createClusterState(indexName, aliasName, templateName, IndexMetadata.State.OPEN);
    }

    public static ClusterState.Builder createClusterState(String indexName, String aliasName, String templateName,
                                                          IndexMetadata.State state) throws IOException {
        return createClusterState(indexName, aliasName, templateName, templateName, SecurityIndexManager.INTERNAL_MAIN_INDEX_FORMAT, state);
    }

    public static ClusterState.Builder createClusterState(String indexName, String aliasName, String templateName, int format)
            throws IOException {
        return createClusterState(indexName, aliasName, templateName, templateName, format, IndexMetadata.State.OPEN);
    }

    private static ClusterState.Builder createClusterState(String indexName, String aliasName, String templateName, String buildMappingFrom,
                                                           int format, IndexMetadata.State state) throws IOException {
        IndexTemplateMetadata.Builder templateBuilder = getIndexTemplateMetadata(templateName);
        IndexMetadata.Builder indexMeta = getIndexMetadata(indexName, aliasName, buildMappingFrom, format, state);

        Metadata.Builder metadataBuilder = new Metadata.Builder();
        metadataBuilder.put(templateBuilder);
        metadataBuilder.put(indexMeta);

        return ClusterState.builder(state()).metadata(metadataBuilder.build());
    }

    private void markShardsAvailable(ClusterState.Builder clusterStateBuilder) {
        clusterStateBuilder.routingTable(SecurityTestUtils.buildIndexRoutingTable(RestrictedIndicesNames.INTERNAL_SECURITY_MAIN_INDEX_7));
    }

    private static ClusterState state() {
        final DiscoveryNodes nodes = DiscoveryNodes.builder().masterNodeId("1").localNodeId("1").build();
        return ClusterState.builder(CLUSTER_NAME)
                .nodes(nodes)
                .metadata(Metadata.builder().generateClusterUuidIfNeeded())
                .build();
    }

    private static IndexMetadata.Builder getIndexMetadata(String indexName, String aliasName, String templateName, int format,
                                                          IndexMetadata.State state) {
        IndexMetadata.Builder indexMetadata = IndexMetadata.builder(indexName);
        indexMetadata.settings(Settings.builder()
                .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
                .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
                .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
                .put(IndexMetadata.INDEX_FORMAT_SETTING.getKey(), format)
                .build());
        indexMetadata.putAlias(AliasMetadata.builder(aliasName).build());
        indexMetadata.state(state);
        final String mappings = getTemplateMappings(templateName);
        if (mappings != null) {
            indexMetadata.putMapping(mappings);
        }

        return indexMetadata;
    }

    private static IndexTemplateMetadata.Builder getIndexTemplateMetadata(String templateName) throws IOException {
        final String mappings = getTemplateMappings(templateName);
        IndexTemplateMetadata.Builder templateBuilder = IndexTemplateMetadata.builder(TEMPLATE_NAME)
                .patterns(Arrays.asList(generateRandomStringArray(10, 100, false, false)));
        if (mappings != null) {
            templateBuilder.putMapping(MapperService.SINGLE_MAPPING_NAME, mappings);
        }
        return templateBuilder;
    }

    private static String getTemplateMappings(String templateName) {
        String template = loadTemplate(templateName);
        PutIndexTemplateRequest request = new PutIndexTemplateRequest();
        request.source(template, XContentType.JSON);
        return request.mappings();
    }

    private static String loadTemplate(String templateName) {
        final String resource = "/" + templateName + ".json";
        return TemplateUtils.loadTemplate(resource, Version.CURRENT.toString(), TEMPLATE_VERSION_VARIABLE);
    }
}
