/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.core.ml.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.elasticsearch.ResourceAlreadyExistsException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthRequest;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.admin.indices.alias.Alias;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequestBuilder;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequestBuilder;
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.action.admin.indices.template.put.PutIndexTemplateRequest;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.Requests;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.xpack.core.template.IndexTemplateConfig;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static org.elasticsearch.xpack.core.ClientHelper.ML_ORIGIN;
import static org.elasticsearch.xpack.core.ClientHelper.executeAsyncWithOrigin;

/**
 * Utils to create an ML index with alias ready for rollover with a 6-digit suffix
 */
public final class MlIndexAndAlias {

    private static final Logger logger = LogManager.getLogger(MlIndexAndAlias.class);

    // Visible for testing
    static final Comparator<String> INDEX_NAME_COMPARATOR = new Comparator<>() {

        private final Predicate<String> HAS_SIX_DIGIT_SUFFIX = Pattern.compile("\\d{6}").asMatchPredicate();

        @Override
        public int compare(String index1, String index2) {
            String[] index1Parts = index1.split("-");
            String index1Suffix = index1Parts[index1Parts.length - 1];
            boolean index1HasSixDigitsSuffix = HAS_SIX_DIGIT_SUFFIX.test(index1Suffix);
            String[] index2Parts = index2.split("-");
            String index2Suffix = index2Parts[index2Parts.length - 1];
            boolean index2HasSixDigitsSuffix = HAS_SIX_DIGIT_SUFFIX.test(index2Suffix);
            if (index1HasSixDigitsSuffix && index2HasSixDigitsSuffix) {
                return index1Suffix.compareTo(index2Suffix);
            } else if (index1HasSixDigitsSuffix != index2HasSixDigitsSuffix) {
                return Boolean.compare(index1HasSixDigitsSuffix, index2HasSixDigitsSuffix);
            } else {
                return index1.compareTo(index2);
            }
        }
    };

    private MlIndexAndAlias() {}

    /**
     * Creates the first index with a name of the given {@code indexPatternPrefix} followed by "-000001", if the index is missing.
     * Adds an {@code alias} to that index if it was created,
     * or to the index with the highest suffix if the index did not have to be created.
     * The listener is notified with a {@code boolean} that informs whether the index or the alias were created.
     * If the index is created, the listener is not called until the index is ready to use via the supplied alias,
     * so that a method that receives a success response from this method can safely use the index immediately.
     */
    public static void createIndexAndAliasIfNecessary(Client client,
                                                      ClusterState clusterState,
                                                      IndexNameExpressionResolver resolver,
                                                      String indexPatternPrefix,
                                                      String alias,
                                                      ActionListener<Boolean> finalListener) {

        final ActionListener<Boolean> loggingListener = ActionListener.wrap(
            finalListener::onResponse,
            e -> {
                logger.error(new ParameterizedMessage(
                        "Failed to create alias and index with pattern [{}] and alias [{}]",
                        indexPatternPrefix,
                        alias),
                    e);
                finalListener.onFailure(e);
            }
        );

        // If both the index and alias were successfully created then wait for the shards of the index that the alias points to be ready
        ActionListener<Boolean> indexCreatedListener = ActionListener.wrap(
            created -> {
                if (created) {
                    waitForShardsReady(client, alias, loggingListener);
                } else {
                    loggingListener.onResponse(false);
                }
            },
            loggingListener::onFailure
        );

        String legacyIndexWithoutSuffix = indexPatternPrefix;
        String indexPattern = indexPatternPrefix + "*";
        // The initial index name must be suitable for rollover functionality.
        String firstConcreteIndex = indexPatternPrefix + "-000001";
        String[] concreteIndexNames =
            resolver.concreteIndexNames(clusterState, IndicesOptions.lenientExpandOpen(), indexPattern);
        Optional<IndexMetadata> indexPointedByCurrentWriteAlias = clusterState.getMetadata().hasAlias(alias)
            ? clusterState.getMetadata().getIndicesLookup().get(alias).getIndices().stream().findFirst()
            : Optional.empty();

        if (concreteIndexNames.length == 0) {
            if (indexPointedByCurrentWriteAlias.isEmpty()) {
                createFirstConcreteIndex(client, firstConcreteIndex, alias, true, indexCreatedListener);
                return;
            }
            logger.error(
                "There are no indices matching '{}' pattern but '{}' alias points at [{}]. This should never happen.",
                indexPattern, alias, indexPointedByCurrentWriteAlias.get());
        } else if (concreteIndexNames.length == 1 && concreteIndexNames[0].equals(legacyIndexWithoutSuffix)) {
            if (indexPointedByCurrentWriteAlias.isEmpty()) {
                createFirstConcreteIndex(client, firstConcreteIndex, alias, true, indexCreatedListener);
                return;
            }
            if (indexPointedByCurrentWriteAlias.get().getIndex().getName().equals(legacyIndexWithoutSuffix)) {
                createFirstConcreteIndex(
                    client,
                    firstConcreteIndex,
                    alias,
                    false,
                    ActionListener.wrap(
                        unused -> updateWriteAlias(client, alias, legacyIndexWithoutSuffix, firstConcreteIndex, indexCreatedListener),
                        loggingListener::onFailure)
                );
                return;
            }
            logger.error(
                "There is exactly one index (i.e. '{}') matching '{}' pattern but '{}' alias points at [{}]. This should never happen.",
                legacyIndexWithoutSuffix, indexPattern, alias, indexPointedByCurrentWriteAlias.get());
        } else {
            if (indexPointedByCurrentWriteAlias.isEmpty()) {
                assert concreteIndexNames.length > 0;
                String latestConcreteIndexName = Arrays.stream(concreteIndexNames).max(INDEX_NAME_COMPARATOR).get();
                updateWriteAlias(client, alias, null, latestConcreteIndexName, loggingListener);
                return;
            }
        }
        // If the alias is set, there is nothing more to do.
        loggingListener.onResponse(false);
    }

    private static void waitForShardsReady(Client client, String index, ActionListener<Boolean> listener) {
        ClusterHealthRequest healthRequest = Requests.clusterHealthRequest(index)
            .waitForYellowStatus()
            .waitForNoRelocatingShards(true)
            .waitForNoInitializingShards(true);
        executeAsyncWithOrigin(
            client.threadPool().getThreadContext(),
            ML_ORIGIN,
            healthRequest,
            ActionListener.<ClusterHealthResponse>wrap(
                response -> listener.onResponse(response.isTimedOut() == false),
                listener::onFailure),
            client.admin().cluster()::health
        );
    }

    private static void createFirstConcreteIndex(Client client,
                                                 String index,
                                                 String alias,
                                                 boolean addAlias,
                                                 ActionListener<Boolean> listener) {
        logger.info("About to create first concrete index [{}] with alias [{}]", index, alias);
        CreateIndexRequestBuilder requestBuilder = client.admin()
            .indices()
            .prepareCreate(index);
        if (addAlias) {
            requestBuilder.addAlias(new Alias(alias).isHidden(true));
        }
        CreateIndexRequest request = requestBuilder.request();

        executeAsyncWithOrigin(client.threadPool().getThreadContext(),
            ML_ORIGIN,
            request,
            ActionListener.<CreateIndexResponse>wrap(
                createIndexResponse -> listener.onResponse(true),
                createIndexFailure -> {
                    // If it was created between our last check, and this request being handled, we should add the alias
                    // Adding an alias that already exists is idempotent. So, no need to double check if the alias exists
                    // as well.
                    if (ExceptionsHelper.unwrapCause(createIndexFailure) instanceof ResourceAlreadyExistsException) {
                        updateWriteAlias(client, alias, null, index, listener);
                    } else {
                        listener.onFailure(createIndexFailure);
                    }
                }),
            client.admin().indices()::create);
    }

    private static void updateWriteAlias(Client client,
                                         String alias,
                                         @Nullable String currentIndex,
                                         String newIndex,
                                         ActionListener<Boolean> listener) {
        logger.info("About to move write alias [{}] from index [{}] to index [{}]", alias, currentIndex, newIndex);
        IndicesAliasesRequestBuilder requestBuilder = client.admin()
            .indices()
            .prepareAliases()
            .addAliasAction(IndicesAliasesRequest.AliasActions.add().index(newIndex).alias(alias).isHidden(true));
        if (currentIndex != null) {
            requestBuilder.removeAlias(currentIndex, alias);
        }
        IndicesAliasesRequest request = requestBuilder.request();

        executeAsyncWithOrigin(client.threadPool().getThreadContext(),
            ML_ORIGIN,
            request,
            ActionListener.<AcknowledgedResponse>wrap(
                resp -> listener.onResponse(resp.isAcknowledged()),
                listener::onFailure),
            client.admin().indices()::aliases);
    }

    /**
     * Installs the index template specified by {@code templateConfig} if it is not in already
     * installed in {@code clusterState}.
     *
     * The check for presence is simple and will return the listener on
     * the calling thread if successful. If the template has to be installed
     * an async call will be made.
     *
     * @param clusterState The cluster state
     * @param client For putting the template
     * @param templateConfig The config
     * @param listener Async listener
     */
    public static void installIndexTemplateIfRequired(
        ClusterState clusterState,
        Client client,
        IndexTemplateConfig templateConfig,
        ActionListener<Boolean> listener
    ) {
        String templateName = templateConfig.getTemplateName();

        // The check for existence of the template is against the cluster state, so very cheap
        if (hasIndexTemplate(clusterState, templateName)) {
            listener.onResponse(true);
            return;
        }

        PutIndexTemplateRequest request = new PutIndexTemplateRequest(templateName)
            .source(templateConfig.loadBytes(), XContentType.JSON);

        installIndexTemplateIfRequired(clusterState, client, request, listener);
    }

    /**
     * See {@link #installIndexTemplateIfRequired(ClusterState, Client, IndexTemplateConfig, ActionListener)}.
     *
     * Overload takes a {@code PutIndexTemplateRequest} instead of {@code IndexTemplateConfig}
     *
     * @param clusterState The cluster state
     * @param client For putting the template
     * @param templateRequest The Put template request
     * @param listener Async listener
     */
    public static void installIndexTemplateIfRequired(
        ClusterState clusterState,
        Client client,
        PutIndexTemplateRequest templateRequest,
        ActionListener<Boolean> listener
    ) {
        String templateName = templateRequest.name();

        // The check for existence of the template is against the cluster state, so very cheap
        if (hasIndexTemplate(clusterState, templateRequest.name())) {
            listener.onResponse(true);
            return;
        }

        templateRequest.masterNodeTimeout(TimeValue.timeValueMinutes(1));

        ActionListener<AcknowledgedResponse> innerListener = ActionListener.wrap(
            response ->  {
                if (response.isAcknowledged() == false) {
                    logger.warn("error adding legacy template [{}], request was not acknowledged", templateName);
                }
                listener.onResponse(response.isAcknowledged());
            },
            listener::onFailure);

        executeAsyncWithOrigin(client.threadPool().getThreadContext(), ML_ORIGIN, templateRequest, innerListener,
            client.admin().indices()::putTemplate);
    }

    public static boolean hasIndexTemplate(ClusterState state, String templateName) {
        return state.getMetadata().getTemplates().containsKey(templateName);
    }
}