package com.bisotun

/**
 * Copies the full flow (maxDepth = 4) including related data models.
 */
class CopyDeepFlowAction : ExtractFlowActionBase(
    maxDepth = 4,
    includeModels = true,
    actionText = "Copy Deep Flow"
)
