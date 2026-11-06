package io.jenkins.plugins.python;

import hudson.FilePath;
import hudson.ProxyConfiguration;
import hudson.Util;
import hudson.model.Node;
import hudson.model.TaskListener;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.net.URL;
import java.net.URLConnection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provisions standalone Astral {@code uv} (and {@code uvx}) binaries across Linux, macOS,
 * and Windows agents, supporting version pinning, local agent tool caching, and corporate mirrors.
 */
public final class UvInstaller implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String DEFAULT_UV_BASE_URL = "https://github.com/astral-sh/uv/releases";
    private static final String MARKER_FILENAME = ".installed-uv-version";

    private static final Map<String, String> PLATFORM_ARCHIVES;

    static {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("linux-x86_64", "uv-x86_64-unknown-linux-gnu.tar.gz");
        map.put("linux-x86_64-musl", "uv-x86_64-unknown-linux-musl.tar.gz");
        map.put("linux-aarch64", "uv-aarch64-unknown-linux-gnu.tar.gz");
        map.put("linux-aarch64-musl", "uv-aarch64-unknown-linux-musl.tar.gz");
        map.put("linux-armv7", "uv-armv7-unknown-linux-gnueabihf.tar.gz");
        map.put("linux-ppc64le", "uv-powerpc64le-unknown-linux-gnu.tar.gz");
        map.put("linux-s390x", "uv-s390x-unknown-linux-gnu.tar.gz");
        map.put("macos-x86_64", "uv-x86_64-apple-darwin.tar.gz");
        map.put("macos-aarch64", "uv-aarch64-apple-darwin.tar.gz");
        map.put("windows-x86_64", "uv-x86_64-pc-windows-msvc.zip");
        map.put("windows-aarch64", "uv-aarch64-pc-windows-msvc.zip");
        map.put("windows-x86", "uv-i686-pc-windows-msvc.zip");
        PLATFORM_ARCHIVES = Collections.unmodifiableMap(map);
    }

    private UvInstaller() {
    }

    /**
     * Normalizes a user-supplied uv version string (e.g. null, "", "latest", "v0.13.1" -> "0.13.1").
     */
    public static String normalizeVersion(String uvVersion) {
        String clean = Util.fixEmptyAndTrim(uvVersion);
        if (clean == null || "latest".equalsIgnoreCase(clean)) {
            return "latest";
        }
        if ((clean.startsWith("v") || clean.startsWith("V")) && clean.length() > 1 && Character.isDigit(clean.charAt(1))) {
            return clean.substring(1);
        }
        return clean;
    }

    /**
     * Returns the archive filename for a given platformKey (from {@link PlatformUtils#detectPlatformKey}).
     */
    public static String getArchiveFilename(String platformKey) {
        String filename = PLATFORM_ARCHIVES.get(platformKey);
        if (filename == null) {
            throw new IllegalArgumentException("[Python Plugin] Unsupported platform for Astral uv: " + platformKey);
        }
        return filename;
    }

    /**
     * Builds the download URL for the uv archive, honoring custom mirror overrides or system properties.
     */
    public static String buildDownloadUrl(String uvVersion, String platformKey, String customMirrorUrl) {
        String version = normalizeVersion(uvVersion);
        String filename = getArchiveFilename(platformKey);

        String effectiveMirror = Util.fixEmptyAndTrim(customMirrorUrl);
        if (effectiveMirror == null) {
            effectiveMirror = Util.fixEmptyAndTrim(System.getProperty("io.jenkins.plugins.python.uvMirrorUrl"));
        }

        if (effectiveMirror != null) {
            String base = effectiveMirror.endsWith("/") ? effectiveMirror : effectiveMirror + "/";
            if ("latest".equals(version) || base.endsWith("/" + version + "/")) {
                return base + filename;
            }
            return base + version + "/" + filename;
        }

        if ("latest".equals(version)) {
            return DEFAULT_UV_BASE_URL + "/latest/download/" + filename;
        }
        return DEFAULT_UV_BASE_URL + "/download/" + version + "/" + filename;
    }

    /**
     * Checks whether an existing Python installation directory already contains a pre-installed {@code uv} binary.
     */
    public static FilePath findExistingUvBinDir(FilePath pythonHome) throws IOException, InterruptedException {
        if (pythonHome == null || !pythonHome.exists()) {
            return null;
        }
        FilePath unixBin = pythonHome.child("bin");
        if (unixBin.child("uv").exists() || unixBin.child("uv.exe").exists()) {
            return unixBin;
        }
        FilePath winScripts = pythonHome.child("Scripts");
        if (winScripts.child("uv.exe").exists() || winScripts.child("uv").exists()) {
            return winScripts;
        }
        if (pythonHome.child("uv.exe").exists() || pythonHome.child("uv").exists()) {
            return pythonHome;
        }
        return null;
    }

    /**
     * Resolves the executable path for {@code uv} inside a directory known to contain it.
     */
    public static FilePath getUvExecutable(FilePath uvBinDir, boolean isUnix) throws IOException, InterruptedException {
        FilePath winExe = uvBinDir.child("uv.exe");
        FilePath unixExe = uvBinDir.child("uv");
        if (!isUnix && winExe.exists()) {
            return winExe;
        }
        if (unixExe.exists()) {
            return unixExe;
        }
        if (winExe.exists()) {
            return winExe;
        }
        return isUnix ? unixExe : winExe;
    }

    /**
     * Provisions {@code uv} into the agent's shared tool cache directory and returns the directory containing the {@code uv} binary.
     */
    public static FilePath provisionUv(Node node, FilePath workspaceFallback, TaskListener log,
                                       String uvVersion, String customMirrorUrl) throws IOException, InterruptedException {
        String platformKey = PlatformUtils.detectPlatformKey(node, log);
        String resolvedVersion = normalizeVersion(uvVersion);
        String downloadUrl = buildDownloadUrl(resolvedVersion, platformKey, customMirrorUrl);
        String filename = getArchiveFilename(platformKey);

        FilePath rootDir = node != null ? node.getRootPath() : null;
        if (rootDir == null) {
            rootDir = workspaceFallback;
        }
        if (rootDir == null) {
            throw new IOException("[Python Plugin] Cannot determine agent root or workspace directory to install uv.");
        }

        FilePath installDir = rootDir.child("tools")
                .child("io.jenkins.plugins.python.UvInstaller")
                .child("uv-" + resolvedVersion + "-" + platformKey);

        FilePath marker = installDir.child(MARKER_FILENAME);
        String markerContent = resolvedVersion + "@" + downloadUrl;

        boolean isWin = platformKey.startsWith("windows-");
        FilePath expectedBinary = installDir.child(isWin ? "uv.exe" : "uv");

        if (marker.exists() && expectedBinary.exists()) {
            try {
                String existing = marker.readToString().trim();
                if (existing.equals(markerContent)) {
                    if (log != null) {
                        log.getLogger().println("[Python Plugin] Astral uv (" + resolvedVersion + ") is already cached at: " + installDir.getRemote());
                    }
                    return installDir;
                }
            } catch (IOException | InterruptedException ignored) {
                // Re-install if marker cannot be verified
            }
        }

        if (log != null) {
            log.getLogger().println("[Python Plugin] Provisioning Astral uv (" + resolvedVersion + ") for " + platformKey + " from: " + downloadUrl);
        }

        installDir.mkdirs();
        FilePath tempArchive = installDir.child("uv-download-" + filename);

        try {
            URL url = new URL(downloadUrl);
            URLConnection conn = ProxyConfiguration.open(url);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(120000);
            try (InputStream in = conn.getInputStream()) {
                tempArchive.copyFrom(in);
            }

            if (filename.endsWith(".zip")) {
                tempArchive.unzip(installDir);
            } else {
                tempArchive.untar(installDir, FilePath.TarCompression.GZIP);
            }
        } finally {
            if (tempArchive.exists()) {
                tempArchive.delete();
            }
        }

        flattenAndChmodBinaries(installDir, !isWin);
        marker.write(markerContent, "UTF-8");

        if (log != null) {
            log.getLogger().println("[Python Plugin] Successfully provisioned Astral uv at: " + installDir.getRemote());
        }
        return installDir;
    }

    /**
     * Installs {@code uv} and {@code uvx} binaries directly into an existing standalone Python home directory
     * ({@code bin/} on Unix or {@code Scripts/} on Windows).
     */
    public static void installIntoPythonHome(FilePath pyHome, Node node, TaskListener log,
                                             String uvVersion, String customMirrorUrl) throws IOException, InterruptedException {
        FilePath uvCacheDir = provisionUv(node, pyHome, log, uvVersion, customMirrorUrl);
        boolean isWin = pyHome.child("python.exe").exists();
        FilePath targetBinDir = isWin ? pyHome.child("Scripts") : pyHome.child("bin");
        if (!targetBinDir.exists()) {
            targetBinDir.mkdirs();
        }

        String[] binaries = isWin
                ? new String[]{"uv.exe", "uvx.exe", "uvw.exe"}
                : new String[]{"uv", "uvx", "uvw"};

        for (String binName : binaries) {
            FilePath src = uvCacheDir.child(binName);
            if (src.exists()) {
                FilePath dst = targetBinDir.child(binName);
                src.copyTo(dst);
                if (!isWin) {
                    try {
                        dst.chmod(0755);
                    } catch (IOException | InterruptedException ignored) {
                    }
                }
            }
        }
    }

    /**
     * Flattens nested archive directories (such as {@code uv-x86_64-unknown-linux-gnu/uv}) into {@code installDir}
     * and ensures executable permissions on Unix agents.
     */
    static void flattenAndChmodBinaries(FilePath installDir, boolean isUnix) throws IOException, InterruptedException {
        String[] binaryNames = isUnix
                ? new String[]{"uv", "uvx", "uvw"}
                : new String[]{"uv.exe", "uvx.exe", "uvw.exe"};

        FilePath primaryExe = installDir.child(binaryNames[0]);
        if (!primaryExe.exists()) {
            List<FilePath> subDirs = installDir.listDirectories();
            for (FilePath subDir : subDirs) {
                for (String binName : binaryNames) {
                    FilePath candidate = subDir.child(binName);
                    if (candidate.exists()) {
                        FilePath dest = installDir.child(binName);
                        candidate.copyTo(dest);
                    }
                }
            }
        }

        if (isUnix) {
            for (String binName : binaryNames) {
                FilePath bin = installDir.child(binName);
                if (bin.exists()) {
                    try {
                        bin.chmod(0755);
                    } catch (IOException | InterruptedException ignored) {
                    }
                }
            }
        }
    }
}
