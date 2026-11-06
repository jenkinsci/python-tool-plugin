package io.jenkins.plugins.python;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PythonVersionCatalogTest {

    @Test
    void testCatalogLoadsVersionsAndPreservesHistory() {
        PythonVersionCatalog catalog = PythonVersionCatalog.getInstance();
        List<String> versions = catalog.getAvailableVersions();

        assertNotNull(versions);
        assertFalse(versions.isEmpty(), "Catalog should contain Python versions");
        assertTrue(versions.size() >= 15, "Catalog should retain historical and current Python versions");
        assertTrue(versions.contains("3.14.7"), "Catalog should preserve older patch release 3.14.7");
        assertTrue(versions.contains("3.13.15"), "Catalog should preserve older patch release 3.13.15");
        assertTrue(versions.contains("3.12.14"), "Catalog should preserve older patch release 3.12.14");
    }

    @Test
    void testCatalogLoadsUvVersions() {
        PythonVersionCatalog catalog = PythonVersionCatalog.getInstance();
        List<String> uvVersions = catalog.getUvVersions();

        assertNotNull(uvVersions);
        assertFalse(uvVersions.isEmpty(), "Catalog should contain Astral uv versions");
        assertTrue(uvVersions.size() >= 10, "Catalog should contain multiple Astral uv versions");
    }

    @Test
    void testDefaultRemoteUrlTargetsOfficialCatalogBranch() {
        assertEquals(
                "https://raw.githubusercontent.com/jenkinsci/python-tool-plugin/catalog/versions.json",
                PythonVersionCatalog.DEFAULT_REMOTE_URL
        );
    }

    @Test
    void testResolveVersion() {
        PythonVersionCatalog catalog = PythonVersionCatalog.getInstance();

        String latest = catalog.resolveVersion("latest");
        assertNotNull(latest);
        assertFalse(latest.isEmpty());

        String py312 = catalog.resolveVersion("3.12");
        assertNotNull(py312);
        assertTrue(py312.startsWith("3.12."), "3.12 prefix should resolve to 3.12.x");

        String py313 = catalog.resolveVersion("3.13");
        assertNotNull(py313);
        assertTrue(py313.startsWith("3.13."), "3.13 prefix should resolve to 3.13.x");
    }

    @Test
    void testDownloadsContainMajorPlatforms() {
        PythonVersionCatalog catalog = PythonVersionCatalog.getInstance();
        String latest = catalog.getLatestVersion();

        PythonVersionCatalog.PlatformDownload linuxX64 = catalog.getDownload(latest, "linux-x86_64");
        assertNotNull(linuxX64, "Missing linux-x86_64 for " + latest);
        assertTrue(linuxX64.getUrl().contains("linux-gnu"), "URL should target linux-gnu");

        PythonVersionCatalog.PlatformDownload macArm64 = catalog.getDownload(latest, "macos-aarch64");
        assertNotNull(macArm64, "Missing macos-aarch64 for " + latest);
        assertTrue(macArm64.getUrl().contains("apple-darwin"), "URL should target apple-darwin");

        PythonVersionCatalog.PlatformDownload winX64 = catalog.getDownload(latest, "windows-x86_64");
        assertNotNull(winX64, "Missing windows-x86_64 for " + latest);
        assertTrue(winX64.getUrl().contains("windows-msvc"), "URL should target windows-msvc");

        PythonVersionCatalog.PlatformDownload winArm64 = catalog.getDownload(latest, "windows-aarch64");
        assertNotNull(winArm64, "Missing windows-aarch64 for " + latest);
        assertTrue(winArm64.getUrl().contains("aarch64-pc-windows-msvc"), "URL should target aarch64 windows");

        PythonVersionCatalog.PlatformDownload ppc64le = catalog.getDownload(latest, "linux-ppc64le");
        assertNotNull(ppc64le, "Missing linux-ppc64le for " + latest);

        PythonVersionCatalog.PlatformDownload s390x = catalog.getDownload(latest, "linux-s390x");
        assertNotNull(s390x, "Missing linux-s390x for " + latest);

        PythonVersionCatalog.PlatformDownload musl = catalog.getDownload(latest, "linux-x86_64-musl");
        assertNotNull(musl, "Missing linux-x86_64-musl for " + latest);
    }

    @Test
    void testAirGappedFileOverride(@TempDir Path tempDir) throws Exception {
        String customJson = "{\n" +
                "  \"versions\": {\n" +
                "    \"3.99.1\": {\n" +
                "      \"releaseTag\": \"20990101\",\n" +
                "      \"platforms\": {\n" +
                "        \"linux-x86_64\": {\n" +
                "          \"url\": \"https://internal.repo/cpython-3.99.1.tar.gz\",\n" +
                "          \"size\": 12345,\n" +
                "          \"filename\": \"cpython-3.99.1.tar.gz\"\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "  },\n" +
                "  \"uvVersions\": [\"0.99.1\", \"0.99.0\"]\n" +
                "}";

        Path overrideFile = tempDir.resolve("custom-versions.json");
        Files.writeString(overrideFile, customJson);

        String prev = System.getProperty("io.jenkins.plugins.python.versionsFile");
        try {
            System.setProperty("io.jenkins.plugins.python.versionsFile", overrideFile.toString());
            PythonVersionCatalog catalog = PythonVersionCatalog.getInstance();
            catalog.reload();

            assertTrue(catalog.getAvailableVersions().contains("3.99.1"));
            assertEquals("3.99.1", catalog.resolveVersion("3.99"));
            assertEquals(List.of("0.99.1", "0.99.0"), catalog.getUvVersions());
            PythonVersionCatalog.PlatformDownload dl = catalog.getDownload("3.99.1", "linux-x86_64");
            assertNotNull(dl);
            assertEquals("https://internal.repo/cpython-3.99.1.tar.gz", dl.getUrl());
        } finally {
            if (prev != null) {
                System.setProperty("io.jenkins.plugins.python.versionsFile", prev);
            } else {
                System.clearProperty("io.jenkins.plugins.python.versionsFile");
            }
            PythonVersionCatalog.getInstance().reload();
        }
    }
}
