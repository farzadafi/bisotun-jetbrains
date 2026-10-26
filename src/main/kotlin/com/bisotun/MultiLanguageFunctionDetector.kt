package com.bisotun

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.PsiNamedElement

/**
 * Language-agnostic function detection for Java, JavaScript, TypeScript, JSX, TSX
 * Uses class name checking to avoid hard dependencies on JavaScript PSI classes
 */
class MultiLanguageFunctionDetector {
    
    companion object {
        
        /**
         * Find the nearest function/method element at the given caret position
         * Works for Java, JavaScript, TypeScript, JSX, TSX
         */
        fun findFunctionAtCaret(psiFile: PsiFile, caretOffset: Int): PsiElement? {
            val element = psiFile.findElementAt(caretOffset) ?: return null
            
            // Try Java method first
            val javaMethod = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
            if (javaMethod != null) {
                return javaMethod
            }
            
            // Check file language/extension
            val isJsFile = isJavaScriptOrTypeScriptFile(psiFile)
            
            if (isJsFile) {
                // Try to find JavaScript/TypeScript function
                return findJsFunctionAtElement(element)
            }
            
            // Try generic PSI tree traversal as fallback
            return findGenericFunctionElement(element)
        }
        
        /**
         * Find JavaScript/TypeScript function element
         * Uses class name checking to avoid hard dependency
         */
        private fun findJsFunctionAtElement(element: PsiElement): PsiElement? {
            var current: PsiElement? = element
            
            // Traverse up the PSI tree looking for function-like elements
            while (current != null && current !== current.containingFile) {
                val elementClassName = current::class.java.name
                
                // Check for JavaScript function types using class name patterns
                when {
                    elementClassName.contains("JSFunction") -> return current
                    elementClassName.contains("FunctionExpression") -> return current
                    elementClassName.contains("ArrowFunction") -> return current
                    elementClassName.contains("Method") && !elementClassName.contains("PsiMethod") -> return current
                    elementClassName.contains("JSClassMethod") -> return current
                    elementClassName.contains("JSFunctionExpression") -> return current
                    elementClassName.contains("JSFunctionDeclaration") -> return current
                    elementClassName.contains("JSElement") && elementClassName.contains("Function") -> return current
                }
                
                // Check for React components
                if (isReactComponent(current)) {
                    return current
                }
                
                // Check for class-like elements that might contain methods
                if (elementClassName.contains("JSClass") || 
                    elementClassName.contains("ClassExpression") ||
                    elementClassName.contains("ClassDeclaration")) {
                    // We're inside a class, look for methods
                    return findMethodInClass(current, element)
                }
                
                current = current.parent
            }
            
            return null
        }
        
        /**
         * Find method within a class element
         */
        private fun findMethodInClass(classElement: PsiElement, originalElement: PsiElement): PsiElement? {
            // Look for method-like children near the original element
            var current: PsiElement? = originalElement
            while (current != null && current !== classElement && current !== current.containingFile) {
                val elementClassName = current::class.java.name
                if (elementClassName.contains("Method") || 
                    elementClassName.contains("Function") ||
                    elementClassName.contains("Property") && current.text?.contains("(") == true) {
                    return current
                }
                current = current.parent
            }
            return null
        }
        
        /**
         * Check if element is a React component
         */
        private fun isReactComponent(element: PsiElement): Boolean {
            val elementText = element.text ?: ""
            
            // Look for React component patterns
            return when {
                // Arrow function components
                elementText.contains("=>") && (
                    elementText.contains("React.FC") || 
                    elementText.contains("FC<") ||
                    elementText.contains(": FC") ||
                    elementText.contains("React.Component") ||
                    elementText.contains("Component<")
                ) -> true
                
                // Function declaration components
                elementText.contains("function") && (
                    elementText.contains("Component") ||
                    elementText.contains("props") ||
                    elementText.contains("return") && elementText.contains("JSX") ||
                    elementText.contains("return") && elementText.contains("<")
                ) -> true
                
                // JSX/TSX elements
                elementText.contains("<") && elementText.contains(">") && 
                elementText.contains("(") && elementText.contains(")") -> true
                
                else -> false
            }
        }
        
        /**
         * Generic function detection using AST node types
         */
        private fun findGenericFunctionElement(element: PsiElement): PsiElement? {
            var current: PsiElement? = element
            
            while (current != null && current !== current.containingFile) {
                val node = current.node
                if (node != null) {
                    val elementType = node.elementType.toString().lowercase()
                    
                    // Look for function/method patterns in element type
                    when {
                        elementType.contains("function") -> return current
                        elementType.contains("method") -> return current
                        elementType.contains("declaration") && elementType.contains("function") -> return current
                        elementType.contains("expression") && elementType.contains("function") -> return current
                        elementType.contains("arrow") -> return current
                        elementType.contains("lambda") -> return current
                    }
                }
                
                // Check if element has a name and looks like a function
                if (current is PsiNamedElement) {
                    val text = current.text ?: ""
                    if (text.contains("(") && text.contains(")") && 
                        (text.contains("{") || text.contains("=>") || text.endsWith(";"))) {
                        return current
                    }
                }
                
                // Check text patterns for function-like structures
                val text = current.text ?: ""
                if ((text.contains("function ") || text.contains("=>") || text.contains("(")) && 
                    text.contains(")") && (text.contains("{") || text.contains("=>"))) {
                    return current
                }
                
                current = current.parent
            }
            
            return null
        }
        
        /**
         * Get function/method name for any language
         */
        fun getFunctionName(functionElement: PsiElement): String {
            return when {
                functionElement is PsiMethod -> {
                    val className = functionElement.containingClass?.qualifiedName ?: 
                                   functionElement.containingClass?.name ?: "Unknown"
                    "$className.${functionElement.name ?: "unknown"}"
                }
                functionElement is PsiNamedElement -> {
                    functionElement.name ?: "anonymous"
                }
                else -> {
                    // Try to extract name from text
                    val text = functionElement.text ?: "anonymous"
                    val lines = text.lines().take(3)
                    lines.firstOrNull { it.contains("function") || it.contains("=>") || it.contains("(") }?.let { line ->
                        // Extract function name from line
                        val pattern = Regex("""(?:function\s+|const\s+|let\s+|var\s+|export\s+)*(?:default\s+)?([A-Za-z_$][A-Za-z0-9_$]*)\s*[=(:]""")
                        pattern.find(line)?.groupValues?.getOrNull(1) ?: "anonymous"
                    } ?: "anonymous"
                }
            }
        }
        
        /**
         * Get function signature for any language
         */
        fun getFunctionSignature(functionElement: PsiElement): String {
            val name = getFunctionName(functionElement)
            
            // Try to extract parameters from text
            val text = functionElement.text ?: ""
            val paramStart = text.indexOf('(')
            val paramEnd = text.indexOf(')', paramStart)
            
            return if (paramStart >= 0 && paramEnd > paramStart) {
                val params = text.substring(paramStart + 1, paramEnd).trim()
                if (params.isNotEmpty()) {
                    "$name($params)"
                } else {
                    "$name()"
                }
            } else {
                name
            }
        }
        
        /**
         * Check if file is a JavaScript/TypeScript file
         */
        fun isJavaScriptOrTypeScriptFile(psiFile: PsiFile): Boolean {
            val fileName = psiFile.virtualFile?.name?.lowercase() ?: ""
            val fileExtension = psiFile.virtualFile?.extension?.lowercase() ?: ""
            
            return fileExtension in setOf("js", "jsx", "ts", "tsx") ||
                   fileName.endsWith(".js") || fileName.endsWith(".jsx") ||
                   fileName.endsWith(".ts") || fileName.endsWith(".tsx")
        }
        
        /**
         * Check if file is a Java/Kotlin file
         */
        fun isJavaOrKotlinFile(psiFile: PsiFile): Boolean {
            val fileName = psiFile.virtualFile?.name?.lowercase() ?: ""
            val fileExtension = psiFile.virtualFile?.extension?.lowercase() ?: ""
            
            return fileExtension in setOf("java", "kt") ||
                   fileName.endsWith(".java") || fileName.endsWith(".kt")
        }
    }
}
