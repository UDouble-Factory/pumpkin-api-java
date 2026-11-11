package io.github.pumpkinmc.gradle;

import javax.inject.Inject;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

public class PumpkinExtension {
    private final Property<String> apiGroup;
    private final Property<String> apiVersion;
    private final Property<String> pluginClass;
    private final Property<Integer> maxHeapMb;

    @Inject
    public PumpkinExtension(ObjectFactory objects) {
        apiGroup = objects.property(String.class).convention(ToolVersions.get("apiGroup"));
        apiVersion = objects.property(String.class).convention(ToolVersions.get("apiVersion"));
        pluginClass = objects.property(String.class);
        maxHeapMb = objects.property(Integer.class).convention(128);
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

    public Property<Integer> getMaxHeapMb() {
        return maxHeapMb;
    }
}
