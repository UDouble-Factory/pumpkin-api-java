package io.github.pumpkinmc.gradle;

import java.io.IOException;
import java.util.Properties;

final class ToolVersions {
    private static final Properties VALUES = load();

    private ToolVersions() {
    }

    private static Properties load() {
        Properties values = new Properties();
        try (var input = ToolVersions.class.getResourceAsStream("tool-versions.properties")) {
            if (input == null) throw new IllegalStateException("Missing Pumpkin tool versions");
            values.load(input);
            return values;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read Pumpkin tool versions", failure);
        }
    }

    static String get(String name) {
        String value = VALUES.getProperty(name);
        if (value == null) throw new IllegalArgumentException("Unknown tool version: " + name);
        return value;
    }
}
