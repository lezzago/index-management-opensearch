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

package org.opensearch.indexmanagement.rollup.action

import org.opensearch.indexmanagement.IndexManagementPlugin.Companion.INDEX_MANAGEMENT_INDEX
import org.opensearch.indexmanagement.rollup.action.delete.DeleteRollupRequest
import org.opensearch.indexmanagement.rollup.action.explain.ExplainRollupRequest
import org.opensearch.indexmanagement.rollup.action.get.GetRollupRequest
import org.opensearch.indexmanagement.rollup.action.get.GetRollupsRequest
import org.opensearch.indexmanagement.rollup.action.index.IndexRollupRequest
import org.opensearch.indexmanagement.rollup.action.start.StartRollupRequest
import org.opensearch.indexmanagement.rollup.action.stop.StopRollupRequest
import org.opensearch.indexmanagement.rollup.model.Rollup
import org.opensearch.indexmanagement.rollup.randomRollup
import org.opensearch.action.DocWriteRequest
import org.opensearch.action.support.WriteRequest
import org.opensearch.common.io.stream.BytesStreamOutput
import org.opensearch.common.io.stream.StreamInput
import org.opensearch.index.seqno.SequenceNumbers
import org.opensearch.search.fetch.subphase.FetchSourceContext
import org.opensearch.test.OpenSearchTestCase

class RequestTests : OpenSearchTestCase() {

    fun `test delete rollup request`() {
        val id = "some_id"
        val req = DeleteRollupRequest(id).index(INDEX_MANAGEMENT_INDEX)

        val out = BytesStreamOutput().apply { req.writeTo(this) }
        val sin = StreamInput.wrap(out.bytes().toBytesRef().bytes)
        val streamedReq = DeleteRollupRequest(sin)
        assertEquals(id, streamedReq.id())
    }

    fun `test explain rollup request`() {
        val ids = listOf("oneid", "twoid", "threeid")
        val req = ExplainRollupRequest(ids)

        val out = BytesStreamOutput().apply { req.writeTo(this) }
        val sin = StreamInput.wrap(out.bytes().toBytesRef().bytes)
        val streamedReq = ExplainRollupRequest(sin)
        assertEquals(ids, streamedReq.rollupIDs)
    }

    fun `test get rollup request`() {
        val id = "some_id"
        val srcContext = null
        val preference = "_local"
        val req = GetRollupRequest(id, srcContext, preference)

        val out = BytesStreamOutput().apply { req.writeTo(this) }
        val sin = StreamInput.wrap(out.bytes().toBytesRef().bytes)
        val streamedReq = GetRollupRequest(sin)
        assertEquals(id, streamedReq.id)
        assertEquals(srcContext, streamedReq.srcContext)
        assertEquals(preference, streamedReq.preference)
    }

    fun `test head get rollup request`() {
        val id = "some_id"
        val srcContext = FetchSourceContext.DO_NOT_FETCH_SOURCE
        val req = GetRollupRequest(id, srcContext)

        val out = BytesStreamOutput().apply { req.writeTo(this) }
        val sin = StreamInput.wrap(out.bytes().toBytesRef().bytes)
        val streamedReq = GetRollupRequest(sin)
        assertEquals(id, streamedReq.id)
        assertEquals(srcContext, streamedReq.srcContext)
    }

    fun `test get rollups request default`() {
        val req = GetRollupsRequest()

        val out = BytesStreamOutput().apply { req.writeTo(this) }
        val sin = StreamInput.wrap(out.bytes().toBytesRef().bytes)
        val streamedReq = GetRollupsRequest(sin)
        assertEquals("", streamedReq.searchString)
        assertEquals(0, streamedReq.from)
        assertEquals(20, streamedReq.size)
        assertEquals("${Rollup.ROLLUP_TYPE}.${Rollup.ROLLUP_ID_FIELD}.keyword", streamedReq.sortField)
        assertEquals("asc", streamedReq.sortDirection)
    }

    fun `test get rollups request`() {
        val req = GetRollupsRequest("searching", 10, 50, "sorted", "desc")

        val out = BytesStreamOutput().apply { req.writeTo(this) }
        val sin = StreamInput.wrap(out.bytes().toBytesRef().bytes)
        val streamedReq = GetRollupsRequest(sin)
        assertEquals("searching", streamedReq.searchString)
        assertEquals(10, streamedReq.from)
        assertEquals(50, streamedReq.size)
        assertEquals("sorted", streamedReq.sortField)
        assertEquals("desc", streamedReq.sortDirection)
    }

    fun `test index rollup post request`() {
        val rollup = randomRollup().copy(seqNo = SequenceNumbers.UNASSIGNED_SEQ_NO, primaryTerm = SequenceNumbers.UNASSIGNED_PRIMARY_TERM)
        val req = IndexRollupRequest(
            rollup = rollup,
            refreshPolicy = WriteRequest.RefreshPolicy.IMMEDIATE
        ).index(INDEX_MANAGEMENT_INDEX)

        val out = BytesStreamOutput().apply { req.writeTo(this) }
        val sin = StreamInput.wrap(out.bytes().toBytesRef().bytes)
        val streamedReq = IndexRollupRequest(sin)
        assertEquals(rollup, streamedReq.rollup)
        assertEquals(rollup.seqNo, streamedReq.ifSeqNo())
        assertEquals(rollup.primaryTerm, streamedReq.ifPrimaryTerm())
        assertEquals(WriteRequest.RefreshPolicy.IMMEDIATE, streamedReq.refreshPolicy)
        assertEquals(DocWriteRequest.OpType.CREATE, streamedReq.opType())
    }

    fun `test index rollup put request`() {
        val rollup = randomRollup().copy(seqNo = 1L, primaryTerm = 2L)
        val req = IndexRollupRequest(
            rollup = rollup,
            refreshPolicy = WriteRequest.RefreshPolicy.IMMEDIATE
        ).index(INDEX_MANAGEMENT_INDEX)

        val out = BytesStreamOutput().apply { req.writeTo(this) }
        val sin = StreamInput.wrap(out.bytes().toBytesRef().bytes)
        val streamedReq = IndexRollupRequest(sin)
        assertEquals(rollup, streamedReq.rollup)
        assertEquals(rollup.seqNo, streamedReq.ifSeqNo())
        assertEquals(rollup.primaryTerm, streamedReq.ifPrimaryTerm())
        assertEquals(WriteRequest.RefreshPolicy.IMMEDIATE, streamedReq.refreshPolicy)
        assertEquals(DocWriteRequest.OpType.INDEX, streamedReq.opType())
    }

    fun `test start rollup request`() {
        val id = "some_id"
        val req = StartRollupRequest(id).index(INDEX_MANAGEMENT_INDEX)

        val out = BytesStreamOutput().apply { req.writeTo(this) }
        val sin = StreamInput.wrap(out.bytes().toBytesRef().bytes)
        val streamedReq = StartRollupRequest(sin)
        assertEquals(id, streamedReq.id())
    }

    fun `test stop rollup request`() {
        val id = "some_id"
        val req = StopRollupRequest(id).index(INDEX_MANAGEMENT_INDEX)

        val out = BytesStreamOutput().apply { req.writeTo(this) }
        val sin = StreamInput.wrap(out.bytes().toBytesRef().bytes)
        val streamedReq = StopRollupRequest(sin)
        assertEquals(id, streamedReq.id())
    }
}
