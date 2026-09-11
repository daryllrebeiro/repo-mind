package dev.repomind.core.index

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PolyrepoFederationTest {

    private fun createCatalogRepo(): Path {
        val root = Files.createTempDirectory("service-catalog")
        root.resolve("pom.xml").writeText(
            """<project><modelVersion>4.0.0</modelVersion>
               <groupId>com.example</groupId><artifactId>service-catalog</artifactId><version>1.0.0</version>
               </project>""".trimIndent(),
        )
        val controllerFile = root.resolve("src/main/java/com/catalog/CatalogController.java")
        Files.createDirectories(controllerFile.parent)
        controllerFile.writeText(
            """
            package com.catalog;

            import org.springframework.web.bind.annotation.RestController;
            import org.springframework.web.bind.annotation.RequestMapping;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.PostMapping;

            @RestController
            @RequestMapping("/catalog")
            public class CatalogController {

                @GetMapping("/{id}")
                public String getItem(String id) {
                    return "item-" + id;
                }

                @PostMapping
                public void createItem() {
                }
            }
            """.trimIndent(),
        )
        return root
    }

    private fun createOrderRepo(): Path {
        val root = Files.createTempDirectory("service-order")
        root.resolve("pom.xml").writeText(
            """<project><modelVersion>4.0.0</modelVersion>
               <groupId>com.example</groupId><artifactId>service-order</artifactId><version>1.0.0</version>
               </project>""".trimIndent(),
        )
        val clientFile = root.resolve("src/main/java/com/order/CatalogClient.java")
        Files.createDirectories(clientFile.parent)
        clientFile.writeText(
            """
            package com.order;

            import org.springframework.cloud.openfeign.FeignClient;
            import org.springframework.web.bind.annotation.GetMapping;

            @FeignClient(name = "catalog-service", path = "/catalog")
            public interface CatalogClient {

                @GetMapping("/{id}")
                String getCatalogItem(String id);
            }
            """.trimIndent(),
        )

        val serviceFile = root.resolve("src/main/java/com/order/OrderService.java")
        serviceFile.writeText(
            """
            package com.order;

            public class OrderService {
                private CatalogClient client;

                public String processOrder(String itemId) {
                    return client.getCatalogItem(itemId);
                }
            }
            """.trimIndent(),
        )
        return root
    }

    @Test
    fun `federates catalog and order microservices linking Feign client to REST controller`() {
        val catalogRoot = createCatalogRepo()
        val orderRoot = createOrderRepo()

        // Index both repositories into their local .repomind/index.db
        IncrementalIndexer(catalogRoot.resolve(".repomind/index.db")).update(catalogRoot)
        IncrementalIndexer(orderRoot.resolve(".repomind/index.db")).update(orderRoot)

        val engine = PolyrepoFederationEngine()
        val report = engine.federate(listOf(catalogRoot, orderRoot))

        // Check linked repos
        assertEquals(2, report.linkedRepos.size)
        assertTrue(report.linkedRepos.any { it.contains("catalog") })
        assertTrue(report.linkedRepos.any { it.contains("order") })

        // Check endpoints
        assertTrue(report.totalEndpointsDiscovered >= 3)

        // Check cross-repo link
        val link = report.crossRepoLinks.firstOrNull { it.path == "/catalog/{*}" || it.path == "/catalog/{id}" }
        assertNotNull(link, "Expected cross-repo link between Feign client and Controller")
        assertTrue(link.clientRepo.contains("order"))
        assertEquals("com.order.CatalogClient#getCatalogItem", link.clientFqn)
        assertTrue(link.targetRepo.contains("catalog"))
        assertEquals("com.catalog.CatalogController#getItem", link.targetFqn)
        assertEquals("GET", link.httpMethod)

        // Check blast radius
        assertTrue(report.crossRepoBlastRadii.isNotEmpty())
        val blast = report.crossRepoBlastRadii.first { it.targetSymbol.contains("getItem") }
        assertTrue(blast.callingRepos.any { it.contains("order") })
        assertTrue(blast.callingClients.contains("com.order.CatalogClient#getCatalogItem"))

        // Check Markdown output
        val markdown = report.toMarkdown()
        assertTrue(markdown.contains("Enterprise Polyrepo Federation Report"))
        assertTrue(markdown.contains("com.order.CatalogClient#getCatalogItem"))
        assertTrue(markdown.contains("com.catalog.CatalogController#getItem"))
    }
}
