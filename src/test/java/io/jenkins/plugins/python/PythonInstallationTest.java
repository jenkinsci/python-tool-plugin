package io.jenkins.plugins.python;

import hudson.EnvVars;
import hudson.tools.InstallSourceProperty;
import hudson.tools.ToolInstaller;
import hudson.tools.ToolInstallerDescriptor;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PythonInstallationTest {

    @Test
    void testBuildEnvVarsUnix() {
        PythonInstallation tool = new PythonInstallation("Python-3.12", "/opt/tools/python", Collections.emptyList());
        EnvVars env = new EnvVars();
        env.put("PATH", "/usr/bin:/bin");

        tool.buildEnvVars(env);

        assertEquals("/opt/tools/python", env.get("PYTHONHOME"));
        assertEquals("/opt/tools/python", env.get("PYTHON_HOME"));
        assertEquals("1", env.get("PYTHONUNBUFFERED"));
        assertTrue(env.get("PATH").startsWith("/opt/tools/python/bin:"), "Unix PATH should prepend /opt/tools/python/bin");
    }

    @Test
    void testBuildEnvVarsWindows() {
        PythonInstallation tool = new PythonInstallation("Python-3.12", "C:\\tools\\python", Collections.emptyList());
        EnvVars env = new EnvVars();
        env.put("PATH", "C:\\Windows\\System32");

        tool.buildEnvVars(env);

        assertEquals("C:\\tools\\python", env.get("PYTHONHOME"));
        assertEquals("C:\\tools\\python", env.get("PYTHON_HOME"));
        assertEquals("1", env.get("PYTHONUNBUFFERED"));
        assertTrue(env.get("PATH").startsWith("C:\\tools\\python;C:\\tools\\python\\Scripts;"), "Windows PATH should prepend root and Scripts");
    }

    @Test
    @WithJenkins
    void testDefaultInstallersAndApplicableInstallerDescriptors(JenkinsRule jenkins) throws Exception {
        PythonInstallation.DescriptorImpl desc = jenkins.jenkins.getDescriptorByType(PythonInstallation.DescriptorImpl.class);
        assertNotNull(desc);

        List<? extends ToolInstaller> defaultInstallers = desc.getDefaultInstallers();
        assertNotNull(defaultInstallers);
        assertEquals(1, defaultInstallers.size(), "PythonInstallation should default to PythonInstaller");
        assertTrue(defaultInstallers.get(0) instanceof PythonInstaller);
        assertEquals("latest", ((PythonInstaller) defaultInstallers.get(0)).getId());

        List<ToolInstallerDescriptor<?>> applicable = ToolInstallerDescriptor.for_(PythonInstallation.class);
        assertTrue(
                applicable.stream().anyMatch(d -> d instanceof PythonInstaller.DescriptorImpl),
                "Install Standalone Python from Astral-sh should be listed as an applicable installer for PythonInstallation"
        );

        PythonInstaller installer = new PythonInstaller("3.14");
        installer.setInstallUv(true);
        installer.setUvVersion("0.13.7");
        InstallSourceProperty installSource = new InstallSourceProperty(Collections.singletonList(installer));
        PythonInstallation installation = new PythonInstallation("Python-3.14", "", Collections.singletonList(installSource));
        desc.setInstallations(installation);

        PythonInstallation[] saved = desc.getInstallations();
        assertEquals(1, saved.length);
        assertEquals("Python-3.14", saved[0].getName());
        InstallSourceProperty savedSource = saved[0].getProperties().get(InstallSourceProperty.class);
        assertNotNull(savedSource);
        PythonInstaller savedInstaller = savedSource.installers.get(PythonInstaller.class);
        assertNotNull(savedInstaller);
        assertEquals("3.14", savedInstaller.getId());
        assertTrue(savedInstaller.isInstallUv());
        assertEquals("0.13.7", savedInstaller.getUvVersion());
    }
}
