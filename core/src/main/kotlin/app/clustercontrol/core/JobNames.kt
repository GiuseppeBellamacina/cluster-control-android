package app.clustercontrol.core

data class JobTitle(val type: String?, val title: String, val subtitle: String)

/** Readable titles for job names / cell tags such as `train-conll-2003-qwen25-15b-grpo-zero-shot`. */
object JobNames {
    private val DATASETS = listOf(
        "aslg-pc12" to "ASLG-PC12",
        "phoenix-2014t" to "PHOENIX-2014T",
        "wos-46985" to "WOS-46985",
        "conll-2003" to "CoNLL-2003",
    )
    private val MODEL = Regex("""qwen25-(\d+)b""")
    private val PROMPTING = listOf("zero-shot", "few-shot")

    private fun model(digits: String?) = "Qwen2.5 " + when (digits) {
        null, "05" -> "0.5B"
        "15" -> "1.5B"
        else -> digits.trimStart('0') + "B"
    }

    private fun approach(raw: String): String = when (raw) {
        "sft-grpo" -> "SFT + GRPO"
        "grpo" -> "GRPO"
        "sft" -> "SFT"
        "baseline" -> "Baseline"
        else -> raw.replace('-', ' ').replaceFirstChar { it.uppercase() }
    }

    fun parse(name: String): JobTitle {
        var rest = name
        var type: String? = null
        for (t in listOf("train", "eval")) {
            if (rest.startsWith("$t-")) { type = t; rest = rest.removePrefix("$t-") }
        }
        val ds = DATASETS.firstOrNull { rest == it.first || rest.startsWith(it.first + "-") }
            ?: return JobTitle(type, rest, "")
        rest = rest.removePrefix(ds.first).removePrefix("-")
        var digits: String? = null
        MODEL.find(rest)?.takeIf { it.range.first == 0 }?.let {
            digits = it.groupValues[1]
            rest = rest.substring(it.range.last + 1).removePrefix("-")
        }
        val title = "${ds.second} · ${model(digits)}"
        val subtitle = if (rest.startsWith("ablations-")) {
            val parts = rest.removePrefix("ablations-").split('-', limit = 2)
            "Ablation · " + parts.joinToString(" · ")
        } else {
            val p = PROMPTING.firstOrNull { rest == it || rest.endsWith("-$it") || rest.contains("-$it-") }
            if (p == null) approach(rest) else {
                val head = rest.substringBefore(p).removeSuffix("-")
                val tail = rest.substringAfter(p).removePrefix("-").replace('-', ' ')
                listOfNotNull(head.takeIf { it.isNotEmpty() }?.let(::approach), p, tail.takeIf { it.isNotEmpty() })
                    .joinToString(" · ")
            }
        }
        return JobTitle(type, title, subtitle)
    }

    /** Title of a queue entry (its tag is the cell tag). */
    fun of(entry: JobEntry): JobTitle = parse(entry.tag).copy(type = entry.type)
}
