package dev.repomind.core.config

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigFileParserTest {

    @Test
    fun `flattens nested yaml to dotted keys`() {
        val yaml = """
            spring:
              datasource:
                url: jdbc:postgresql://localhost:5432/app
                username: app
            server:
              port: 8080
        """.trimIndent()

        val flat = ConfigFileParser.flattenYaml(yaml)

        assertEquals(
            mapOf(
                "spring.datasource.url" to "jdbc:postgresql://localhost:5432/app",
                "spring.datasource.username" to "app",
                "server.port" to "8080",
            ),
            flat,
        )
    }

    @Test
    fun `flattens yaml lists with index notation`() {
        val yaml = """
            mail:
              servers:
                - host: a.example.com
                  port: 25
                - host: b.example.com
                  port: 587
        """.trimIndent()

        val flat = ConfigFileParser.flattenYaml(yaml)

        assertEquals("a.example.com", flat["mail.servers[0].host"])
        assertEquals("587", flat["mail.servers[1].port"])
    }

    @Test
    fun `parses properties files`() {
        val props = "app.timeout=30\napp.retries=3\n"

        val flat = ConfigFileParser.flattenProperties(props)

        assertEquals(mapOf("app.timeout" to "30", "app.retries" to "3"), flat)
    }

    @Test
    fun `empty yaml yields empty map`() {
        assertTrue(ConfigFileParser.flattenYaml("").isEmpty())
    }
}
