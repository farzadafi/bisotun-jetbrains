package com.bisotun.language

/**
 * Shown when a `.go` file is opened in an IDE that did not load the Go content module.
 * Kept in the main plugin so the dispatcher can report it without referencing Go PSI.
 */
internal const val GO_PLUGIN_REQUIRED_MESSAGE =
    "Go extraction requires JetBrains GoLand (or IntelliJ IDEA Ultimate) with the bundled " +
        "Go plugin enabled, because it is built on native Go PSI. This IDE does not provide " +
        "the Go plugin."
