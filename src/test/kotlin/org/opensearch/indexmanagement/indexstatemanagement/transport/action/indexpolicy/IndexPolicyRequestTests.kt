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

package org.opensearch.indexmanagement.indexstatemanagement.transport.action.indexpolicy

import org.opensearch.indexmanagement.indexstatemanagement.model.Policy
import org.opensearch.indexmanagement.indexstatemanagement.model.State
import org.opensearch.indexmanagement.indexstatemanagement.model.action.AllocationActionConfig
import org.opensearch.indexmanagement.indexstatemanagement.model.action.DeleteActionConfig
import org.opensearch.indexmanagement.indexstatemanagement.model.action.IndexPriorityActionConfig
import org.opensearch.indexmanagement.indexstatemanagement.randomErrorNotification
import org.opensearch.action.support.WriteRequest
import org.opensearch.common.io.stream.BytesStreamOutput
import org.opensearch.common.io.stream.StreamInput
import org.opensearch.test.OpenSearchTestCase
import java.time.Instant
import java.time.temporal.ChronoUnit

class IndexPolicyRequestTests : OpenSearchTestCase() {

    fun `test index policy request index priority action`() {
        val policyID = "policyID"
        val actionConfig = IndexPriorityActionConfig(50, 0)
        val states = listOf(State(name = "SetPriorityState", actions = listOf(actionConfig), transitions = listOf()))
        val policy = Policy(
            id = policyID,
            description = "description",
            schemaVersion = 1L,
            lastUpdatedTime = Instant.now().truncatedTo(ChronoUnit.MILLIS),
            errorNotification = randomErrorNotification(),
            defaultState = states[0].name,
            states = states
        )
        val seqNo: Long = 123
        val primaryTerm: Long = 456
        val refreshPolicy = WriteRequest.RefreshPolicy.NONE
        val req = IndexPolicyRequest(policyID, policy, seqNo, primaryTerm, refreshPolicy)

        val out = BytesStreamOutput()
        req.writeTo(out)
        val sin = StreamInput.wrap(out.bytes().toBytesRef().bytes)
        val newReq = IndexPolicyRequest(sin)
        assertEquals(policyID, newReq.policyID)
        assertEquals(policy, newReq.policy)
        assertEquals(seqNo, newReq.seqNo)
        assertEquals(primaryTerm, newReq.primaryTerm)
        assertEquals(policy, newReq.policy)
    }

    fun `test index policy request allocation action`() {
        val policyID = "policyID"
        val actionConfig = AllocationActionConfig(require = mapOf("box_type" to "hot"), exclude = emptyMap(), include = emptyMap(), index = 0)
        val states = listOf(State("Allocate", listOf(actionConfig), listOf()))

        val policy = Policy(
            id = policyID,
            description = "description",
            schemaVersion = 1L,
            lastUpdatedTime = Instant.now().truncatedTo(ChronoUnit.MILLIS),
            errorNotification = randomErrorNotification(),
            defaultState = states[0].name,
            states = states
        )
        val seqNo: Long = 123
        val primaryTerm: Long = 456
        val refreshPolicy = WriteRequest.RefreshPolicy.NONE
        val req = IndexPolicyRequest(policyID, policy, seqNo, primaryTerm, refreshPolicy)

        val out = BytesStreamOutput()
        req.writeTo(out)
        val sin = StreamInput.wrap(out.bytes().toBytesRef().bytes)
        val newReq = IndexPolicyRequest(sin)
        assertEquals(policyID, newReq.policyID)
        assertEquals(policy, newReq.policy)
        assertEquals(seqNo, newReq.seqNo)
        assertEquals(primaryTerm, newReq.primaryTerm)
        assertEquals(policy, newReq.policy)
    }

    fun `test index policy request delete action`() {
        val policyID = "policyID"
        val actionConfig = DeleteActionConfig(index = 0)
        val states = listOf(State("Delete", listOf(actionConfig), listOf()))

        val policy = Policy(
            id = policyID,
            description = "description",
            schemaVersion = 1L,
            lastUpdatedTime = Instant.now().truncatedTo(ChronoUnit.MILLIS),
            errorNotification = randomErrorNotification(),
            defaultState = states[0].name,
            states = states
        )
        val seqNo: Long = 123
        val primaryTerm: Long = 456
        val refreshPolicy = WriteRequest.RefreshPolicy.NONE
        val req = IndexPolicyRequest(policyID, policy, seqNo, primaryTerm, refreshPolicy)

        val out = BytesStreamOutput()
        req.writeTo(out)
        val sin = StreamInput.wrap(out.bytes().toBytesRef().bytes)
        val newReq = IndexPolicyRequest(sin)
        assertEquals(policyID, newReq.policyID)
        assertEquals(policy, newReq.policy)
        assertEquals(seqNo, newReq.seqNo)
        assertEquals(primaryTerm, newReq.primaryTerm)
        assertEquals(policy, newReq.policy)
    }
}
