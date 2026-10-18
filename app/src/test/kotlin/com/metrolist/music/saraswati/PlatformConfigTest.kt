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
    fun `no network security config anywhere trusts user CAs or has debug-overrides`() {
        // SARASWATI: "no user-installed CAs, ever, in any build type" is one
        // of this fork's Global Constraints — this directly tests that
        // guarantee against the actual XML, instead of relying on nobody
        // ever adding it back. Globs every network_security_config.xml
        // under src/*/res/xml/ (today there's exactly one, in main) rather
        // than hardcoding that one path, so a future variant-specific file
        // (e.g. src/debug/res/xml/) can't quietly reintroduce Android
        // Studio's default new-project template, which adds a
        // <debug-overrides><trust-anchors><certificates src="user"/>...
        // block that trusts user CAs in debug builds — exactly what this
        // fork's network story must never do, in any build variant.
        val configFiles =
            File("src")
                .walkTopDown()
                .filter { it.isFile && it.parentFile?.name == "xml" && it.name == "network_security_config.xml" }
                .toList()
        assertTrue("expected at least one network_security_config.xml under src/*/res/xml/", configFiles.isNotEmpty())

        for (file in configFiles) {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)

            assertEquals(
                "${file.path} must not have a <debug-overrides> block " +
                    "(it would trust user-installed CAs in debug builds)",
                0,
                doc.getElementsByTagName("debug-overrides").length,
            )

            val certificates = doc.getElementsByTagName("certificates")
            for (i in 0 until certificates.length) {
                val cert = certificates.item(i) as Element
                assertTrue(
                    "${file.path} has <certificates src=\"${cert.getAttribute("src")}\"> — " +
                        "src=\"user\" must never appear anywhere in this file",
                    cert.getAttribute("src") != "user",
                )
            }
        }
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
