package io.github.youndie.kesh.server.http

/**
 * A strict reader of the Prometheus text exposition format 0.0.4, written from the format's
 * specification rather than from kesh's writer: every sample must belong to a family whose `# TYPE`
 * came first, names and labels must be well formed, a histogram's buckets must be cumulative and end
 * in `+Inf` equal to its `_count`. Anything else throws.
 */
object PrometheusText {
    class Sample(
        val name: String,
        val labels: Map<String, String>,
        val value: Double,
    )

    private val metricName = Regex("[a-zA-Z_:][a-zA-Z0-9_:]*")
    private val labelName = Regex("[a-zA-Z_][a-zA-Z0-9_]*")
    private val types = setOf("counter", "gauge", "histogram", "summary", "untyped")

    /** Samples by family name. */
    fun parse(text: String): Map<String, List<Sample>> {
        require(text.endsWith("\n")) { "the exposition ends with a line feed" }
        val typeOf = LinkedHashMap<String, String>()
        val families = LinkedHashMap<String, MutableList<Sample>>()
        for (line in text.removeSuffix("\n").split('\n')) {
            when {
                line.isEmpty() -> {}

                line.startsWith("# TYPE ") -> {
                    val (name, type) = line.removePrefix("# TYPE ").split(' ', limit = 2)
                    require(metricName.matches(name)) { "bad family name: $line" }
                    require(type in types) { "bad type: $line" }
                    require(typeOf.put(name, type) == null) { "a second TYPE for $name" }
                    require(name !in families) { "TYPE for $name after its samples" }
                    families[name] = ArrayList()
                }

                line.startsWith("# HELP ") -> {
                    require(metricName.matches(line.removePrefix("# HELP ").substringBefore(' '))) { "bad HELP: $line" }
                }

                line.startsWith("#") -> {}

                else -> {
                    val sample = sample(line)
                    val family =
                        typeOf.keys.firstOrNull { f ->
                            sample.name == f ||
                                (
                                    typeOf[f] == "histogram" &&
                                        sample.name in listOf("${f}_bucket", "${f}_sum", "${f}_count")
                                )
                        } ?: error("a sample with no TYPE before it: $line")
                    families.getValue(family) += sample
                }
            }
        }
        for ((family, type) in typeOf) if (type == "histogram") checkHistogram(family, families.getValue(family))
        return families
    }

    private fun sample(line: String): Sample {
        val name = metricName.find(line)?.takeIf { it.range.first == 0 }?.value ?: error("bad sample: $line")
        var rest = line.substring(name.length)
        val labels = LinkedHashMap<String, String>()
        if (rest.startsWith("{")) {
            var i = 1
            while (rest[i] != '}') {
                val label = labelName.find(rest, i)?.takeIf { it.range.first == i }?.value ?: error("bad label: $line")
                i += label.length
                require(rest.substring(i).startsWith("=\"")) { "bad label: $line" }
                i += 2
                val value = StringBuilder()
                while (rest[i] != '"') {
                    if (rest[i] == '\\') {
                        i++
                        value.append(
                            when (rest[i]) {
                                'n' -> '\n'
                                '\\' -> '\\'
                                '"' -> '"'
                                else -> error("bad escape: $line")
                            },
                        )
                    } else {
                        value.append(rest[i])
                    }
                    i++
                }
                i++
                require(labels.put(label, value.toString()) == null) { "a repeated label: $line" }
                if (rest[i] == ',') i++
            }
            rest = rest.substring(i + 1)
        }
        require(rest.startsWith(" ")) { "no space before the value: $line" }
        val fields = rest.trim().split(' ')
        require(fields.size in 1..2) { "bad value: $line" }
        return Sample(name, labels, number(fields[0]) ?: error("bad value: $line"))
    }

    private fun number(text: String): Double? =
        when (text) {
            "+Inf" -> Double.POSITIVE_INFINITY
            "-Inf" -> Double.NEGATIVE_INFINITY
            "NaN" -> Double.NaN
            else -> text.toDoubleOrNull()
        }

    private fun checkHistogram(
        family: String,
        samples: List<Sample>,
    ) {
        val series = samples.groupBy { s -> s.labels.filterKeys { it != "le" } }
        for ((labels, group) in series) {
            val buckets =
                group
                    .filter { it.name == "${family}_bucket" }
                    .map { (number(it.labels.getValue("le")) ?: error("bad le")) to it.value }
            require(buckets.isNotEmpty()) { "$family$labels has no buckets" }
            require(buckets.zipWithNext().all { (a, b) -> a.first < b.first && a.second <= b.second }) {
                "$family$labels: buckets not ascending and cumulative"
            }
            require(buckets.last().first == Double.POSITIVE_INFINITY) { "$family$labels: no +Inf bucket" }
            val count = group.single { it.name == "${family}_count" }.value
            require(buckets.last().second == count) { "$family$labels: +Inf ${buckets.last().second} != count $count" }
            group.single { it.name == "${family}_sum" }
        }
    }
}
