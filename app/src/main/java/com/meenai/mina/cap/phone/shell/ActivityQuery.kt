package com.meenai.mina.cap.phone.shell

/**
 * When the system interface cannot check which pages can receive an Intent (some systems read additional
 * settings in the query on the application side, and throw an error if the shell identity cannot be
 * read), ask `cmd package query-activities` instead: The query is done in the system service, and only
 * the shell identity is recognized. It does not rely on Android classes, and unit tests can be adjusted
 * directly.
 */
internal object ActivityQuery {
    /** Command parameters: [action], optional [type], [uri], [category]. When limiting [packageName], only check this package; [user] checks which user (default current user). */
    fun args(action: String, type: String? = null, uri: String? = null, category: String? = null, packageName: String? = null, user: String = "current"): List<String> =
        listOf("cmd", "package", "query-activities", "--brief", "--user", user, "-a", action) +
            listOfNotNull(type?.let { "-t" }, type) + listOfNotNull(uri?.let { "-d" }, uri) +
            listOfNotNull(category?.let { "-c" }, category) + listOfNotNull(packageName?.let { "-p" }, packageName)

    /** Take (package name, full class name) for each page from the output; append the package name with the short class name starting with a dot. */
    fun parse(output: String): List<Pair<String, String>> = output.lineSequence().mapNotNull { line ->
        val m = COMPONENT.matchEntire(line.trim()) ?: return@mapNotNull null
        val (pkg, cls) = m.destructured
        pkg to if (cls.startsWith(".")) pkg + cls else cls
    }.distinct().toList()

    private val COMPONENT = Regex("""([A-Za-z][\w.]*)/([\w.$]+)""")
}
