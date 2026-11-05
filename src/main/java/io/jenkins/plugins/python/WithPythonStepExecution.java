package io.jenkins.plugins.python;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.tools.InstallSourceProperty;
import hudson.util.ArgumentListBuilder;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.EnvironmentExpander;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepExecution;

import java.io.IOException;
import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Step execution handler for the withPython pipeline step.
 */
public class WithPythonStepExecution extends StepExecution implements Serializable {

    private static final long serialVersionUID = 1L;

    private final WithPythonStep step;

    public WithPythonStepExecution(WithPythonStep step, StepContext context) {
        super(context);
        this.step = step;
    }

    @Override
    public boolean start() throws Exception {
        StepContext context = getContext();
        TaskListener listener = context.get(TaskListener.class);
        EnvVars currentEnv = context.get(EnvVars.class);
        FilePath ws = context.get(FilePath.class);
        Launcher launcher = context.get(Launcher.class);

        Node node = context.get(Node.class);
        if (node == null && ws != null) {
            Computer c = ws.toComputer();
            if (c != null) {
                node = c.getNode();
            }
        }
        if (node == null) {
            node = Jenkins.get();
        }
        if (launcher == null && node != null) {
            launcher = node.createLauncher(listener);
        }

        Map<String, String> additions = new HashMap<>();
        Set<String> deletions = new HashSet<>();

        String basePythonExe = null;
        String basePythonHome = null;

        // 1. Resolve Python installation / environment
        if (step.getHome() != null && !step.getHome().isEmpty()) {
            basePythonHome = step.getHome();
            if (listener != null) {
                listener.getLogger().println("[withPython] Using pre-configured Python home: " + basePythonHome);
            }
            PythonInstallation installation = new PythonInstallation("CustomPython", basePythonHome, Collections.emptyList());
            PythonInstallation translated = installation.forNode(node, listener).forEnvironment(currentEnv != null ? currentEnv : new EnvVars());
            boolean isWin = launcher != null ? !launcher.isUnix() : basePythonHome.contains("\\");
            if (launcher != null) {
                basePythonExe = translated.getExecutable(launcher);
            }
            additions.put("PYTHONHOME", basePythonHome);
            additions.put("PYTHON_HOME", basePythonHome);
            additions.put("PATH", isWin ? basePythonHome + ";" + basePythonHome + "\\Scripts" : basePythonHome + "/bin");
        } else if (step.isSystem()) {
            AgentProbeUtils.SystemPythonInfo sysPy = AgentProbeUtils.probeSystemPython(node, launcher, listener);
            if (sysPy == null) {
                throw new AbortException("[withPython] 'system: true' specified, but no host system Python installation was found on this agent.");
            }
            basePythonHome = sysPy.getHome();
            basePythonExe = sysPy.getExecutable();
            if (listener != null) {
                listener.getLogger().println("[withPython] Using host system Python: " + basePythonExe + " (home: " + basePythonHome + ")");
            }
            if (basePythonHome != null && !basePythonHome.isEmpty()) {
                additions.put("PATH", basePythonHome);
            }
        } else {
            PythonInstallation installation = resolveInstallation(step, node, listener, currentEnv);
            if (installation == null) {
                throw new AbortException("[withPython] Failed to resolve a valid Python installation.");
            }

            PythonInstallation translated = installation.forNode(node, listener).forEnvironment(currentEnv != null ? currentEnv : new EnvVars());
            EnvVars toolEnv = new EnvVars();
            translated.buildEnvVars(toolEnv);

            basePythonHome = translated.getHome();
            basePythonExe = translated.getExecutable(launcher);

            for (Map.Entry<String, String> e : toolEnv.entrySet()) {
                additions.put(e.getKey(), e.getValue());
            }

            if (listener != null) {
                listener.getLogger().println("[withPython] Injected Python environment for " + translated.getName() + " (home: " + (basePythonHome != null ? basePythonHome : "default") + ")");
            }
        }

        // 2. CUDA Support (Hardware accelerator & native C-extensions)
        if (step.isCuda() || (step.getCudaHome() != null && !step.getCudaHome().isEmpty())) {
            configureCudaEnvironment(step, node, launcher, listener, additions);
        }

        // 3. Astral uv Support (Fast Rust-based Python package & project manager)
        String uvExe = null;
        if (step.isUvEnabled()) {
            uvExe = configureUvEnvironment(step, ws, node, launcher, listener, basePythonHome, basePythonExe, additions);
        }

        // 4. Virtual Environment Support
        if (step.getVenv() != null && !step.getVenv().isEmpty()) {
            if (ws == null) {
                throw new AbortException("[withPython] Workspace is required to provision virtual environment.");
            }
            provisionVirtualenv(step, ws, node, launcher, listener, basePythonExe, uvExe, additions, deletions);
        }

        EnvironmentExpander expander = EnvironmentExpander.merge(
                context.get(EnvironmentExpander.class),
                new PythonEnvironmentExpander(additions, deletions)
        );

        context.newBodyInvoker()
                .withContext(expander)
                .withCallback(BodyExecutionCallback.wrap(context))
                .start();

        return false;
    }

    private String configureUvEnvironment(WithPythonStep step, FilePath ws, Node node, Launcher launcher,
                                          TaskListener listener, String basePythonHome, String basePythonExe,
                                          Map<String, String> additions)
            throws IOException, InterruptedException {
        boolean isUnix = launcher != null ? launcher.isUnix() : true;
        FilePath uvBinDir = null;

        String requestedUvVersion = UvInstaller.normalizeVersion(step.getUvVersion());
        String effectiveUvMirror = step.getUvMirrorUrl() != null && !step.getUvMirrorUrl().isEmpty()
                ? step.getUvMirrorUrl()
                : step.getMirrorUrl();

        // Check if uv is already pre-bundled in the resolved Python home (when no custom version/mirror is requested)
        if ("latest".equals(requestedUvVersion) && effectiveUvMirror == null
                && basePythonHome != null && !basePythonHome.isEmpty()) {
            FilePath pyHomePath = node != null ? node.createPath(basePythonHome) : null;
            if (pyHomePath != null) {
                uvBinDir = UvInstaller.findExistingUvBinDir(pyHomePath);
            }
        }

        if (uvBinDir == null) {
            uvBinDir = UvInstaller.provisionUv(node, ws, listener, requestedUvVersion, effectiveUvMirror);
        }

        FilePath uvExecutablePath = UvInstaller.getUvExecutable(uvBinDir, isUnix);
        String uvExeRemote = uvExecutablePath.getRemote();

        // Prepend uv directory onto PATH if not already included
        String uvBinRemote = uvBinDir.getRemote();
        String existingPath = additions.get("PATH");
        String sep = isUnix ? ":" : ";";
        if (existingPath != null && !existingPath.isEmpty()) {
            if (!existingPath.contains(uvBinRemote)) {
                additions.put("PATH", uvBinRemote + sep + existingPath);
            }
        } else {
            additions.put("PATH", uvBinRemote);
        }

        // Bind UV_PYTHON to the active Python executable resolved by withPython
        if (basePythonExe != null && !basePythonExe.isEmpty()) {
            additions.put("UV_PYTHON", basePythonExe);
        }

        // Configure optional UV_CACHE_DIR
        if (step.getUvCacheDir() != null && !step.getUvCacheDir().isEmpty()) {
            String cacheSpec = step.getUvCacheDir();
            String resolvedCache = cacheSpec;
            if (ws != null && !cacheSpec.startsWith("/") && !cacheSpec.contains(":")) {
                resolvedCache = ws.child(cacheSpec).getRemote();
            }
            additions.put("UV_CACHE_DIR", resolvedCache);
        }

        if (listener != null) {
            listener.getLogger().println("[withPython] Configured Astral uv environment: uv=" + uvExeRemote
                    + (basePythonExe != null ? " (UV_PYTHON=" + basePythonExe + ")" : ""));
        }

        return uvExeRemote;
    }

    private void provisionVirtualenv(WithPythonStep step, FilePath ws, Node node, Launcher launcher,
                                     TaskListener listener, String basePythonExe, String uvExe,
                                     Map<String, String> additions, Set<String> deletions)
            throws IOException, InterruptedException {
        String venvSpec = step.getVenv();
        FilePath venvDir = ws.child(venvSpec);

        boolean isUnix = launcher != null ? launcher.isUnix() : true;
        FilePath pyvenvCfg = venvDir.child("pyvenv.cfg");

        if (step.isClear() && venvDir.exists()) {
            if (listener != null) {
                listener.getLogger().println("[withPython] Clearing existing virtual environment at: " + venvDir.getRemote());
            }
            venvDir.deleteRecursive();
        }

        if (!pyvenvCfg.exists()) {
            if (basePythonExe == null || basePythonExe.isEmpty()) {
                basePythonExe = isUnix ? "python3" : "python.exe";
            }

            ArgumentListBuilder cmd = new ArgumentListBuilder();
            if (uvExe != null && !uvExe.isEmpty()) {
                if (listener != null) {
                    listener.getLogger().println("[withPython] Provisioning virtual environment with Astral uv at: " + venvDir.getRemote());
                }
                cmd.add(uvExe, "venv", "--seed", "--python", basePythonExe);
                if (step.isClear()) {
                    cmd.add("--clear");
                }
                if (step.isSystemSitePackages()) {
                    cmd.add("--system-site-packages");
                }
                cmd.add(venvDir.getRemote());
            } else {
                if (listener != null) {
                    listener.getLogger().println("[withPython] Provisioning virtual environment at: " + venvDir.getRemote());
                }
                cmd.add(basePythonExe);
                cmd.add("-m", "venv");
                if (step.isClear()) {
                    cmd.add("--clear");
                }
                if (step.isSystemSitePackages()) {
                    cmd.add("--system-site-packages");
                }
                cmd.add(venvDir.getRemote());
            }

            EnvVars execEnv = new EnvVars();
            for (Map.Entry<String, String> entry : additions.entrySet()) {
                execEnv.put(entry.getKey(), entry.getValue());
            }

            Launcher.ProcStarter ps = launcher.launch()
                    .cmds(cmd)
                    .envs(execEnv)
                    .pwd(ws);
            if (listener != null) {
                ps.stdout(listener.getLogger()).stderr(listener.getLogger());
            }

            int exitCode = ps.join();
            if (exitCode != 0) {
                throw new AbortException("[withPython] Failed to create virtual environment at " + venvDir.getRemote() + " (exit code " + exitCode + ")");
            }
            if (listener != null) {
                listener.getLogger().println("[withPython] Virtual environment created successfully.");
            }
        } else {
            if (listener != null) {
                listener.getLogger().println("[withPython] Using existing virtual environment at: " + venvDir.getRemote());
            }
        }

        FilePath venvBin = isUnix ? venvDir.child("bin") : venvDir.child("Scripts");

        // Essential: unset and remove PYTHONHOME inside venv to prevent library search corruption
        additions.remove("PYTHONHOME");
        additions.remove("PYTHON_HOME");
        deletions.add("PYTHONHOME");
        deletions.add("PYTHON_HOME");

        additions.put("VIRTUAL_ENV", venvDir.getRemote());
        additions.put("VIRTUAL_ENV_PROMPT", venvDir.getName());

        if (step.isUvEnabled()) {
            String venvPython = isUnix
                    ? venvBin.child("python3").getRemote()
                    : venvBin.child("python.exe").getRemote();
            additions.put("UV_PYTHON", venvPython);
        }

        // Prepend venv bin directory onto PATH
        String existingPath = additions.get("PATH");
        String sep = isUnix ? ":" : ";";
        if (existingPath != null && !existingPath.isEmpty()) {
            additions.put("PATH", venvBin.getRemote() + sep + existingPath);
        } else {
            additions.put("PATH", venvBin.getRemote());
        }

        if (listener != null) {
            listener.getLogger().println("[withPython] Activated virtual environment: VIRTUAL_ENV=" + venvDir.getRemote());
        }
    }

    private void configureCudaEnvironment(WithPythonStep step, Node node, Launcher launcher,
                                          TaskListener listener, Map<String, String> additions) {
        AgentProbeUtils.CudaInfo cudaInfo = AgentProbeUtils.probeCuda(node, launcher, listener, step.getCudaHome());
        boolean isUnix = launcher != null ? launcher.isUnix() : true;

        if (cudaInfo != null && cudaInfo.getCudaHome() != null && !cudaInfo.getCudaHome().isEmpty()) {
            String cudaHome = cudaInfo.getCudaHome();
            if (listener != null) {
                listener.getLogger().println("[withPython] Configured CUDA accelerator environment: CUDA_HOME=" + cudaHome);
            }

            additions.put("CUDA_HOME", cudaHome);
            additions.put("CUDA_PATH", cudaHome);
            additions.put("CUDA_ROOT", cudaHome);
            additions.put("CUDA_TOOLKIT_ROOT_DIR", cudaHome);
            additions.put("CUDA_AVAILABLE", "1");

            String sep = isUnix ? ":" : ";";
            if (isUnix) {
                String cudaBin = cudaHome + "/bin";
                String existingPath = additions.get("PATH");
                additions.put("PATH", existingPath != null && !existingPath.isEmpty() ? cudaBin + sep + existingPath : cudaBin);

                String cudaLib = cudaHome + "/lib64:" + cudaHome + "/lib";
                additions.put("LD_LIBRARY_PATH", cudaLib);

                String cudaInc = cudaHome + "/include";
                additions.put("CPATH", cudaInc);
                additions.put("CUDA_INC_DIR", cudaInc);
            } else {
                String cudaBin = cudaHome + "\\bin;" + cudaHome + "\\libnvvp";
                String existingPath = additions.get("PATH");
                additions.put("PATH", existingPath != null && !existingPath.isEmpty() ? cudaBin + sep + existingPath : cudaBin);

                additions.put("INCLUDE", cudaHome + "\\include");
                additions.put("LIB", cudaHome + "\\lib\\x64");
            }
        } else {
            if (listener != null) {
                listener.getLogger().println("[withPython] Notice: CUDA requested (cuda: true) but no CUDA installation was detected on this agent. Continuing with CPU-only mode.");
            }
            additions.put("CUDA_AVAILABLE", "0");
        }
    }

    private PythonInstallation resolveInstallation(WithPythonStep step, Node node, TaskListener listener, EnvVars env) throws IOException, InterruptedException {
        PythonInstallation.DescriptorImpl descriptor = Jenkins.get().getDescriptorByType(PythonInstallation.DescriptorImpl.class);

        // 1. Tool name specified
        if (step.getTool() != null && !step.getTool().isEmpty()) {
            for (PythonInstallation inst : descriptor.getInstallations()) {
                if (inst.getName().equals(step.getTool())) {
                    return inst;
                }
            }
            throw new AbortException("[withPython] Configured Python tool named '" + step.getTool() + "' was not found in Jenkins Global Tool Configuration.");
        }

        // 2. Version specified or resolved default
        String versionSpec = step.getVersion();
        String resolvedVersion = PythonVersionCatalog.getInstance().resolveVersion(versionSpec);

        for (PythonInstallation inst : descriptor.getInstallations()) {
            if (inst.getName().equalsIgnoreCase("Python-" + resolvedVersion) || inst.getName().equalsIgnoreCase(resolvedVersion)) {
                return inst;
            }
        }

        // Auto-provision installation
        PythonInstaller installer = new PythonInstaller(resolvedVersion);
        if (step.getMirrorUrl() != null && !step.getMirrorUrl().isEmpty()) {
            installer.setMirrorUrl(step.getMirrorUrl());
        }

        return new PythonInstallation(
                "Python-" + resolvedVersion,
                null,
                Collections.singletonList(new InstallSourceProperty(Collections.singletonList(installer)))
        );
    }

    private static class PythonEnvironmentExpander extends EnvironmentExpander implements Serializable {
        private static final long serialVersionUID = 1L;
        private final Map<String, String> additions;
        private final Set<String> deletions;

        public PythonEnvironmentExpander(Map<String, String> additions, Set<String> deletions) {
            this.additions = new HashMap<>(additions);
            this.deletions = new HashSet<>(deletions);
        }

        @Override
        public void expand(EnvVars env) {
            for (String del : deletions) {
                env.remove(del);
            }
            for (Map.Entry<String, String> entry : additions.entrySet()) {
                String key = entry.getKey();
                if (deletions.contains(key)) {
                    continue;
                }
                String val = entry.getValue();
                if ("PATH".equalsIgnoreCase(key) || "LD_LIBRARY_PATH".equalsIgnoreCase(key)
                        || "CPATH".equalsIgnoreCase(key) || "INCLUDE".equalsIgnoreCase(key) || "LIB".equalsIgnoreCase(key)) {
                    String existing = env.get(key);
                    if (existing != null && !existing.isEmpty()) {
                        boolean isWin = val.contains(";") || existing.contains(";");
                        String sep = isWin ? ";" : ":";
                        env.put(key, val + sep + existing);
                    } else {
                        env.put(key, val);
                    }
                } else {
                    env.put(key, val);
                }
            }
        }
    }
}
