package dev.repomind.core.jdk

enum class JdkVersion(val major: Int, val displayName: String) {
    JDK_25(25, "Java 25"),
    JDK_21(21, "Java 21"),
    JDK_17(17, "Java 17"),
    JDK_11(11, "Java 11"),
    UNKNOWN(0, "Unknown");

    companion object {
        fun fromMajor(major: Int): JdkVersion {
            return values().find { it.major == major } ?: UNKNOWN
        }

        fun fromString(version: String): JdkVersion {
            val major = version.extractMajorVersion()
            return fromMajor(major)
        }

        private fun String.extractMajorVersion(): Int {
            return Regex("(\\d+)").find(this)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }
    }
}