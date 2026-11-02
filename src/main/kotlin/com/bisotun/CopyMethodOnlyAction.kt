package com.bisotun

/**
 * Copies only the method/function under the caret (maxDepth = 0, no models).
 */
class CopyMethodOnlyAction : ExtractFlowActionBase(
    maxDepth = 0,
    includeModels = false,
    actionText = "Copy Method Only"
)
