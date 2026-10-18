"""Policy tests for the Gradle supply chain (repositories, wrapper checksum).

Stdlib-only text assertions over settings.gradle.kts, build.gradle.kts and
gradle/wrapper/gradle-wrapper.properties: no Gradle invocation, so this suite
stays fast and runs on any machine. Run with:

    python3 -I -m unittest discover -s security -p 'test_*.py' -v
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SETTINGS_GRADLE = ROOT / "settings.gradle.kts"
BUILD_GRADLE = ROOT / "build.gradle.kts"
WRAPPER_PROPERTIES = ROOT / "gradle" / "wrapper" / "gradle-wrapper.properties"


def _plugin_management_block(settings_text: str) -> str:
    """Return the body of the top-level `pluginManagement { ... }` block.

    Deliberately narrow (not a general brace parser): this file only has one
    top-level `pluginManagement` block, so scanning forward from its opening
    brace to the matching close is enough, and keeps the test readable.
    """
    start = settings_text.index("pluginManagement")
    brace_start = settings_text.index("{", start)
    depth = 0
    for i in range(brace_start, len(settings_text)):
        if settings_text[i] == "{":
            depth += 1
        elif settings_text[i] == "}":
            depth -= 1
            if depth == 0:
                return settings_text[brace_start : i + 1]
    raise AssertionError("unbalanced braces in pluginManagement block")


class RepositoryPolicyTests(unittest.TestCase):
    def test_no_aliyun_mirror(self) -> None:
        for path in (SETTINGS_GRADLE, BUILD_GRADLE):
            text = path.read_text(encoding="utf-8")
            self.assertNotIn(
                "maven.aliyun.com",
                text,
                f"{path} must not declare the aliyun mirror repository",
            )

    def test_no_maven_local(self) -> None:
        settings_text = SETTINGS_GRADLE.read_text(encoding="utf-8")
        plugin_management = _plugin_management_block(settings_text)
        self.assertNotIn(
            "mavenLocal()",
            plugin_management,
            "pluginManagement must not declare an unconditional mavenLocal()",
        )
        # The conditional InnerTubeX-from-mavenLocal path is allowed to
        # remain in dependencyResolutionManagement, but only gated behind
        # the explicit useMavenLocalInnerTubeX property.
        self.assertIn("useMavenLocalInnerTubeX", settings_text)

    def test_jitpack_is_content_filtered(self) -> None:
        settings_text = SETTINGS_GRADLE.read_text(encoding="utf-8")
        build_text = BUILD_GRADLE.read_text(encoding="utf-8")

        # build.gradle.kts' buildscript classpath deps (the Android Gradle
        # Plugin and the Kotlin Gradle plugin) come from google()/
        # mavenCentral()/gradlePluginPortal() only; JitPack has no business
        # being a buildscript repository.
        self.assertNotIn("jitpack.io", build_text)

        # The generic JitPack entry in settings.gradle.kts must be
        # restricted to the exact groups verification showed genuinely
        # resolve from it (ucrop and tinypinyin's real engine module);
        # InnerTubeX already has its own exclusiveContent filter above
        # this one.
        jitpack_entry = re.search(
            r'maven\s*\{\s*setUrl\("https://jitpack\.io"\)(.*?)\}\s*\}',
            settings_text,
            re.DOTALL,
        )
        self.assertIsNotNone(
            jitpack_entry, "expected a generic maven { setUrl(\"https://jitpack.io\") } entry"
        )
        body = jitpack_entry.group(1)
        self.assertIn("content", body)
        self.assertIn('includeGroup("com.github.yalantis")', body)
        self.assertIn('includeGroup("com.github.promeG")', body)
        self.assertIn('includeGroup("com.github.promeG.tinypinyin")', body)

    def test_tinypinyin_pinned_to_a_live_jitpack_build(self) -> None:
        # Upstream's 2.0.3 is dead on JitPack under every coordinate spelling
        # (jitpack.io/api/builds/com.github.promeG/tinypinyin reports status
        # "Error" for 2.0.3/v2.0.3/master, "ok" only for 2.0.1) — restricting
        # JitPack's content (this task's purpose) forces a fresh resolution
        # that 2.0.3 can never satisfy again. 2.0.1 is pinned instead; its
        # only functional difference is a cncity lexicon data fix, not a
        # change to the Pinyin.toPinyin(Char) API this app actually calls.
        toml_text = (ROOT / "gradle" / "libs.versions.toml").read_text(encoding="utf-8")
        match = re.search(r'tinypinyin\s*=\s*"([^"]+)"', toml_text)
        self.assertIsNotNone(match, "expected a tinypinyin version pin in libs.versions.toml")
        self.assertEqual("2.0.1", match.group(1))

    def test_wrapper_has_sha256(self) -> None:
        text = WRAPPER_PROPERTIES.read_text(encoding="utf-8")
        match = re.search(r"^distributionSha256Sum=([0-9a-fA-F]{64})$", text, re.MULTILINE)
        self.assertIsNotNone(
            match, "gradle-wrapper.properties must pin distributionSha256Sum to a sha256 hex digest"
        )


if __name__ == "__main__":
    unittest.main()
