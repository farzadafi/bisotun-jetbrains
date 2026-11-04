package com.bisotun

import com.bisotun.domain.FlowNode
import com.bisotun.domain.FlowResult
import com.bisotun.domain.ResolutionStatus

/**
 * Renders a [FlowResult] as markdown.
 *
 * Pure rendering: no PSI and no project access, because every [FlowNode] already carries its file
 * path, line range, language and source snippet. This keeps rendering valid after the read action
 * that produced the result has ended.
 */
class MarkdownFormatter {

    fun format(result: FlowResult, maxDepth: Int, includeModels: Boolean): String {
        val sb = StringBuilder()

        sb.appendLine("# Flow Extracted by Bisotun")
        sb.appendLine()
        sb.appendLine("**Configuration:** maxDepth=$maxDepth, includeModels=$includeModels")
        sb.appendLine()

        for (warning in result.warnings) {
            sb.appendLine("> $warning")
        }
        if (result.warnings.isNotEmpty()) sb.appendLine()

        appendSummary(sb, result)
        appendNodes(sb, result)
        if (includeModels) appendModels(sb, result)

        return sb.toString()
    }

    private fun appendSummary(sb: StringBuilder, result: FlowResult) {
        val nodes = uniqueNodes(result.allNodes)
        if (nodes.isEmpty()) return
        sb.appendLine("## Execution Flow Summary")
        sb.appendLine()
        nodes.forEachIndexed { index, node ->
            sb.appendLine("${index + 1}. `${node.displayName}`")
        }
        sb.appendLine()
    }

    private fun appendNodes(sb: StringBuilder, result: FlowResult) {
        val nodes = uniqueNodes(result.allNodes)
        if (nodes.isEmpty()) return
        sb.appendLine("## Source Code & Implementations")
        sb.appendLine()
        nodes.forEachIndexed { index, node -> appendNode(sb, index + 1, node) }
    }

    private fun appendModels(sb: StringBuilder, result: FlowResult) {
        val models = uniqueNodes(result.models)
        if (models.isEmpty()) return
        sb.appendLine("## Related Data Models & Entities")
        sb.appendLine()
        models.forEachIndexed { index, model -> appendNode(sb, index + 1, model) }
    }

    private fun appendNode(sb: StringBuilder, index: Int, node: FlowNode) {
        sb.appendLine("### $index. `${node.displayName}`")
        if (node.filePath.isNotEmpty()) {
            sb.appendLine("- File: `${node.filePath}`")
        }
        sb.appendLine("- Lines: ${node.range.startLine}-${node.range.endLine}")
        if (node.resolutionStatus == ResolutionStatus.UNRESOLVED) {
            sb.appendLine("- Resolution: unresolved (no source declaration found, not expanded)")
        }
        sb.appendLine()
        val code = node.code.trim()
        if (code.isEmpty()) return
        sb.appendLine("```${node.languageKind.codeFence}")
        sb.appendLine(code)
        sb.appendLine("```")
        sb.appendLine()
    }

    private fun uniqueNodes(nodes: List<FlowNode>): List<FlowNode> {
        val unique = LinkedHashMap<String, FlowNode>()
        for (node in nodes) {
            unique.putIfAbsent(node.id, node)
        }
        return unique.values.toList()
    }
}
