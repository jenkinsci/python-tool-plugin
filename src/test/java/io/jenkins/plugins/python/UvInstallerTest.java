package io.jenkins.plugins.python;

import hudson.FilePath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UvInstallerTest {

    @Test
    void testNormalizeVersion() {
        assertEquals("latest", UvInstaller.normalizeVersion(null));
        assertEquals("latest", UvInstaller.normalizeVersion(""));
        assertEquals("latest", UvInstaller.normalizeVersion("   "));
        assertEquals("latest", UvInstaller.normalizeVersion("latest"));
        assertEquals("latest", UvInstaller.normalizeVersion("LATEST"));
        assertEquals("0.13.1", UvInstaller.normalizeVersion("0.13.1"));
        assertEquals("0.13.1", UvInstaller.normalizeVersion("v0.13.1"));
        assertEquals("0.13.1", UvInstaller.normalizeVersion("V0.13.1"));
    }

    @Test
    void testGetArchiveFilename() {
        assertEquals("uv-x86_64-unknown-linux-gnu.tar.gz", UvInstaller.getArchiveFilename("linux-x86_64"));
        assertEquals("uv-x86_64-unknown-linux-musl.tar.gz", UvInstaller.getArchiveFilename("linux-x86_64-musl"));
        assertEquals("uv-aarch64-unknown-linux-gnu.tar.gz", UvInstaller.getArchiveFilename("linux-aarch64"));
        assertEquals("uv-aarch64-unknown-linux-musl.tar.gz", UvInstaller.getArchiveFilename("linux-aarch64-musl"));
        assertEquals("uv-armv7-unknown-linux-gnueabihf.tar.gz", UvInstaller.getArchiveFilename("linux-armv7"));
        assertEquals("uv-powerpc64le-unknown-linux-gnu.tar.gz", UvInstaller.getArchiveFilename("linux-ppc64le"));
        assertEquals("uv-s390x-unknown-linux-gnu.tar.gz", UvInstaller.getArchiveFilename("linux-s390x"));
        assertEquals("uv-x86_64-apple-darwin.tar.gz", UvInstaller.getArchiveFilename("macos-x86_64"));
        assertEquals("uv-aarch64-apple-darwin.tar.gz", UvInstaller.getArchiveFilename("macos-aarch64"));
        assertEquals("uv-x86_64-pc-windows-msvc.zip", UvInstaller.getArchiveFilename("windows-x86_64"));
        assertEquals("uv-aarch64-pc-windows-msvc.zip", UvInstaller.getArchiveFilename("windows-aarch64"));
        assertEquals("uv-i686-pc-windows-msvc.zip", UvInstaller.getArchiveFilename("windows-x86"));

        assertThrows(IllegalArgumentException.class, () -> UvInstaller.getArchiveFilename("unsupported-platform"));
    }

    @Test
    void testBuildDownloadUrl() {
        assertEquals(
                "https://github.com/astral-sh/uv/releases/latest/download/uv-x86_64-unknown-linux-gnu.tar.gz",
                UvInstaller.buildDownloadUrl("latest", "linux-x86_64", null)
        );
        assertEquals(
                "https://github.com/astral-sh/uv/releases/download/0.13.1/uv-x86_64-pc-windows-msvc.zip",
                UvInstaller.buildDownloadUrl("v0.13.1", "windows-x86_64", null)
        );
        assertEquals(
                "https://mirror.corp.internal/uv/uv-aarch64-apple-darwin.tar.gz",
                UvInstaller.buildDownloadUrl("latest", "macos-aarch64", "https://mirror.corp.internal/uv")
        );
        assertEquals(
                "https://mirror.corp.internal/uv/0.13.1/uv-aarch64-apple-darwin.tar.gz",
                UvInstaller.buildDownloadUrl("0.13.1", "macos-aarch64", "https://mirror.corp.internal/uv/")
        );
    }

    @Test
    void testFindExistingUvBinDir(@TempDir File tempDir) throws Exception {
        FilePath root = new FilePath(tempDir);
        assertNull(UvInstaller.findExistingUvBinDir(root));

        FilePath unixPy = root.child("unix-py");
        unixPy.child("bin").mkdirs();
        unixPy.child("bin").child("uv").write("mock-uv", "UTF-8");
        FilePath foundUnix = UvInstaller.findExistingUvBinDir(unixPy);
        assertNotNull(foundUnix);
        assertEquals(unixPy.child("bin").getRemote(), foundUnix.getRemote());

        FilePath winPy = root.child("win-py");
        winPy.child("Scripts").mkdirs();
        winPy.child("Scripts").child("uv.exe").write("mock-uv-exe", "UTF-8");
        FilePath foundWin = UvInstaller.findExistingUvBinDir(winPy);
        assertNotNull(foundWin);
        assertEquals(winPy.child("Scripts").getRemote(), foundWin.getRemote());
    }

    @Test
    void testFlattenAndChmodBinaries(@TempDir File tempDir) throws Exception {
        FilePath installDir = new FilePath(tempDir);
        FilePath nested = installDir.child("uv-x86_64-unknown-linux-gnu");
        nested.mkdirs();
        nested.child("uv").write("binary-uv", "UTF-8");
        nested.child("uvx").write("binary-uvx", "UTF-8");

        UvInstaller.flattenAndChmodBinaries(installDir, true);

        assertTrue(installDir.child("uv").exists());
        assertTrue(installDir.child("uvx").exists());
        assertEquals("binary-uv", installDir.child("uv").readToString());
        assertEquals("binary-uvx", installDir.child("uvx").readToString());
    }
}
