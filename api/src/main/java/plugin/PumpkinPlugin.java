package plugin;

import pumpkin.command.CommandError;
import pumpkin.command.CommandSender;
import pumpkin.command.CommandSuggestions;
import pumpkin.command.ConsumedArgs;
import pumpkin.command.SuggestionRequest;
import pumpkin.context.Context;
import pumpkin.event.Event;
import pumpkin.runtime.Result;
import pumpkin.runtime.WitResource;
import pumpkin.server.Server;
import pumpkin.world.ChunkBuffer;
import pumpkin.world.Entity;
import pumpkin.world.GenerationPhase;

public abstract class PumpkinPlugin {
    private final PluginTasks tasks = new PluginTasks();

    public final PluginTasks tasks() {
        return tasks;
    }

    public abstract PluginMetadata metadata();

    public void initPlugin() {
    }

    public Result<Void, String> onLoad(Context context) {
        return Result.success(null);
    }

    public Result<Void, String> onUnload(Context context) {
        return Result.success(null);
    }

    public Result<Integer, CommandError> handleCommand(long commandId, CommandSender sender, Server server, ConsumedArgs args) {
        throw unsupported("handleCommand");
    }

    public CommandSuggestions handleCommandSuggestion(long handlerId, CommandSender sender, Server server, SuggestionRequest request) {
        throw unsupported("handleCommandSuggestion");
    }

    public Event handleEvent(long eventId, Server server, Event event) {
        throw unsupported("handleEvent");
    }

    public void handleTask(long handlerId, Server server) {
        throw unsupported("handleTask");
    }

    public Result<byte[], String> handleIpcMessage(String sender, byte[] message) {
        return Result.failure("This plugin cannot receive messages.");
    }

    public boolean handleAiGoalCanStart(long goalId, Server server, Entity entity) {
        throw unsupported("handleAiGoalCanStart");
    }

    public boolean handleAiGoalShouldContinue(long goalId, Server server, Entity entity) {
        throw unsupported("handleAiGoalShouldContinue");
    }

    public void handleAiGoalStart(long goalId, Server server, Entity entity) {
        throw unsupported("handleAiGoalStart");
    }

    public void handleAiGoalTick(long goalId, Server server, Entity entity) {
        throw unsupported("handleAiGoalTick");
    }

    public void handleAiGoalStop(long goalId, Server server, Entity entity) {
        throw unsupported("handleAiGoalStop");
    }

    public void handleGeneratePhase(long generatorId, GenerationPhase phase, ChunkBuffer chunk) {
        throw unsupported("handleGeneratePhase");
    }

    final void dispatchTask(long handlerId, Server server) {
        if (!tasks.dispatch(handlerId, server)) handleTask(handlerId, server);
    }

    final Result<Void, String> load(Context context) {
        try {
            return onLoad(context);
        } catch (Throwable failure) {
            return Result.failure(failure.toString());
        }
    }

    final Result<Void, String> unload(Context context) {
        Result<Void, String> result;
        try {
            result = onUnload(context);
        } catch (Throwable failure) {
            result = Result.failure(failure.toString());
        }

        try {
            tasks.close();
        } catch (Throwable failure) {
            if (!result.isFailure()) result = Result.failure(failure.toString());
        } finally {
            WitResource.closeAll();
        }
        return result;
    }

    private UnsupportedOperationException unsupported(String callback) {
        return new UnsupportedOperationException("PumpkinPlugin." + callback + " has not been implemented");
    }
}
