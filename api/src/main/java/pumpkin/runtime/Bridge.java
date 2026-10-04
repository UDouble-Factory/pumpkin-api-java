package pumpkin.runtime;

import org.teavm.interop.Address;
import org.teavm.interop.Import;

public final class Bridge {
    private Bridge() {
    }

    public static Reader call(int opcode, Writer writer) {
        WitResource.collect();
        byte[] request = writer.toByteArray();
        writer.commitTransfers();
        Address response = invoke(opcode, Address.ofData(request), request.length);
        try {
            return new Reader(copy(response.add(4), response.getInt()));
        } finally {
            free(response);
        }
    }

    public static byte[] copy(Address pointer, int length) {
        if (length < 0) throw new IllegalArgumentException("Invalid native buffer size");
        byte[] result = new byte[length];
        for (int i = 0; i < length; i++) {
            result[i] = pointer.add(i).getByte();
        }
        return result;
    }

    public static Address export(Writer writer) {
        byte[] data = writer.toByteArray();
        Address pointer = allocate(data.length + 4);
        pointer.putInt(data.length);
        for (int i = 0; i < data.length; i++) {
            pointer.add(i + 4).putByte(data[i]);
        }
        writer.commitTransfers();
        return pointer;
    }

    @Import(name = "pumpkin_java_call")
    private static native Address invoke(int opcode, Address data, int length);

    @Import(name = "pumpkin_java_drop")
    static native void drop(int type, int handle);

    @Import(name = "pumpkin_java_allocate")
    private static native Address allocate(int length);

    @Import(name = "pumpkin_java_free")
    private static native void free(Address pointer);
}
