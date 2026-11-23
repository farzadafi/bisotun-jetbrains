package com.bisotun

import java.io.DataInputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Plugin Verifier on 2026.1 IDEs flags the Kotlin [com.intellij.openapi.application.runReadAction]
 * overload. The action must call [com.intellij.openapi.application.Application.runReadAction]
 * with a [com.intellij.openapi.util.Computable], which is the API those IDEs still accept.
 */
class ExtractFlowActionReadActionTest {

    @Test
    fun `flow action uses Application runReadAction with Computable`() {
        val type = ExtractFlowActionBase::class.java
        val utf8 = constantPoolUtf8(type)
        val joined = utf8.joinToString("\n")
        val location = type.protectionDomain.codeSource?.location

        assertTrue(
            "com/intellij/openapi/application/ActionsKt" !in joined,
            "Deprecated Kotlin runReadAction must not be referenced. Loaded from $location"
        )
        assertTrue(
            utf8.any { it.contains("Lcom/intellij/openapi/util/Computable;") },
            "Read action must use Computable. Entries: ${utf8.filter { "Computable" in it || it == "runReadAction" }}"
        )
        assertTrue(utf8.any { it == "runReadAction" }, "Read action must still run under a read lock")
    }

    private fun constantPoolUtf8(type: Class<*>): List<String> {
        val resource = type.name.replace('.', '/') + ".class"
        val bytes = checkNotNull(type.classLoader.getResourceAsStream(resource)) {
            "Class file for ${type.name} is not on the test classpath"
        }.use { it.readBytes() }
        DataInputStream(bytes.inputStream()).use { input ->
            require(input.readInt() == 0xCAFEBABE.toInt())
            input.readUnsignedShort()
            input.readUnsignedShort()
            val count = input.readUnsignedShort()
            val values = ArrayList<String>()
            var index = 1
            while (index < count) {
                when (val tag = input.readUnsignedByte()) {
                    1 -> values += input.readUTF()
                    7, 8, 16, 19, 20 -> input.readUnsignedShort()
                    3, 4, 9, 10, 11, 12, 17, 18 -> input.skipBytes(4)
                    5, 6 -> {
                        input.skipBytes(8)
                        index++
                    }
                    15 -> input.skipBytes(3)
                    else -> error("Unknown constant pool tag $tag at index $index")
                }
                index++
            }
            return values
        }
    }
}
