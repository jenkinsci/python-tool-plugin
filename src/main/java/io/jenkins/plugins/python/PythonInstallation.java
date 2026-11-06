package io.jenkins.plugins.python;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.Util;
import hudson.model.EnvironmentSpecific;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.NodeSpecific;
import hudson.tools.ToolDescriptor;
import hudson.tools.ToolInstallation;
import hudson.tools.ToolInstaller;
import hudson.tools.ToolProperty;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * Tool installation representing a managed or pre-installed Python distribution on Jenkins agents.
 */
public class PythonInstallation extends ToolInstallation implements
        NodeSpecific<PythonInstallation>,
        EnvironmentSpecific<PythonInstallation> {

    @DataBoundConstructor
    public PythonInstallation(String name, String home, List<? extends ToolProperty<?>> properties) {
        super(Util.fixEmptyAndTrim(name), Util.fixEmptyAndTrim(home), properties);
    }

    @Override
    public PythonInstallation forNode(Node node, TaskListener log) throws IOException, InterruptedException {
        return new PythonInstallation(getName(), translateFor(node, log), getProperties().toList());
    }

    @Override
    public PythonInstallation forEnvironment(EnvVars environment) {
        return new PythonInstallation(getName(), environment.expand(getHome()), getProperties().toList());
    }

    @Override
    public void buildEnvVars(EnvVars env) {
        String home = getHome();
        if (home != null && !home.isEmpty()) {
            env.put("PYTHONHOME", home);
            env.put("PYTHON_HOME", home);
            env.put("PYTHONUNBUFFERED", "1");

            boolean isWin = home.contains("\\") || (home.length() > 1 && home.charAt(1) == ':');
            String path = env.get("PATH");

            if (isWin) {
                String winScripts = home + "\\Scripts";
                String newPath = home + ";" + winScripts + (path != null && !path.isEmpty() ? ";" + path : "");
                env.put("PATH", newPath);
            } else {
                String binUnix = home + "/bin";
                String newPath = binUnix + (path != null && !path.isEmpty() ? ":" + path : "");
                env.put("PATH", newPath);
            }
        }
    }

    /**
     * Resolves the python executable path on the given launcher/node.
     */
    public String getExecutable(Launcher launcher) throws IOException, InterruptedException {
        String home = getHome();
        if (home == null || home.isEmpty()) {
            return launcher.isUnix() ? "python3" : "python.exe";
        }

        if (launcher.isUnix()) {
            String bin = home + "/bin/python3";
            return bin;
        } else {
            return home + "\\python.exe";
        }
    }

    @Extension
    @Symbol("python")
    public static class DescriptorImpl extends ToolDescriptor<PythonInstallation> {

        public DescriptorImpl() {
            load();
        }

        @Override
        public String getDisplayName() {
            return "Python";
        }

        @Override
        public List<? extends ToolInstaller> getDefaultInstallers() {
            String defaultId = PythonVersionCatalog.getInstance().getLatestVersion();
            return Collections.singletonList(new PythonInstaller(
                    defaultId != null && !defaultId.isEmpty() ? defaultId : "latest"));
        }

        @Override
        public PythonInstallation[] getInstallations() {
            return super.getInstallations();
        }

        @Override
        public void setInstallations(PythonInstallation... installations) {
            super.setInstallations(installations);
            save();
        }
    }
}
