package io.jenkins.plugins.python;

import hudson.ProxyConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads, dynamically fetches, and caches the standalone Python version catalog.
 * Supports air-gapped instances via local filesystem override and fallback to bundled catalog.
 */
public final class PythonVersionCatalog implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = Logger.getLogger(PythonVersionCatalog.class.getName());

    /**
     * Self-updating catalog published by the scheduled "Update Version Catalog" workflow to the
     * {@code catalog} branch, so new Python/uv versions appear without a plugin release.
     */
    public static final String DEFAULT_REMOTE_URL =
            "https://raw.githubusercontent.com/jenkinsci/python-tool-plugin/catalog/versions.json";
    private static final long CACHE_TTL_MS = 24 * 60 * 60 * 1000L; // 24 hours

    private static volatile PythonVersionCatalog instance;

    private final Map<String, PythonRelease> releases = new LinkedHashMap<>();
    private final List<String> uvVersions = new ArrayList<>();

    public static class PlatformDownload implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String url;
        private final long size;
        private final String filename;

        public PlatformDownload(String url, long size, String filename) {
            this.url = url;
            this.size = size;
            this.filename = filename;
        }

        public String getUrl() {
            return url;
        }

        public long getSize() {
            return size;
        }

        public String getFilename() {
            return filename;
        }
    }

    public static class PythonRelease implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String version;
        private final String releaseTag;
        private final Map<String, PlatformDownload> platforms = new LinkedHashMap<>();

        public PythonRelease(String version, String releaseTag) {
            this.version = version;
            this.releaseTag = releaseTag;
        }

        public String getVersion() {
            return version;
        }

        public String getReleaseTag() {
            return releaseTag;
        }

        public Map<String, PlatformDownload> getPlatforms() {
            return Collections.unmodifiableMap(platforms);
        }

        public PlatformDownload getPlatform(String platformKey) {
            return platforms.get(platformKey);
        }

        public void addPlatform(String platformKey, PlatformDownload download) {
            this.platforms.put(platformKey, download);
        }
    }

    public static PythonVersionCatalog getInstance() {
        if (instance == null) {
            synchronized (PythonVersionCatalog.class) {
                if (instance == null) {
                    instance = new PythonVersionCatalog();
                }
            }
        }
        return instance;
    }

    private PythonVersionCatalog() {
        loadCatalog();
    }

    /**
     * Reloads the version catalog from local override, dynamic cache, or bundled resources.
     */
    public synchronized void reload() {
        releases.clear();
        uvVersions.clear();
        loadCatalog();
    }

    private void loadCatalog() {
        // 1. Check for explicit local file override (system property or $JENKINS_HOME/python-versions.json)
        String overridePath = System.getProperty("io.jenkins.plugins.python.versionsFile");
        if (overridePath != null && !overridePath.trim().isEmpty()) {
            File customFile = new File(overridePath.trim());
            if (customFile.isFile() && loadFromFile(customFile)) {
                LOGGER.log(Level.INFO, "[Python Plugin] Loaded Python version catalog from configured override: " + customFile.getAbsolutePath());
                return;
            }
        }

        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins != null) {
            File rootFile = new File(jenkins.getRootDir(), "python-versions.json");
            if (rootFile.isFile() && loadFromFile(rootFile)) {
                LOGGER.log(Level.INFO, "[Python Plugin] Loaded Python version catalog from JENKINS_HOME override: " + rootFile.getAbsolutePath());
                return;
            }

            // 2. Check dynamic cache or fetch remote updates if enabled
            File cacheDir = new File(new File(jenkins.getRootDir(), "caches"), "python-tool");
            File cachedFile = new File(cacheDir, "versions.json");

            boolean dynamicFetchEnabled = !"false".equalsIgnoreCase(System.getProperty("io.jenkins.plugins.python.dynamicFetch"));
            if (dynamicFetchEnabled) {
                boolean needsRefresh = !cachedFile.exists() || (System.currentTimeMillis() - cachedFile.lastModified() > CACHE_TTL_MS);
                if (needsRefresh) {
                    fetchRemoteCatalog(cachedFile);
                }
            }

            if (cachedFile.isFile() && loadFromFile(cachedFile)) {
                LOGGER.log(Level.FINE, "[Python Plugin] Loaded Python version catalog from local disk cache: " + cachedFile.getAbsolutePath());
                return;
            }
        }

        // 3. Fallback: load bundled classpath versions.json
        loadFromClasspath();
    }

    private void fetchRemoteCatalog(File cacheDestination) {
        String remoteUrl = System.getProperty("io.jenkins.plugins.python.versionsUrl", DEFAULT_REMOTE_URL);
        try {
            LOGGER.log(Level.INFO, "[Python Plugin] Refreshing Python versions catalog from: " + remoteUrl);
            URL url = new URL(remoteUrl);
            URLConnection conn = ProxyConfiguration.open(url);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);

            try (InputStream in = conn.getInputStream()) {
                String content = IOUtils.toString(in, StandardCharsets.UTF_8);
                JSONObject root = JSONObject.fromObject(content);
                if (root.has("versions") && !root.getJSONObject("versions").isEmpty()) {
                    FileUtils.writeStringToFile(cacheDestination, content, StandardCharsets.UTF_8);
                    LOGGER.log(Level.INFO, "[Python Plugin] Successfully cached latest Python versions catalog to: " + cacheDestination.getAbsolutePath());
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "[Python Plugin] Note: Dynamic catalog update skipped (offline or unreachable): " + e.getMessage());
        }
    }

    private boolean loadFromFile(File file) {
        try (InputStream in = new FileInputStream(file)) {
            String content = IOUtils.toString(in, StandardCharsets.UTF_8);
            return parseCatalogJson(content);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[Python Plugin] Failed to read version catalog file: " + file.getAbsolutePath(), e);
            return false;
        }
    }

    private void loadFromClasspath() {
        try (InputStream in = PythonVersionCatalog.class.getResourceAsStream("versions.json")) {
            if (in == null) {
                LOGGER.log(Level.WARNING, "[Python Plugin] Embedded versions.json resource not found on classpath!");
                return;
            }
            String content = IOUtils.toString(in, StandardCharsets.UTF_8);
            parseCatalogJson(content);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "[Python Plugin] Failed to parse embedded Python versions catalog", e);
        }
    }

    private boolean parseCatalogJson(String content) {
        try {
            JSONObject root = JSONObject.fromObject(content);
            if (root.has("versions")) {
                JSONObject vObj = root.getJSONObject("versions");
                for (Object keyObj : vObj.keySet()) {
                    String ver = String.valueOf(keyObj);
                    JSONObject vData = vObj.getJSONObject(ver);
                    String releaseTag = vData.optString("releaseTag", "");
                    PythonRelease release = new PythonRelease(ver, releaseTag);

                    if (vData.has("platforms")) {
                        JSONObject pObj = vData.getJSONObject("platforms");
                        for (Object pKeyObj : pObj.keySet()) {
                            String pKey = String.valueOf(pKeyObj);
                            JSONObject pData = pObj.getJSONObject(pKey);
                            String url = pData.getString("url");
                            long size = pData.optLong("size", 0);
                            String filename = pData.optString("filename", "");
                            release.addPlatform(pKey, new PlatformDownload(url, size, filename));
                        }
                    }
                    releases.put(ver, release);
                }
                sortReleasesNewestFirst();
                parseUvVersions(root);
                return true;
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[Python Plugin] Invalid Python catalog JSON format: " + e.getMessage());
        }
        return false;
    }

    private void parseUvVersions(JSONObject root) {
        uvVersions.clear();
        if (!root.has("uvVersions")) {
            return;
        }
        for (Object o : root.getJSONArray("uvVersions")) {
            String v = String.valueOf(o).trim();
            if (!v.isEmpty()) {
                uvVersions.add(v);
            }
        }
        uvVersions.sort(PythonVersionCatalog::compareVersionsDescending);
    }

    private void sortReleasesNewestFirst() {
        List<Map.Entry<String, PythonRelease>> entries = new ArrayList<>(releases.entrySet());
        entries.sort((a, b) -> compareVersionsDescending(a.getKey(), b.getKey()));
        releases.clear();
        for (Map.Entry<String, PythonRelease> e : entries) {
            releases.put(e.getKey(), e.getValue());
        }
    }

    /**
     * Orders dotted numeric versions newest-first so that "latest" never depends on the
     * key order of a hand-edited or merged catalog file. Non-numeric segments sort last.
     */
    static int compareVersionsDescending(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            long na = i < pa.length ? parseSegment(pa[i]) : 0;
            long nb = i < pb.length ? parseSegment(pb[i]) : 0;
            if (na != nb) {
                return Long.compare(nb, na);
            }
        }
        return 0;
    }

    private static long parseSegment(String segment) {
        try {
            return Long.parseLong(segment);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Known Astral uv releases, newest first. May be empty for catalogs that predate uv version
     * listing, in which case callers should only offer "latest".
     */
    public List<String> getUvVersions() {
        return new ArrayList<>(uvVersions);
    }

    public List<String> getAvailableVersions() {
        return new ArrayList<>(releases.keySet());
    }

    public PythonRelease getRelease(String version) {
        return releases.get(version);
    }

    public String getLatestVersion() {
        List<String> list = getAvailableVersions();
        return list.isEmpty() ? "3.12.15" : list.get(0);
    }

    /**
     * Resolves exact version or prefix (e.g. "3.12" -> "3.12.15", "latest" -> latest stable).
     */
    public String resolveVersion(String versionSpec) {
        if (versionSpec == null || versionSpec.trim().isEmpty() || "latest".equalsIgnoreCase(versionSpec.trim())) {
            return getLatestVersion();
        }
        String clean = versionSpec.trim();
        if (releases.containsKey(clean)) {
            return clean;
        }
        // Prefix matching: find first matching major.minor
        for (String v : releases.keySet()) {
            if (v.startsWith(clean + ".") || v.equals(clean)) {
                return v;
            }
        }
        return clean;
    }

    public PlatformDownload getDownload(String version, String platformKey) {
        String resolved = resolveVersion(version);
        PythonRelease release = releases.get(resolved);
        if (release != null) {
            return release.getPlatform(platformKey);
        }
        return null;
    }
}
