package de.takt.ui

/** Never more than two bars, and only pair bars when both beat rows fit without clipping. */
internal fun patternRows(counts: List<Int>, width: Float, beatWidth: Float): List<List<Int>> {
    val rows = mutableListOf<List<Int>>()
    val half = (width - 8f) / 2f
    var index = 0
    while (index < counts.size) {
        val pair = index + 1 < counts.size && counts[index] * beatWidth + 18f <= half &&
            counts[index + 1] * beatWidth + 18f <= half
        rows += if (pair) listOf(index, index + 1) else listOf(index)
        index += if (pair) 2 else 1
    }
    return rows
}
