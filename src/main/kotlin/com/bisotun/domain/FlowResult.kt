package com.bisotun.domain

import com.bisotun.language.LanguageKind

/**
 * Language agnostic flow node.
 *
 * It deliberately holds no PSI reference: extraction converts platform elements into plain data
 * (id, display name, file, line range, source snippet) so that rendering and clipboard delivery
 * never touch the PSI tree, and nodes stay valid after the read action ends.
 */
data class FlowNode(
    val id: String,
    val displayName: String,
    val filePath: String,
    val range: SourceRange,
    val languageKind: LanguageKind,
    val resolutionStatus: ResolutionStatus,
    val kind: NodeKind,
    val code: String = ""
)

/**
 * Result of a single extraction run.
 *
 * [warnings] are rendered verbatim in the markdown output (for example the JS/TS fallback notice
 * that is emitted when the IntelliJ JavaScript plugin is not available).
 */
data class FlowResult(
    val root: FlowNode,
    val nodes: List<FlowNode>,
    val models: List<FlowNode>,
    val warnings: List<String> = emptyList()
) {
    /** Root plus crawled nodes, in the order used by the summary and the source listing. */
    val allNodes: List<FlowNode> get() = listOf(root) + nodes
}
