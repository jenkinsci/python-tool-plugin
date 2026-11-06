package io.jenkins.plugins.python;

import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PythonInstallerTest {

    @Test
    void testInstallerConstructorAndGetter() {
        PythonInstaller installer = new PythonInstaller("3.12.15");
        assertEquals("3.12.15", installer.getId());
    }

    @Test
    void testDescriptorItems() {
        PythonInstaller.DescriptorImpl desc = new PythonInstaller.DescriptorImpl();
        ListBoxModel items = desc.doFillIdItems();

        assertNotNull(items);
        assertFalse(items.isEmpty(), "Version dropdown should have items");
        assertTrue(items.size() >= 5);
    }

    @Test
    void testDescriptorUvVersionItems() {
        PythonInstaller.DescriptorImpl desc = new PythonInstaller.DescriptorImpl();
        ListBoxModel uvItems = desc.doFillUvVersionItems(null);

        assertNotNull(uvItems);
        assertTrue(uvItems.size() > 1, "uv version dropdown should include latest plus catalog versions");
        assertEquals("latest", uvItems.get(0).value);

        ListBoxModel customItems = desc.doFillUvVersionItems("0.9.99-custom");
        assertEquals("latest", customItems.get(0).value);
        assertEquals("0.9.99-custom", customItems.get(1).value);
    }

    @Test
    void testDescriptorValidation() {
        PythonInstaller.DescriptorImpl desc = new PythonInstaller.DescriptorImpl();

        assertEquals(FormValidation.Kind.ERROR, desc.doCheckId("").kind);
        assertEquals(FormValidation.Kind.ERROR, desc.doCheckId(null).kind);
        assertEquals(FormValidation.Kind.OK, desc.doCheckId("3.12.15").kind);
        assertEquals(FormValidation.Kind.OK, desc.doCheckId("3.13.16").kind);
        assertEquals(FormValidation.Kind.OK, desc.doCheckId("3.11").kind);
        assertEquals(FormValidation.Kind.OK, desc.doCheckId("3.10").kind);
        assertEquals(FormValidation.Kind.OK, desc.doCheckId("latest").kind);
        assertEquals(FormValidation.Kind.WARNING, desc.doCheckId("9.99.99").kind);
    }

    @Test
    void testUvOptions() {
        PythonInstaller installer = new PythonInstaller("3.12.15");
        assertFalse(installer.isInstallUv());
        assertFalse(installer.isUvEnabled());

        // Default "latest" from dropdown when installUv is unchecked should not enable uv
        installer.setUvVersion("latest");
        assertFalse(installer.isUvEnabled());

        installer.setInstallUv(true);
        assertTrue(installer.isInstallUv());
        assertTrue(installer.isUvEnabled());

        installer.setInstallUv(false);
        installer.setUvVersion("0.13.1");
        installer.setUvMirrorUrl("https://mirror.corp.internal/uv/");
        assertEquals("0.13.1", installer.getUvVersion());
        assertEquals("https://mirror.corp.internal/uv/", installer.getUvMirrorUrl());
        assertTrue(installer.isUvEnabled());
    }
}
