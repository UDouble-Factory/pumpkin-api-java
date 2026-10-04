package pumpkin.runtime;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.teavm.interop.Address;
import org.teavm.interop.Function;
import org.teavm.interop.Import;
import plugin.PumpkinPlugin;
import plugin.Exports;

public final class PluginRuntime {
    private static PumpkinPlugin plugin;

    private PluginRuntime() {
    }

    public static void start(PumpkinPlugin instance) {
        if (plugin != null) throw new IllegalStateException("Plugin is already initialized");
        plugin = Objects.requireNonNull(instance);
        register(Function.get(Callback.class, PluginRuntime.class, "dispatch"));
    }

    public static Address dispatch(int opcode, Address data, int length) {
        try {
            WitResource.collect();
            Reader reader = new Reader(Bridge.copy(data, length));
            Writer writer = new Writer();
            writer.writeU8(0);
            Exports.dispatch(opcode, reader, writer, plugin);
            return Bridge.export(writer);
        } catch (Throwable failure) {
            byte[] message = failure.toString().getBytes(StandardCharsets.UTF_8);
            reportFailure(Address.ofData(message), message.length);
            Writer writer = new Writer();
            writer.writeU8(1);
            return Bridge.export(writer);
        }
    }

    @Import(name = "pumpkin_java_register")
    private static native void register(Callback callback);

    @Import(name = "pumpkin_java_report_failure")
    private static native void reportFailure(Address data, int length);

    public abstract static class Callback extends Function {
        public abstract Address apply(int opcode, Address data, int length);
    }
}
