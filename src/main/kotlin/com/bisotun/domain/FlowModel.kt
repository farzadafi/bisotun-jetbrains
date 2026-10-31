package com.bisotun.domain

/**
 * Whether a node of the extracted flow could be resolved to real source or not.
 */
enum class ResolutionStatus { RESOLVED, UNRESOLVED }

/**
 * What kind of source construct a [FlowNode] describes.
 */
enum class NodeKind { FUNCTION, COMPONENT, METHOD, MODEL, UNKNOWN }

/**
 * Inclusive, 1-based line range of a [FlowNode] inside its source file.
 */
data class SourceRange(val startLine: Int, val endLine: Int) {
    companion object {
        val UNKNOWN = SourceRange(1, 1)
    }
}
