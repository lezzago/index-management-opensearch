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

package org.opensearch.indexmanagement.indexstatemanagement.transport.action.addpolicy

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

import org.opensearch.indexmanagement.IndexManagementIndices
import org.opensearch.indexmanagement.IndexManagementPlugin.Companion.INDEX_MANAGEMENT_INDEX
import org.opensearch.indexmanagement.indexstatemanagement.opensearchapi.getUuidsForClosedIndices
import org.opensearch.indexmanagement.indexstatemanagement.settings.ManagedIndexSettings
import org.opensearch.indexmanagement.indexstatemanagement.transport.action.ISMStatusResponse
import org.opensearch.indexmanagement.indexstatemanagement.util.FailedIndex
import org.opensearch.indexmanagement.indexstatemanagement.util.managedIndexConfigIndexRequest
import org.apache.logging.log4j.LogManager
import org.opensearch.OpenSearchStatusException
import org.opensearch.OpenSearchTimeoutException
import org.opensearch.ExceptionsHelper
import org.opensearch.action.ActionListener
import org.opensearch.action.admin.cluster.state.ClusterStateRequest
import org.opensearch.action.admin.cluster.state.ClusterStateResponse
import org.opensearch.action.bulk.BulkRequest
import org.opensearch.action.bulk.BulkResponse
import org.opensearch.action.get.MultiGetRequest
import org.opensearch.action.get.MultiGetResponse
import org.opensearch.action.support.ActionFilters
import org.opensearch.action.support.HandledTransportAction
import org.opensearch.action.support.IndicesOptions
import org.opensearch.action.support.master.AcknowledgedResponse
import org.opensearch.client.node.NodeClient
import org.opensearch.cluster.ClusterState
import org.opensearch.cluster.block.ClusterBlockException
import org.opensearch.cluster.service.ClusterService
import org.opensearch.common.inject.Inject
import org.opensearch.common.settings.Settings
import org.opensearch.common.unit.TimeValue
import org.opensearch.rest.RestStatus
import org.opensearch.tasks.Task
import org.opensearch.transport.TransportService
import java.lang.Exception
import java.time.Duration
import java.time.Instant

private val log = LogManager.getLogger(TransportAddPolicyAction::class.java)

class TransportAddPolicyAction @Inject constructor(
    val client: NodeClient,
    transportService: TransportService,
    actionFilters: ActionFilters,
    val settings: Settings,
    val clusterService: ClusterService,
    val ismIndices: IndexManagementIndices
) : HandledTransportAction<AddPolicyRequest, ISMStatusResponse>(
        AddPolicyAction.NAME, transportService, actionFilters, ::AddPolicyRequest
) {

    @Volatile private var jobInterval = ManagedIndexSettings.JOB_INTERVAL.get(settings)

    init {
        clusterService.clusterSettings.addSettingsUpdateConsumer(ManagedIndexSettings.JOB_INTERVAL) {
            jobInterval = it
        }
    }

    override fun doExecute(task: Task, request: AddPolicyRequest, listener: ActionListener<ISMStatusResponse>) {
        AddPolicyHandler(client, listener, request).start()
    }

    inner class AddPolicyHandler(
        private val client: NodeClient,
        private val actionListener: ActionListener<ISMStatusResponse>,
        private val request: AddPolicyRequest
    ) {
        private lateinit var startTime: Instant
        private val indicesToAdd = mutableMapOf<String, String>() // uuid: name
        private val failedIndices: MutableList<FailedIndex> = mutableListOf()

        fun start() {
            ismIndices.checkAndUpdateIMConfigIndex(object : ActionListener<AcknowledgedResponse> {
                override fun onResponse(response: AcknowledgedResponse) {
                    onCreateMappingsResponse(response)
                }

                override fun onFailure(t: Exception) {
                    actionListener.onFailure(ExceptionsHelper.unwrapCause(t) as Exception)
                }
            })
        }

        private fun onCreateMappingsResponse(response: AcknowledgedResponse) {
            if (response.isAcknowledged) {
                log.info("Successfully created or updated $INDEX_MANAGEMENT_INDEX with newest mappings.")
                getClusterState()
            } else {
                log.error("Unable to create or update $INDEX_MANAGEMENT_INDEX with newest mapping.")

                actionListener.onFailure(OpenSearchStatusException(
                    "Unable to create or update $INDEX_MANAGEMENT_INDEX with newest mapping.",
                    RestStatus.INTERNAL_SERVER_ERROR))
            }
        }

        @Suppress("SpreadOperator")
        fun getClusterState() {
            val strictExpandOptions = IndicesOptions.strictExpand()

            val clusterStateRequest = ClusterStateRequest()
                .clear()
                .indices(*request.indices.toTypedArray())
                .metadata(true)
                .local(false)
                .waitForTimeout(TimeValue.timeValueMillis(ADD_POLICY_TIMEOUT_IN_MILLIS))
                .indicesOptions(strictExpandOptions)

            startTime = Instant.now()

            client.admin()
                .cluster()
                .state(clusterStateRequest, object : ActionListener<ClusterStateResponse> {
                    override fun onResponse(response: ClusterStateResponse) {
                        response.state.metadata.indices.forEach {
                            indicesToAdd.putIfAbsent(it.value.indexUUID, it.key)
                        }

                        populateLists(response.state)
                    }

                    override fun onFailure(t: Exception) {
                        actionListener.onFailure(ExceptionsHelper.unwrapCause(t) as Exception)
                    }
                })
        }

        private fun populateLists(state: ClusterState) {
            getUuidsForClosedIndices(state).forEach {
                failedIndices.add(FailedIndex(indicesToAdd[it] as String, it, "This index is closed"))
                indicesToAdd.remove(it)
            }
            if (indicesToAdd.isEmpty()) {
                actionListener.onResponse(ISMStatusResponse(0, failedIndices))
                return
            }

            val multiGetReq = MultiGetRequest()
            indicesToAdd.forEach { multiGetReq.add(INDEX_MANAGEMENT_INDEX, it.key) }

            client.multiGet(multiGetReq, object : ActionListener<MultiGetResponse> {
                override fun onResponse(response: MultiGetResponse) {
                    response.forEach {
                        if (it.response.isExists) {
                            val docId = it.id // docId is managed index uuid
                            failedIndices.add(FailedIndex(indicesToAdd[docId] as String, docId,
                                    "This index already has a policy, use the update policy API to update index policies"))
                            indicesToAdd.remove(docId)
                        }
                    }

                    createManagedIndices()
                }

                override fun onFailure(t: Exception) {
                    actionListener.onFailure(ExceptionsHelper.unwrapCause(t) as Exception)
                }
            })
        }

        private fun createManagedIndices() {
            if (indicesToAdd.isNotEmpty()) {
                val timeSinceClusterStateRequest: Duration = Duration.between(startTime, Instant.now())

                // Timeout for UpdateSettingsRequest in milliseconds
                val bulkReqTimeout = ADD_POLICY_TIMEOUT_IN_MILLIS - timeSinceClusterStateRequest.toMillis()

                // If after the ClusterStateResponse we go over the timeout for Add Policy (30 seconds), throw an
                // exception since UpdateSettingsRequest cannot have a negative timeout
                if (bulkReqTimeout < 0) {
                    throw OpenSearchTimeoutException("Add policy API timed out after ClusterStateResponse")
                }

                val bulkReq = BulkRequest().timeout(TimeValue.timeValueMillis(bulkReqTimeout))
                indicesToAdd.forEach { (uuid, name) ->
                    bulkReq.add(managedIndexConfigIndexRequest(name, uuid, request.policyID, jobInterval))
                }

                client.bulk(bulkReq, object : ActionListener<BulkResponse> {
                    override fun onResponse(response: BulkResponse) {
                        response.forEach {
                            val docId = it.id // docId is managed index uuid
                            if (it.isFailed) {
                                failedIndices.add(FailedIndex(indicesToAdd[docId] as String, docId,
                                    "Failed to add policy due to: ${it.failureMessage}"))
                                indicesToAdd.remove(docId)
                            }
                        }
                        actionListener.onResponse(ISMStatusResponse(indicesToAdd.size, failedIndices))
                    }

                    override fun onFailure(t: Exception) {
                        if (t is ClusterBlockException) {
                            indicesToAdd.forEach { (uuid, name) ->
                                failedIndices.add(FailedIndex(name, uuid, "Failed to add policy due to ClusterBlockingException: ${t.message}"))
                            }
                            actionListener.onResponse(ISMStatusResponse(0, failedIndices))
                        } else {
                            actionListener.onFailure(ExceptionsHelper.unwrapCause(t) as Exception)
                        }
                    }
                })
            } else {
                actionListener.onResponse(ISMStatusResponse(0, failedIndices))
            }
        }
    }

    companion object {
        const val ADD_POLICY_TIMEOUT_IN_MILLIS = 30000L
    }
}
