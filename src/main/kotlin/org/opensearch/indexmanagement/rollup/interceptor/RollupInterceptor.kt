/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.opensearch.indexmanagement.rollup.interceptor

import org.opensearch.indexmanagement.rollup.model.Rollup
import org.opensearch.indexmanagement.rollup.model.RollupFieldMapping
import org.opensearch.indexmanagement.rollup.model.RollupFieldMapping.Companion.UNKNOWN_MAPPING
import org.opensearch.indexmanagement.rollup.model.dimension.Dimension
import org.opensearch.indexmanagement.rollup.settings.RollupSettings
import org.opensearch.indexmanagement.rollup.util.getDateHistogram
import org.opensearch.indexmanagement.rollup.util.getRollupJobs
import org.opensearch.indexmanagement.rollup.util.populateFieldMappings
import org.opensearch.indexmanagement.rollup.util.rewriteSearchSourceBuilder
import org.apache.logging.log4j.LogManager
import org.opensearch.cluster.metadata.IndexNameExpressionResolver
import org.opensearch.cluster.service.ClusterService
import org.opensearch.common.settings.Settings
import org.opensearch.index.query.BoolQueryBuilder
import org.opensearch.index.query.BoostingQueryBuilder
import org.opensearch.index.query.ConstantScoreQueryBuilder
import org.opensearch.index.query.DisMaxQueryBuilder
import org.opensearch.index.query.MatchAllQueryBuilder
import org.opensearch.index.query.MatchPhraseQueryBuilder
import org.opensearch.index.query.QueryBuilder
import org.opensearch.index.query.RangeQueryBuilder
import org.opensearch.index.query.TermQueryBuilder
import org.opensearch.index.query.TermsQueryBuilder
import org.opensearch.index.search.MatchQuery
import org.opensearch.indexmanagement.rollup.util.isRollupIndex
import org.opensearch.search.aggregations.AggregationBuilder
import org.opensearch.search.aggregations.bucket.histogram.DateHistogramAggregationBuilder
import org.opensearch.search.aggregations.bucket.histogram.DateHistogramInterval
import org.opensearch.search.aggregations.bucket.histogram.HistogramAggregationBuilder
import org.opensearch.search.aggregations.bucket.terms.TermsAggregationBuilder
import org.opensearch.search.aggregations.metrics.AvgAggregationBuilder
import org.opensearch.search.aggregations.metrics.MaxAggregationBuilder
import org.opensearch.search.aggregations.metrics.MinAggregationBuilder
import org.opensearch.search.aggregations.metrics.SumAggregationBuilder
import org.opensearch.search.aggregations.metrics.ValueCountAggregationBuilder
import org.opensearch.search.internal.ShardSearchRequest
import org.opensearch.tasks.Task
import org.opensearch.transport.TransportChannel
import org.opensearch.transport.TransportInterceptor
import org.opensearch.transport.TransportRequest
import org.opensearch.transport.TransportRequestHandler

class RollupInterceptor(
    val clusterService: ClusterService,
    val settings: Settings,
    val indexNameExpressionResolver: IndexNameExpressionResolver
) : TransportInterceptor {

    private val logger = LogManager.getLogger(javaClass)

    @Volatile private var searchEnabled = RollupSettings.ROLLUP_SEARCH_ENABLED.get(settings)

    init {
        clusterService.clusterSettings.addSettingsUpdateConsumer(RollupSettings.ROLLUP_SEARCH_ENABLED) {
            searchEnabled = it
        }
    }

    @Suppress("SpreadOperator")
    override fun <T : TransportRequest> interceptHandler(
        action: String,
        executor: String,
        forceExecution: Boolean,
        actualHandler: TransportRequestHandler<T>
    ): TransportRequestHandler<T> {
        return object : TransportRequestHandler<T> {
            override fun messageReceived(request: T, channel: TransportChannel, task: Task) {
                if (searchEnabled && request is ShardSearchRequest) {
                    val index = request.shardId().indexName
                    val isRollupIndex = isRollupIndex(index, clusterService.state())
                    if (isRollupIndex) {
                        if (request.source().size() != 0) {
                            throw IllegalArgumentException("Rollup search must have size explicitly set to 0, but found ${request.source().size()}")
                        }

                        val indices = request.indices().map { it.toString() }.toTypedArray()
                        val concreteIndices = indexNameExpressionResolver
                                .concreteIndexNames(clusterService.state(), request.indicesOptions(), *indices)

                        if (concreteIndices.size > 1) {
                            logger.warn("There can be only one index in search request if its a rollup search - requested to search [${concreteIndices
                                    .size}] indices including rollup index [$index]")
                            throw IllegalArgumentException("Searching rollup index with other indices is not supported currently")
                        }

                        val rollupJobs = clusterService.state().metadata.index(index).getRollupJobs()
                                ?: throw IllegalArgumentException("Could not find any valid rollup job on the index")

                        val queryFieldMappings = getQueryMetadata(request.source().query())
                        val aggregationFieldMappings = getAggregationMetadata(request.source().aggregations()?.aggregatorFactories)
                        val fieldMappings = queryFieldMappings + aggregationFieldMappings

                        val (matchingRollupJobs, issues) = findMatchingRollupJobs(fieldMappings, rollupJobs)

                        if (matchingRollupJobs.isEmpty()) {
                            throw IllegalArgumentException("Could not find a rollup job that can answer this query because $issues")
                        }

                        val matchedRollup = pickRollupJob(matchingRollupJobs.keys)
                        val fieldNameMappingTypeMap = matchingRollupJobs.getValue(matchedRollup).associateBy({ it.fieldName }, { it.mappingType })

                        // only rebuild if there is necessity to rebuild
                        if (fieldMappings.isNotEmpty()) {
                            request.source(request.source().rewriteSearchSourceBuilder(matchedRollup, fieldNameMappingTypeMap))
                        }
                    }
                }
                actualHandler.messageReceived(request, channel, task)
            }
        }
    }

    @Suppress("ComplexMethod")
    private fun getAggregationMetadata(
        aggregationBuilders: Collection<AggregationBuilder>?,
        fieldMappings: MutableSet<RollupFieldMapping> = mutableSetOf()
    ): Set<RollupFieldMapping> {
        aggregationBuilders?.forEach {
            when (it) {
                is TermsAggregationBuilder -> {
                    fieldMappings.add(RollupFieldMapping(RollupFieldMapping.Companion.FieldType.DIMENSION, it.field(), it.type))
                }
                is DateHistogramAggregationBuilder -> {
                    fieldMappings.add(RollupFieldMapping(RollupFieldMapping.Companion.FieldType.DIMENSION, it.field(), it.type))
                }
                is HistogramAggregationBuilder -> {
                    fieldMappings.add(RollupFieldMapping(RollupFieldMapping.Companion.FieldType.DIMENSION, it.field(), it.type))
                }
                is SumAggregationBuilder -> {
                    fieldMappings.add(RollupFieldMapping(RollupFieldMapping.Companion.FieldType.METRIC, it.field(), it.type))
                }
                is AvgAggregationBuilder -> {
                    fieldMappings.add(RollupFieldMapping(RollupFieldMapping.Companion.FieldType.METRIC, it.field(), it.type))
                }
                is MaxAggregationBuilder -> {
                    fieldMappings.add(RollupFieldMapping(RollupFieldMapping.Companion.FieldType.METRIC, it.field(), it.type))
                }
                is MinAggregationBuilder -> {
                    fieldMappings.add(RollupFieldMapping(RollupFieldMapping.Companion.FieldType.METRIC, it.field(), it.type))
                }
                is ValueCountAggregationBuilder -> {
                    fieldMappings.add(RollupFieldMapping(RollupFieldMapping.Companion.FieldType.METRIC, it.field(), it.type))
                }
                else -> throw IllegalArgumentException("The ${it.type} aggregation is not currently supported in rollups")
            }
            if (it.subAggregations?.isNotEmpty() == true) {
                getAggregationMetadata(it.subAggregations, fieldMappings)
            }
        }
        return fieldMappings
    }

    @Suppress("ComplexMethod")
    private fun getQueryMetadata(
        query: QueryBuilder?,
        fieldMappings: MutableSet<RollupFieldMapping> = mutableSetOf()
    ): Set<RollupFieldMapping> {
        if (query == null) {
            return fieldMappings
        }

        when (query) {
            is TermQueryBuilder -> {
                fieldMappings.add(RollupFieldMapping(RollupFieldMapping.Companion.FieldType.DIMENSION, query.fieldName(), Dimension.Type.TERMS.type))
            }
            is TermsQueryBuilder -> {
                fieldMappings.add(RollupFieldMapping(RollupFieldMapping.Companion.FieldType.DIMENSION, query.fieldName(), Dimension.Type.TERMS.type))
            }
            is RangeQueryBuilder -> {
                fieldMappings.add(RollupFieldMapping(RollupFieldMapping.Companion.FieldType.DIMENSION, query.fieldName(), UNKNOWN_MAPPING))
            }
            is MatchAllQueryBuilder -> {
                // do nothing
            }
            is BoolQueryBuilder -> {
                query.must()?.forEach { this.getQueryMetadata(it, fieldMappings) }
                query.mustNot()?.forEach { this.getQueryMetadata(it, fieldMappings) }
                query.should()?.forEach { this.getQueryMetadata(it, fieldMappings) }
                query.filter()?.forEach { this.getQueryMetadata(it, fieldMappings) }
            }
            is BoostingQueryBuilder -> {
                this.getQueryMetadata(query.positiveQuery(), fieldMappings)
                this.getQueryMetadata(query.negativeQuery(), fieldMappings)
            }
            is ConstantScoreQueryBuilder -> {
                this.getQueryMetadata(query.innerQuery(), fieldMappings)
            }
            is DisMaxQueryBuilder -> {
                query.innerQueries().forEach { this.getQueryMetadata(it, fieldMappings) }
            }
            is MatchPhraseQueryBuilder -> {
                if (!query.analyzer().isNullOrEmpty() || query.slop() != MatchQuery.DEFAULT_PHRASE_SLOP ||
                        query.zeroTermsQuery() != MatchQuery.DEFAULT_ZERO_TERMS_QUERY) {
                    throw IllegalArgumentException("The ${query.name} query is currently not supported with analyzer/slop/zero_terms_query in " +
                            "rollups")
                }
                fieldMappings.add(RollupFieldMapping(RollupFieldMapping.Companion.FieldType.DIMENSION, query.fieldName(), Dimension.Type.TERMS.type))
            }
            else -> {
                throw IllegalArgumentException("The ${query.name} query is currently not supported in rollups")
            }
        }

        return fieldMappings
    }

    // TODO: How does this job matching work with roles/security?
    @Suppress("ComplexMethod")
    private fun findMatchingRollupJobs(
        fieldMappings: Set<RollupFieldMapping>,
        rollupJobs: List<Rollup>
    ): Pair<Map<Rollup, Set<RollupFieldMapping>>, Set<String>> {
        val rollupFieldMappings = rollupJobs.map { rollup ->
            rollup to rollup.populateFieldMappings()
        }.toMap()

        val knownFieldMappings = mutableSetOf<RollupFieldMapping>()
        val unknownFields = mutableSetOf<String>()

        fieldMappings.forEach {
            if (it.mappingType == UNKNOWN_MAPPING) unknownFields.add(it.fieldName)
            else knownFieldMappings.add(it)
        }

        val potentialRollupFieldMappings = rollupFieldMappings.filterValues {
            it.containsAll(knownFieldMappings) && it.map { rollupFieldMapping -> rollupFieldMapping.fieldName }.containsAll(unknownFields)
        }

        val issues = mutableSetOf<String>()
        if (potentialRollupFieldMappings.isEmpty()) {
            // create a global set of all field mappings
            val allFieldMappings = mutableSetOf<RollupFieldMapping>()
            rollupFieldMappings.values.forEach { allFieldMappings.addAll(it) }

            // create a global set of field names to handle unknown mapping types
            val allFields = allFieldMappings.map { it.fieldName }

            // Adding to the issue if cannot find defined field mapping or if the field is missing
            fieldMappings.forEach {
                if (!allFields.contains(it.fieldName)) issues.add(it.toIssue(true))
                else if (it.mappingType != UNKNOWN_MAPPING && !allFieldMappings.contains(it)) issues.add(it.toIssue())
            }
        }

        return potentialRollupFieldMappings to issues
    }

    // TODO: revisit - not entirely sure if this is the best thing to do, especially when there is a range query
    private fun pickRollupJob(rollups: Set<Rollup>): Rollup {
        if (rollups.size == 1) {
            return rollups.first()
        }

        // Picking the job with largest rollup window for now
        return rollups.reduce { matched, new ->
            if (getEstimateRollupInterval(matched) > getEstimateRollupInterval(new)) matched
            else new
        }
    }

    private fun getEstimateRollupInterval(rollup: Rollup): Long {
        return if (rollup.getDateHistogram().calendarInterval != null) {
            DateHistogramInterval(rollup.getDateHistogram().calendarInterval).estimateMillis()
        } else {
            DateHistogramInterval(rollup.getDateHistogram().fixedInterval).estimateMillis()
        }
    }
}
