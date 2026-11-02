package com.bisotun

/**
 * Editor context menu entry point ("Extract Flow").
 *
 * It is just another configuration of the shared pipeline: maximal depth with models, identical to
 * [CopyDeepFlowAction]. It is not registered in `plugin.xml`; the four menu actions there are
 * `CopyDeepFlowAction`, `CopyShallowFlowAction`, `CopyShallowFlowWithoutModelAction` and
 * `CopyMethodOnlyAction`.
 */
class ExtractFlowAction : ExtractFlowActionBase(
    maxDepth = 4,
    includeModels = true,
    actionText = "Extract Flow"
)
