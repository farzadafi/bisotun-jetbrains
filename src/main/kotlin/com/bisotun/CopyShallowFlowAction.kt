package com.bisotun

/**
 * Copies a shallow flow (maxDepth = 1) including related data models.
 */
class CopyShallowFlowAction : ExtractFlowActionBase(
    maxDepth = 1,
    includeModels = true,
    actionText = "Copy Shallow Flow"
)
