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

package org.opensearch.indexmanagement.indexstatemanagement.action

import org.opensearch.indexmanagement.indexstatemanagement.model.ManagedIndexMetaData
import org.opensearch.indexmanagement.indexstatemanagement.model.action.ActionConfig
import org.opensearch.indexmanagement.indexstatemanagement.model.action.RollupActionConfig
import org.opensearch.indexmanagement.indexstatemanagement.step.Step
import org.opensearch.indexmanagement.indexstatemanagement.step.rollup.AttemptCreateRollupJobStep
import org.opensearch.indexmanagement.indexstatemanagement.step.rollup.WaitForRollupCompletionStep
import org.opensearch.client.Client
import org.opensearch.cluster.service.ClusterService

class RollupAction(
    clusterService: ClusterService,
    client: Client,
    managedIndexMetaData: ManagedIndexMetaData,
    config: RollupActionConfig
) : Action(ActionConfig.ActionType.ROLLUP, config, managedIndexMetaData) {

    private val attemptCreateRollupJobStep = AttemptCreateRollupJobStep(clusterService, client, config.ismRollup, managedIndexMetaData)
    private val waitForRollupCompletionStep = WaitForRollupCompletionStep(clusterService, client, managedIndexMetaData)

    override fun getSteps(): List<Step> = listOf(attemptCreateRollupJobStep, waitForRollupCompletionStep)

    @Suppress("ReturnCount")
    override fun getStepToExecute(): Step {
        // If stepMetaData is null, return the first step
        val stepMetaData = managedIndexMetaData.stepMetaData ?: return attemptCreateRollupJobStep

        // If the current step has completed, return the next step
        if (stepMetaData.stepStatus == Step.StepStatus.COMPLETED) {
            return when (stepMetaData.name) {
                AttemptCreateRollupJobStep.name -> waitForRollupCompletionStep
                else -> attemptCreateRollupJobStep
            }
        }

        return when (stepMetaData.name) {
            AttemptCreateRollupJobStep.name -> attemptCreateRollupJobStep
            else -> waitForRollupCompletionStep
        }
    }
}
