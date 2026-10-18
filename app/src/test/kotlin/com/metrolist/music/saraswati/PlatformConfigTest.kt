/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

// SARASWATI: regression tests for the platform-config XML files (network
// security config, backup/device-transfer exclusions, FileProvider paths).
// allowBackup=false (Task 4, gms flavor) already stops cloud backup on
// targetSdk 36, but it does NOT stop phone-to-phone device transfer, which
// would otherwise copy the sign-in WebView's cookie jar under app_webview/
// (audit NEW-3). These XML rules are the second layer if allowBackup is
// ever flipped back on.
package com.metrolist.music.saraswati

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

private fun parseXml(path: String): Document {
    val file = File(path)
    assertTrue("missing $file (working dir ${File(".").absolutePath})", file.isFile)
    return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
}

private fun directChildren(
    parent: Element,
    tagName: String,
): List<Element> {
    val result = mutableListOf<Element>()
    val children = parent.childNodes
    for (i in 0 until children.length) {
        val node = children.item(i)
        if (node is Element && node.tagName == tagName) {
            result.add(node)
        }
    }
    return result
}

private fun assertAllDomainsExcluded(
    parent: Element,
    domains: Set<String>,
) {
    val excludes = directChildren(parent, "exclude")
    for (domain in domains) {
        val matching = excludes.filter { it.getAttribute("domain") == domain }
        assertTrue(
            "no <exclude domain=\"$domain\"/> under <${parent.tagName}>",
            matching.isNotEmpty(),
        )
        assertTrue(
            "<exclude domain=\"$domain\"/> under <${parent.tagName}> must use path=\".\" " +
                "(excludes the whole domain)",
            matching.any { it.getAttribute("path") == "." },
        )
    }
}

class PlatformConfigTest {
    @Test
    fun `cleartext is not permitted anywhere`() {
        val doc = parseXml("src/main/res/xml/network_security_config.xml")

        val baseConfigs = doc.getElementsByTagName("base-config")
        assertEquals("expected exactly one base-config", 1, baseConfigs.length)
        val base = baseConfigs.item(0) as Element
        assertEquals("false", base.getAttribute("cleartextTrafficPermitted"))

        val domainConfigs = doc.getElementsByTagName("domain-config")
        for (i in 0 until domainConfigs.length) {
            val domainConfig = domainConfigs.item(i) as Element
            assertTrue(
                "domain-config #$i permits cleartext",
                domainConfig.getAttribute("cleartextTrafficPermitted") != "true",
            )
        }
    }

    @Test
    fun `backups and device transfer exclude every domain`() {
        val coreDomains = setOf("root", "file", "database", "sharedpref", "external")

        val backupRules = parseXml("src/main/res/xml/backup_rules.xml")
        assertAllDomainsExcluded(backupRules.documentElement, coreDomains)

        val dataExtraction = parseXml("src/main/res/xml/data_extraction_rules.xml")
        val cloudBackup = directChildren(dataExtraction.documentElement, "cloud-backup").single()
        val deviceTransfer = directChildren(dataExtraction.documentElement, "device-transfer").single()

        assertAllDomainsExcluded(cloudBackup, coreDomains)
        assertAllDomainsExcluded(
            deviceTransfer,
            coreDomains + setOf("device_root", "device_file", "device_database", "device_sharedpref"),
        )
    }

    @Test
    fun `file provider exposes only named subdirectories`() {
        val doc = parseXml("src/main/res/xml/provider_paths.xml")
        val root = doc.documentElement
        val children = root.childNodes

        var sawAnyPath = false
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node !is Element) continue
            sawAnyPath = true

            assertTrue("<root-path> must not be used", node.tagName != "root-path")
            assertTrue("<external-path> must not be used", node.tagName != "external-path")
            assertTrue("<external-cache-path> must not be used", node.tagName != "external-cache-path")
            assertTrue(
                "<${node.tagName} name=\"${node.getAttribute("name")}\"> must not expose path=\".\"",
                node.getAttribute("path") != ".",
            )
        }
        assertTrue("provider_paths.xml declared no paths at all", sawAnyPath)
    }
}
