package io.jenkins.plugins.python;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.tools.ToolInstallation;
import hudson.tools.ToolInstaller;
import hudson.tools.ToolInstallerDescriptor;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import hudson.ProxyConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.List;

/**
 * Automatically downloads and installs self-contained standalone Python distributions
 * on Windows, Linux, and macOS agents without requiring system package managers or root privileges.
 */
public class PythonInstaller extends ToolInstaller {

    private final String id;
    private String mirrorUrl;
    private boolean installUv;
    private String uvVersion;
    private String uvMirrorUrl;

    @DataBoundConstructor
    public PythonInstaller(String id) {
        super(null);
        this.id = id != null ? id.trim() : "";
    }

    public String getId() {
        return id;
    }

    public String getMirrorUrl() {
        return mirrorUrl;
    }

    @DataBoundSetter
    public void setMirrorUrl(String mirrorUrl) {
        this.mirrorUrl = Util.fixEmptyAndTrim(mirrorUrl);
    }

    public boolean isInstallUv() {
        return installUv;
    }

    @DataBoundSetter
    public void setInstallUv(boolean installUv) {
        this.installUv = installUv;
    }

    public String getUvVersion() {
        return uvVersion;
    }

    @DataBoundSetter
    public void setUvVersion(String uvVersion) {
        this.uvVersion = Util.fixEmptyAndTrim(uvVersion);
    }

    public String getUvMirrorUrl() {
        return uvMirrorUrl;
    }

    @DataBoundSetter
    public void setUvMirrorUrl(String uvMirrorUrl) {
        this.uvMirrorUrl = Util.fixEmptyAndTrim(uvMirrorUrl);
    }

    public boolean isUvEnabled() {
        return installUv || (uvVersion != null && !uvVersion.isEmpty());
    }

    @Override
    public FilePath performInstallation(ToolInstallation tool, Node node, TaskListener log) throws IOException, InterruptedException {
        FilePath expected = preferredLocation(tool, node);
        String platformKey = PlatformUtils.detectPlatformKey(node, log);

        PythonVersionCatalog catalog = PythonVersionCatalog.getInstance();
        String resolvedVersion = catalog.resolveVersion(id);
        PythonVersionCatalog.PlatformDownload download = catalog.getDownload(resolvedVersion, platformKey);

        if (download == null) {
            throw new IOException("[Python Plugin] No pre-built Python standalone release found for version: '"
                    + id + "' (resolved: " + resolvedVersion + ") on platform: " + platformKey);
        }

        String downloadUrl = download.getUrl();
        String effectiveMirror = mirrorUrl != null && !mirrorUrl.isEmpty() ? mirrorUrl : System.getProperty("io.jenkins.plugins.python.mirrorUrl");
        if (effectiveMirror != null && !effectiveMirror.trim().isEmpty()) {
            String baseUrl = effectiveMirror.trim();
            if (!baseUrl.endsWith("/")) {
                baseUrl += "/";
            }
            downloadUrl = baseUrl + download.getFilename();
        }

        String uvMarkerSuffix = isUvEnabled() ? "+uv:" + UvInstaller.normalizeVersion(uvVersion) : "";
        FilePath marker = expected.child(".installed-python-version");
        String markerContent = resolvedVersion + "@" + downloadUrl + uvMarkerSuffix;

        if (marker.exists()) {
            try {
                String existing = marker.readToString().trim();
                if (existing.equals(markerContent)) {
                    FilePath existingHome = resolveHome(expected);
                    log.getLogger().println("[Python Plugin] Python " + resolvedVersion + " is already up to date at: " + existingHome.getRemote());
                    return existingHome;
                }
            } catch (IOException | InterruptedException e) {
                // If marker read fails, re-install
            }
        }

        log.getLogger().println("==================================================================");
        log.getLogger().println("[Python Plugin] Provisioning Standalone Python " + resolvedVersion);
        log.getLogger().println("  Platform:    " + platformKey);
        log.getLogger().println("  Download:    " + downloadUrl);
        log.getLogger().println("  Destination: " + expected.getRemote());
        log.getLogger().println("==================================================================");

        expected.mkdirs();
        FilePath tempArchive = expected.child("cpython-download.tar.gz");

        try {
            log.getLogger().println("[Python Plugin] Downloading archive (" + String.format("%.1f", download.getSize() / 1024.0 / 1024.0) + " MB)...");
            URL url = new URL(downloadUrl);
            URLConnection conn = ProxyConfiguration.open(url);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(120000);
            try (InputStream in = conn.getInputStream()) {
                tempArchive.copyFrom(in);
            }

            log.getLogger().println("[Python Plugin] Unpacking archive...");
            tempArchive.untar(expected, FilePath.TarCompression.GZIP);
        } finally {
            if (tempArchive.exists()) {
                tempArchive.delete();
            }
        }

        FilePath pyHome = resolveHome(expected);

        // On Unix, ensure bin/python binaries have executable permissions
        FilePath binDir = pyHome.child("bin");
        if (binDir.exists()) {
            for (FilePath file : binDir.list()) {
                try {
                    file.chmod(0755);
                } catch (IOException | InterruptedException ignored) {
                }
            }
        }

        // On Windows, ensure python3.exe and Scripts/pip executables are provisioned
        FilePath winExe = pyHome.child("python.exe");
        if (winExe.exists()) {
            FilePath winPy3 = pyHome.child("python3.exe");
            if (!winPy3.exists()) {
                try {
                    winExe.copyTo(winPy3);
                } catch (IOException | InterruptedException ignored) {
                }
            }

            FilePath scriptsDir = pyHome.child("Scripts");
            if (!scriptsDir.exists()) {
                scriptsDir.mkdirs();
            }
            FilePath pipExe = scriptsDir.child("pip.exe");
            if (!pipExe.exists()) {
                FilePath bundledWheelDir = pyHome.child("Lib").child("ensurepip").child("_bundled");
                if (bundledWheelDir.exists()) {
                    try {
                        Launcher launcher = node.createLauncher(log);
                        launcher.launch()
                                .cmds(winExe.getRemote(), "-m", "pip", "install", "--no-index", "--find-links", bundledWheelDir.getRemote(), "--force-reinstall", "pip")
                                .stdout(log.getLogger())
                                .stderr(log.getLogger())
                                .join();
                    } catch (IOException | InterruptedException e) {
                        log.getLogger().println("[Python Plugin] Offline pip provisioning note: " + e.getMessage());
                    }
                }
                if (!scriptsDir.child("pip.exe").exists()) {
                    try {
                        String cmdContent = "@echo off\r\n\"%~dp0..\\python.exe\" -m pip %*\r\n";
                        scriptsDir.child("pip.cmd").write(cmdContent, "UTF-8");
                        scriptsDir.child("pip3.cmd").write(cmdContent, "UTF-8");
                    } catch (IOException | InterruptedException ignored) {
                    }
                }
            }
        }

        if (isUvEnabled()) {
            UvInstaller.installIntoPythonHome(pyHome, node, log, uvVersion, uvMirrorUrl);
        }

        marker.write(markerContent, "UTF-8");
        log.getLogger().println("[Python Plugin] Successfully installed Python " + resolvedVersion + " at: " + pyHome.getRemote());
        return pyHome;
    }

    private FilePath resolveHome(FilePath root) throws IOException, InterruptedException {
        FilePath subPython = root.child("python");
        if (subPython.exists() && (subPython.child("bin").exists() || subPython.child("python.exe").exists())) {
            return subPython;
        }
        return root;
    }

    @Extension
    @Symbol("pythonStandalone")
    public static class DescriptorImpl extends ToolInstallerDescriptor<PythonInstaller> {

        @Override
        public String getDisplayName() {
            return "Install Standalone Python from Astral-sh";
        }

        @Override
        public boolean isApplicable(Class<? extends ToolInstallation> toolType) {
            return toolType == PythonInstallation.class;
        }

        @POST
        @SuppressWarnings({"lgtm[jenkins/no-permission-check]", "lgtm[jenkins/csrf]"})
        public ListBoxModel doFillIdItems() {
            Jenkins instance = Jenkins.getInstanceOrNull();
            if (instance != null) {
                instance.checkPermission(Jenkins.ADMINISTER);
            }
            ListBoxModel model = new ListBoxModel();
            PythonVersionCatalog catalog = PythonVersionCatalog.getInstance();
            List<String> versions = catalog.getAvailableVersions();

            for (String v : versions) {
                model.add("Python " + v, v);
            }
            return model;
        }

        @POST
        @SuppressWarnings({"lgtm[jenkins/no-permission-check]", "lgtm[jenkins/csrf]"})
        public FormValidation doCheckId(@QueryParameter String value) {
            Jenkins instance = Jenkins.getInstanceOrNull();
            if (instance != null) {
                instance.checkPermission(Jenkins.ADMINISTER);
            }
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("Python version must be specified.");
            }
            PythonVersionCatalog catalog = PythonVersionCatalog.getInstance();
            String resolved = catalog.resolveVersion(value.trim());
            if (catalog.getRelease(resolved) == null) {
                return FormValidation.warning("Version '" + value.trim() + "' is not present in bundled catalog. It will attempt dynamic resolution.");
            }
            if (!resolved.equals(value.trim())) {
                return FormValidation.ok("Resolves to bundled release: " + resolved);
            }
            return FormValidation.ok();
        }
    }
}
