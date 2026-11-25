package com.bisotun.language

/**
 * Shown when a `.kt` file is opened in an IDE that did not load the Kotlin content module.
 * Kept in the main plugin so the dispatcher can report it without referencing Kotlin PSI.
 */
internal const val KOTLIN_PLUGIN_REQUIRED_MESSAGE =
    "Kotlin extraction requires the JetBrains Kotlin plugin, because it is built on native " +
        "Kotlin PSI. This IDE does not provide the Kotlin plugin (or its Java PSI dependency)."
