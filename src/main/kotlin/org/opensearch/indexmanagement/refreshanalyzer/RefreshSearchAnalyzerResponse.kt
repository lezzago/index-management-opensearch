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
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

package org.opensearch.indexmanagement.refreshanalyzer

import org.opensearch.action.support.DefaultShardOperationFailedException
import org.opensearch.action.support.broadcast.BroadcastResponse
import org.opensearch.common.io.stream.StreamInput
import org.opensearch.common.io.stream.StreamOutput
import org.opensearch.common.xcontent.ConstructingObjectParser
import org.opensearch.common.xcontent.ToXContent.Params
import org.opensearch.common.xcontent.XContentBuilder
import org.opensearch.rest.action.RestActions
import java.io.IOException
import java.util.function.Function

class RefreshSearchAnalyzerResponse : BroadcastResponse {

    private lateinit var shardResponses: MutableList<RefreshSearchAnalyzerShardResponse>
    private lateinit var shardFailures: MutableList<DefaultShardOperationFailedException>

    @Throws(IOException::class)
    constructor(inp: StreamInput) : super(inp) {
        inp.readList(::RefreshSearchAnalyzerShardResponse)
        inp.readList(DefaultShardOperationFailedException::readShardOperationFailed)
    }

    constructor(
        totalShards: Int,
        successfulShards: Int,
        failedShards: Int,
        shardFailures: List<DefaultShardOperationFailedException>,
        shardResponses: List<RefreshSearchAnalyzerShardResponse>
    ) : super(
            totalShards, successfulShards, failedShards, shardFailures
    ) {
        this.shardResponses = shardResponses.toMutableList()
        this.shardFailures = shardFailures.toMutableList()
    }

    @Throws(IOException::class)
    override fun toXContent(builder: XContentBuilder, params: Params?): XContentBuilder? {
        builder.startObject()
        RestActions.buildBroadcastShardsHeader(builder, params, totalShards, successfulShards, -1, failedShards, shardFailures.toTypedArray())
        builder.startArray("successful_refresh_details")
        val successfulIndices = getSuccessfulRefreshDetails()
        for (index in successfulIndices.keys) {
            val reloadedAnalyzers = successfulIndices.get(index)!!
            builder.startObject().field("index", index).startArray("refreshed_analyzers")
            for (analyzer in reloadedAnalyzers) {
                builder.value(analyzer)
            }
            builder.endArray().endObject()
        }
        builder.endArray().endObject()
        return builder
    }

    // TODO: restrict it for testing
    fun getSuccessfulRefreshDetails(): MutableMap<String, List<String>> {
        var successfulRefreshDetails: MutableMap<String, List<String>> = HashMap()
        var failedIndices = mutableSetOf<String>()
        for (failure in shardFailures) {
            failedIndices.add(failure.index()!!)
        }
        for (response in shardResponses) {
            if (!failedIndices.contains(response.index)) {
                successfulRefreshDetails.putIfAbsent(response.index, response.reloadedAnalyzers)
            }
        }
        return successfulRefreshDetails
    }

    companion object {
        private val PARSER = ConstructingObjectParser<RefreshSearchAnalyzerResponse, Void>("_refresh_search_analyzers", true,
                Function { arg: Array<Any> ->
                    val response = arg[0] as RefreshSearchAnalyzerResponse
                    RefreshSearchAnalyzerResponse(response.totalShards, response.successfulShards, response.failedShards,
                            response.shardFailures, response.shardResponses)
                })
        init {
            declareBroadcastFields(PARSER)
        }
    }

    @Throws(IOException::class)
    override fun writeTo(out: StreamOutput) {
        super.writeTo(out)
        out.writeCollection(shardResponses)
        out.writeCollection(shardFailures)
    }
}
