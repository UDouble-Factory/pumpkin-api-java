package example;

import java.util.List;
import plugin.PluginMetadata;
import plugin.PumpkinPlugin;
import pumpkin.context.Context;
import pumpkin.logging.Level;
import pumpkin.logging.Logging;
import pumpkin.runtime.Result;

public final class ExamplePlugin extends PumpkinPlugin {
    @Override
    public PluginMetadata metadata() {
        return new PluginMetadata("Example Java Plugin", "0.1.0", List.of("You"), "A Java Pumpkin plugin", List.of(), List.of());
    }

    @Override
    public void initPlugin() {
        Logging.log(Level.INFO, "Initialized Java plugin");
    }

    @Override
    public Result<Void, String> onLoad(Context context) {
        try (context) {
            Logging.log(Level.INFO, "Loaded Java plugin: " + context.getDataFolder());
            tasks().afterTicks(1, server -> {
                try (server) {
                    Logging.log(Level.INFO, "Hello from Java after one tick");
                }
            });
        }
        return Result.success(null);
    }

    @Override
    public Result<Void, String> onUnload(Context context) {
        try (context) {
            Logging.log(Level.INFO, "Unloaded Java plugin");
        }
        return Result.success(null);
    }
}
