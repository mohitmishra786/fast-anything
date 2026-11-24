package demo

// Fuzzy string matching helpers.

/** Edit distance: how many inserts, deletes or substitutions turn a into b. */
fun levenshtein(a: String, b: String): Int {
    val (long, short) = if (a.length >= b.length) a to b else b to a
    var previous = IntArray(short.length + 1) { it }
    for (i in long.indices) {
        val current = IntArray(short.length + 1)
        current[0] = i + 1
        for (j in short.indices) {
            current[j + 1] = minOf(previous[j + 1] + 1, current[j] + 1, previous[j] + if (long[i] == short[j]) 0 else 1)
        }
        previous = current
    }
    return previous[short.length]
}

fun normalize(name: String): String = name.lowercase().split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }.joinToString(" ")

/** Index pairs of names within maxDistance edits of each other. */
fun findDuplicates(names: List<String>, maxDistance: Int = 2): List<List<Int>> =
    names.map { normalize(it) }
        .let { clean ->
            clean.indices.flatMap { i ->
                (i + 1 until clean.size).filter { j -> levenshtein(clean[i], clean[j]) <= maxDistance }.map { j -> listOf(i, j) }
            }
        }
