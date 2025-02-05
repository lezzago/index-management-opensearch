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

package org.opensearch.indexmanagement.rollup.action.explain

import org.opensearch.indexmanagement.IndexManagementPlugin.Companion.INDEX_MANAGEMENT_INDEX
import org.opensearch.indexmanagement.opensearchapi.contentParser
import org.opensearch.indexmanagement.opensearchapi.parseWithType
import org.opensearch.indexmanagement.rollup.model.ExplainRollup
import org.opensearch.indexmanagement.rollup.model.Rollup
import org.opensearch.indexmanagement.rollup.model.RollupMetadata
import org.apache.logging.log4j.LogManager
import org.opensearch.ExceptionsHelper
import org.opensearch.ResourceNotFoundException
import org.opensearch.action.ActionListener
import org.opensearch.action.search.SearchRequest
import org.opensearch.action.search.SearchResponse
import org.opensearch.action.support.ActionFilters
import org.opensearch.action.support.HandledTransportAction
import org.opensearch.client.Client
import org.opensearch.common.inject.Inject
import org.opensearch.index.query.BoolQueryBuilder
import org.opensearch.index.query.IdsQueryBuilder
import org.opensearch.index.query.WildcardQueryBuilder
import org.opensearch.search.builder.SearchSourceBuilder
import org.opensearch.tasks.Task
import org.opensearch.transport.RemoteTransportException
import org.opensearch.transport.TransportService
import kotlin.Exception

class TransportExplainRollupAction @Inject constructor(
    transportService: TransportService,
    val client: Client,
    actionFilters: ActionFilters
) : HandledTransportAction<ExplainRollupRequest, ExplainRollupResponse>(
    ExplainRollupAction.NAME, transportService, actionFilters, ::ExplainRollupRequest
) {

    private val log = LogManager.getLogger(javaClass)

    @Suppress("SpreadOperator")
    override fun doExecute(task: Task, request: ExplainRollupRequest, actionListener: ActionListener<ExplainRollupResponse>) {
        val ids = request.rollupIDs
        // Instantiate concrete ids to metadata map by removing wildcard matches
        val idsToExplain: MutableMap<String, ExplainRollup?> = ids.filter { !it.contains("*") }.map { it to null }.toMap(mutableMapOf())
        // First search is for all rollup documents that match at least one of the given rollupIDs
        val searchRequest = SearchRequest(INDEX_MANAGEMENT_INDEX)
            .source(SearchSourceBuilder().query(
                BoolQueryBuilder().minimumShouldMatch(1).apply {
                    ids.forEach {
                        this.should(WildcardQueryBuilder("${Rollup.ROLLUP_TYPE}.${Rollup.ROLLUP_ID_FIELD}.keyword", "*$it*"))
                    }
                }
            ))
        client.search(searchRequest, object : ActionListener<SearchResponse> {
            override fun onResponse(response: SearchResponse) {
                try {
                    response.hits.hits.forEach {
                        val rollup = contentParser(it.sourceRef).parseWithType(it.id, it.seqNo, it.primaryTerm, Rollup.Companion::parse)
                        idsToExplain[rollup.id] = ExplainRollup(metadataID = rollup.metadataID)
                    }
                } catch (e: Exception) {
                    log.error("Failed to parse explain response", e)
                    actionListener.onFailure(e)
                    return
                }

                val metadataIds = idsToExplain.values.mapNotNull { it?.metadataID }
                val metadataSearchRequest = SearchRequest(INDEX_MANAGEMENT_INDEX)
                    .source(SearchSourceBuilder().query(IdsQueryBuilder().addIds(*metadataIds.toTypedArray())))
                client.search(metadataSearchRequest, object : ActionListener<SearchResponse> {
                    override fun onResponse(response: SearchResponse) {
                        try {
                            response.hits.hits.forEach {
                                val metadata = contentParser(it.sourceRef)
                                    .parseWithType(it.id, it.seqNo, it.primaryTerm, RollupMetadata.Companion::parse)
                                idsToExplain.computeIfPresent(metadata.rollupID) { _, explainRollup -> explainRollup.copy(metadata = metadata) }
                            }
                            actionListener.onResponse(ExplainRollupResponse(idsToExplain.toMap()))
                        } catch (e: Exception) {
                            log.error("Failed to parse rollup metadata", e)
                            actionListener.onFailure(e)
                            return
                        }
                    }

                    override fun onFailure(e: Exception) {
                        log.error("Failed to search rollup metadata", e)
                        when (e) {
                            is RemoteTransportException -> actionListener.onFailure(ExceptionsHelper.unwrapCause(e) as Exception)
                            else -> actionListener.onFailure(e)
                        }
                    }
                })
            }

            override fun onFailure(e: Exception) {
                log.error("Failed to search for rollups", e)
                when (e) {
                    is ResourceNotFoundException -> {
                        val nonWildcardIds = ids.filter { !it.contains("*") }.map { it to null }.toMap(mutableMapOf())
                        actionListener.onResponse(ExplainRollupResponse(nonWildcardIds))
                    }
                    is RemoteTransportException -> actionListener.onFailure(ExceptionsHelper.unwrapCause(e) as Exception)
                    else -> actionListener.onFailure(e)
                }
            }
        })
    }
}
