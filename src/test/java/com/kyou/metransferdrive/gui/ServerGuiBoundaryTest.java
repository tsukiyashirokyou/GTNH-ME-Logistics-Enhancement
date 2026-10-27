package com.kyou.metransferdrive.gui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

/** Static linkage check, not a dedicated-server startup test. */
public class ServerGuiBoundaryTest {

    @Test
    public void commonEntryPointsAndPacketsDoNotLinkClientGuiClasses() throws Exception {
        for (String type : new String[] { "CommonProxy", "METransferDriveMod", "RecipeLoader", "gui/DriveGuiHandler",
            "gui/ContainerTransferDrive", "gui/DriveGuiNetwork", "gui/DriveGuiNetwork$ActionHandler",
            "gui/DriveGuiNetwork$StateHandler", "gui/DriveGuiNetwork$ServerTicks", "gui/DriveGuiNetwork$ClickHandler",
            "gui/DriveGuiNetwork$ClickMessage" }) {
            final String resource = "/com/kyou/metransferdrive/" + type + ".class";
            try (InputStream input = ServerGuiBoundaryTest.class.getResourceAsStream(resource)) {
                assertNotNull(resource, input);
                final ByteArrayOutputStream output = new ByteArrayOutputStream();
                final byte[] buffer = new byte[4096];
                int size;
                while ((size = input.read(buffer)) != -1) {
                    output.write(buffer, 0, size);
                }
                final String constants = new String(output.toByteArray(), StandardCharsets.ISO_8859_1);
                assertFalse(resource, constants.contains("net/minecraft/client/"));
                assertFalse(resource, constants.contains("com/kyou/metransferdrive/client/"));
                assertFalse(resource, constants.contains("org/lwjgl/"));
            }
        }
    }
}
