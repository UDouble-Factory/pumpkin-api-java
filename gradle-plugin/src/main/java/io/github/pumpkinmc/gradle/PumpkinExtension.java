package io.github.pumpkinmc.gradle;

import javax.inject.Inject;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

public class PumpkinExtension {
    private final Property<String> apiGroup;
    private final Property<String> apiVersion;
    private final Property<String> pluginClass;

    @Inject
    public PumpkinExtension(ObjectFactory objects) {
        apiGroup = objects.property(String.class).convention("io.github.udouble-factory");
        apiVersion = objects.property(String.class).convention("0.1.1");
        pluginClass = objects.property(String.class);
    }

    public Property<String> getApiGroup() {
        return apiGroup;
    }

    public Property<String> getApiVersion() {
        return apiVersion;
    }

    public Property<String> getPluginClass() {
        return pluginClass;
    }
}
