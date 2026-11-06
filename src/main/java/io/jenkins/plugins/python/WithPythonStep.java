package io.jenkins.plugins.python;

import hudson.Extension;
import hudson.Util;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Pipeline step enabling execution inside a specific Python version environment,
 * supporting virtual environments, CUDA hardware acceleration, corporate mirrors,
 * and host system Python runtimes.
 *
 * Example:
 * <pre>
 * withPython(version: '3.12.15', venv: '.venv', cuda: true) {
 *     sh 'python --version'
 * }
 * </pre>
 */
public class WithPythonStep extends Step implements Serializable {

    private static final long serialVersionUID = 1L;

    private String version;
    private String tool;
    private boolean system;
    private String home;
    private String mirrorUrl;
    private String venv;
    private boolean clear;
    private boolean systemSitePackages;
    private boolean cuda;
    private String cudaHome;
    private boolean uv;
    private String uvVersion;
    private String uvMirrorUrl;
    private String uvCacheDir;

    @DataBoundConstructor
    public WithPythonStep(String version) {
        this.version = Util.fixEmptyAndTrim(version);
    }

    public String getVersion() {
        return version;
    }

    @DataBoundSetter
    public void setVersion(String version) {
        this.version = Util.fixEmptyAndTrim(version);
    }

    public String getTool() {
        return tool;
    }

    @DataBoundSetter
    public void setTool(String tool) {
        this.tool = Util.fixEmptyAndTrim(tool);
    }

    public boolean isSystem() {
        return system;
    }

    @DataBoundSetter
    public void setSystem(boolean system) {
        this.system = system;
    }

    public String getHome() {
        return home;
    }

    @DataBoundSetter
    public void setHome(String home) {
        this.home = Util.fixEmptyAndTrim(home);
    }

    public String getMirrorUrl() {
        return mirrorUrl;
    }

    @DataBoundSetter
    public void setMirrorUrl(String mirrorUrl) {
        this.mirrorUrl = Util.fixEmptyAndTrim(mirrorUrl);
    }

    public String getVenv() {
        return venv;
    }

    @DataBoundSetter
    public void setVenv(String venv) {
        this.venv = Util.fixEmptyAndTrim(venv);
    }

    public boolean isClear() {
        return clear;
    }

    @DataBoundSetter
    public void setClear(boolean clear) {
        this.clear = clear;
    }

    public boolean isSystemSitePackages() {
        return systemSitePackages;
    }

    @DataBoundSetter
    public void setSystemSitePackages(boolean systemSitePackages) {
        this.systemSitePackages = systemSitePackages;
    }

    public boolean isCuda() {
        return cuda;
    }

    @DataBoundSetter
    public void setCuda(boolean cuda) {
        this.cuda = cuda;
    }

    public String getCudaHome() {
        return cudaHome;
    }

    @DataBoundSetter
    public void setCudaHome(String cudaHome) {
        this.cudaHome = Util.fixEmptyAndTrim(cudaHome);
    }

    public boolean isUv() {
        return uv;
    }

    @DataBoundSetter
    public void setUv(boolean uv) {
        this.uv = uv;
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

    public String getUvCacheDir() {
        return uvCacheDir;
    }

    @DataBoundSetter
    public void setUvCacheDir(String uvCacheDir) {
        this.uvCacheDir = Util.fixEmptyAndTrim(uvCacheDir);
    }

    public boolean isUvEnabled() {
        return uv || (uvVersion != null && !uvVersion.isEmpty() && !"latest".equalsIgnoreCase(uvVersion));
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new WithPythonStepExecution(this, context);
    }

    @Extension
    @Symbol("withPython")
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "withPython";
        }

        @Override
        public String getDisplayName() {
            return "Set up Python environment on PATH";
        }

        @Override
        public boolean takesImplicitBlockArgument() {
            return true;
        }

        @POST
        @SuppressWarnings({"lgtm[jenkins/no-permission-check]", "lgtm[jenkins/csrf]"})
        public ListBoxModel doFillUvVersionItems(@QueryParameter String uvVersion) {
            Jenkins instance = Jenkins.getInstanceOrNull();
            if (instance != null) {
                instance.checkPermission(Jenkins.READ);
            }
            return PythonInstaller.buildUvVersionItems(uvVersion);
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            Set<Class<?>> context = new HashSet<>();
            context.add(hudson.FilePath.class);
            context.add(hudson.EnvVars.class);
            context.add(hudson.model.TaskListener.class);
            return Collections.unmodifiableSet(context);
        }
    }
}
