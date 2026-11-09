package com.meenai.mina.about

/**
 * 版本号比较,按「主.次.修订[-预发布][+构建]」的习惯:开头的 v 去掉,数字段逐个比(缺的当 0),
 * 数字一样时正式版比带预发布后缀的新,两个都带后缀时按点分的各段比(数字按大小,文字按字典序,数字段排在文字段前)。
 * 构建后缀(+ 之后)不参与比较。
 */
object AppVersion {
    fun normalize(v: String): String = v.trim().removePrefix("v").removePrefix("V").trim()

    /**
     * Extracts the CI run number from a tag string.
     * Supported formats:
     * - "v0.1.48-127" -> 127L
     * - "0.1.48-127" -> 127L
     * - "v127" -> 127L
     * - "127" -> 127L
     * Semantic version tags without run numbers (e.g., "v0.1.48", "0.1.48") return null.
     */
    fun parseRunNumber(tag: String): Long? {
        val trimmed = tag.trim()
        if (trimmed.isEmpty()) return null
        if ('-' in trimmed) {
            val suffix = trimmed.substringAfterLast('-').trim()
            val num = suffix.toLongOrNull()
            if (num != null) return num
        }
        val withoutV = trimmed.removePrefix("v").removePrefix("V").trim()
        if ('.' !in withoutV) {
            return withoutV.toLongOrNull()
        }
        return null
    }

    /**
     * Compares remote run number with current versionCode.
     */
    fun isNewerRun(remoteTag: String, currentVersionCode: Long): Boolean {
        val remoteRun = parseRunNumber(remoteTag)
        return if (remoteRun != null && remoteRun > 0 && currentVersionCode > 0) {
            remoteRun > currentVersionCode
        } else {
            false
        }
    }

    /**
     * Checks if an update is available:
     * 1. If remote tag contains a valid run number and currentVersionCode > 0: compares run numbers.
     * 2. Otherwise falls back to semantic version comparison.
     */
    fun isUpdateAvailable(candidateTag: String, currentVersionName: String, currentVersionCode: Long = 0): Boolean {
        val remoteRun = parseRunNumber(candidateTag)
        if (remoteRun != null && remoteRun > 0 && currentVersionCode > 0) {
            return remoteRun > currentVersionCode
        }
        val candidateCore = normalize(candidateTag).substringBefore('-')
        return isNewer(candidateCore.ifEmpty { normalize(candidateTag) }, currentVersionName)
    }

    fun compare(a: String, b: String): Int {
        val (coreA, preA) = split(a)
        val (coreB, preB) = split(b)
        for (i in 0 until maxOf(coreA.size, coreB.size)) {
            val c = coreA.getOrElse(i) { 0 }.compareTo(coreB.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return when {
            preA == null && preB == null -> 0
            preA == null -> 1
            preB == null -> -1
            else -> comparePre(preA, preB)
        }
    }

    /** [candidate] 比 [current] 新。 */
    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    private fun split(v: String): Pair<List<Int>, String?> {
        val s = normalize(v).substringBefore('+')
        val core = s.substringBefore('-')
        val pre = if ('-' in s) s.substringAfter('-').takeIf { it.isNotEmpty() } else null
        val parts = core.split('.').map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        return parts to pre
    }

    private fun comparePre(a: String, b: String): Int {
        val pa = a.split('.')
        val pb = b.split('.')
        for (i in 0 until minOf(pa.size, pb.size)) {
            val na = pa[i].toIntOrNull()
            val nb = pb[i].toIntOrNull()
            val c = when {
                na != null && nb != null -> na.compareTo(nb)
                na != null -> -1
                nb != null -> 1
                else -> pa[i].compareTo(pb[i])
            }
            if (c != 0) return c
        }
        return pa.size.compareTo(pb.size)
    }
}
