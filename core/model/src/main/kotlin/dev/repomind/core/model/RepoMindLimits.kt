package dev.repomind.core.model

/**
 * Central repository for all tunable threshold constants across the RepoMind codebase.
 *
 * Keep every "magic number" here and reference it by name everywhere else.
 * Changing a value in one place propagates to all consumers.
 */
object RepoMindLimits {
    /** Maximum number of Maven/Gradle sub-modules collected in a single repository scan. */
    const val MAX_MODULES: Int = 200

    /** Safety ceiling on in-memory graph nodes to prevent OOM on pathological repos. */
    const val MAX_GRAPH_NODES: Int = 100_000

    /** Default maximum traversal depth for caller/callee graph queries. */
    const val DEFAULT_GRAPH_DEPTH: Int = Int.MAX_VALUE

    /** Maximum number of unresolved symbols included in parse output for logging/reporting. */
    const val MAX_UNRESOLVED_LOG: Int = 20

    /** Maximum number of config properties included in a ConfigGraph DTO for display. */
    const val MAX_CONFIG_PROPERTIES_DISPLAY: Int = 50

    /** Maximum number of symbols returned by a name-prefix search query. */
    const val DEFAULT_SYMBOL_SEARCH_LIMIT: Int = 100
}
