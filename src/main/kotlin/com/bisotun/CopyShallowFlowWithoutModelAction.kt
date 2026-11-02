package com.bisotun

/**
 * Copies a shallow flow (maxDepth = 1) without data models.
 */
class CopyShallowFlowWithoutModelAction : ExtractFlowActionBase(
    maxDepth = 1,
    includeModels = false,
    actionText = "Copy Shallow Flow (No Models)"
)
