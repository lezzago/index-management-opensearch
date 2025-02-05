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

package org.opensearch.indexmanagement.rollup.model.dimension

import org.opensearch.common.io.stream.Writeable
import org.opensearch.common.xcontent.ToXContentObject
import org.opensearch.common.xcontent.XContentParser
import org.opensearch.common.xcontent.XContentParser.Token
import org.opensearch.common.xcontent.XContentParserUtils.ensureExpectedToken
import java.io.IOException

abstract class Dimension(
    val type: Type,
    open val sourceField: String,
    open val targetField: String
) : ToXContentObject, Writeable {
    enum class Type(val type: String) {
        DATE_HISTOGRAM("date_histogram"),
        TERMS("terms"),
        HISTOGRAM("histogram");

        override fun toString(): String {
            return type
        }
    }

    companion object {
        const val DIMENSION_SOURCE_FIELD_FIELD = "source_field"
        const val DIMENSION_TARGET_FIELD_FIELD = "target_field"

        @Suppress("ComplexMethod")
        @JvmStatic
        @Throws(IOException::class)
        fun parse(xcp: XContentParser): Dimension {
            var dimension: Dimension? = null
            ensureExpectedToken(Token.START_OBJECT, xcp.currentToken(), xcp)
            while (xcp.nextToken() != Token.END_OBJECT) {
                val fieldName = xcp.currentName()
                xcp.nextToken()

                dimension = when (fieldName) {
                    Type.DATE_HISTOGRAM.type -> DateHistogram.parse(xcp)
                    Type.TERMS.type -> Terms.parse(xcp)
                    Type.HISTOGRAM.type -> Histogram.parse(xcp)
                    else -> throw IllegalArgumentException("Invalid dimension type [$fieldName] found in dimensions")
                }
            }

            return requireNotNull(dimension) { "Dimension cannot be null" }
        }
    }
}
