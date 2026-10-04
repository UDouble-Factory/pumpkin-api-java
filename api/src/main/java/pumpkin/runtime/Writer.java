package pumpkin.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Objects;

public final class Writer {
    private byte[] data = new byte[128];
    private int length;
    private final ArrayList<WitResource> references = new ArrayList<>();
    private final IdentityHashMap<WitResource, Boolean> transfers = new IdentityHashMap<>();

    private void reserve(int size) {
        if (size < 0 || size > Integer.MAX_VALUE - length) throw new IllegalArgumentException("Native request is too large");
        int required = length + size;
        if (required > data.length) data = Arrays.copyOf(data, Math.max(required, data.length > Integer.MAX_VALUE / 2 ? Integer.MAX_VALUE : data.length * 2));
    }

    public void writeBool(boolean value) {
        writeU8(value ? 1 : 0);
    }

    public void writeU8(int value) {
        if (value < 0 || value > 255) throw new IllegalArgumentException("u8 out of range");
        reserve(1);
        data[length++] = (byte) value;
    }

    public void writeS8(byte value) {
        writeU8(value & 255);
    }

    public void writeU16(int value) {
        if (value < 0 || value > 65535) throw new IllegalArgumentException("u16 out of range");
        writeU8(value & 255);
        writeU8(value >>> 8);
    }

    public void writeS16(short value) {
        writeU16(value & 65535);
    }

    public void writeS32(int value) {
        writeU16(value & 65535);
        writeU16(value >>> 16);
    }

    public void writeU32(long value) {
        if (value < 0 || value > 0xFFFF_FFFFL) throw new IllegalArgumentException("u32 out of range");
        writeS32((int) value);
    }

    public void writeS64(long value) {
        writeS32((int) value);
        writeS32((int) (value >>> 32));
    }

    public void writeU64(long value) {
        writeS64(value);
    }

    public void writeF32(float value) {
        writeS32(Float.floatToRawIntBits(value));
    }

    public void writeF64(double value) {
        writeS64(Double.doubleToRawLongBits(value));
    }

    public void writeChar(int value) {
        if (!Character.isValidCodePoint(value) || value >= 0xD800 && value <= 0xDFFF) throw new IllegalArgumentException("Invalid Unicode scalar");
        writeS32(value);
    }

    public void writeString(String value) {
        Objects.requireNonNull(value);
        writeU32(value.length());
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (i + 1 == value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) throw new IllegalArgumentException("Unpaired UTF-16 surrogate");
                writeU16(current);
                writeU16(value.charAt(++i));
            } else {
                if (Character.isLowSurrogate(current)) throw new IllegalArgumentException("Unpaired UTF-16 surrogate");
                writeU16(current);
            }
        }
    }

    public void writeBytes(byte[] value) {
        Objects.requireNonNull(value);
        writeU32(value.length);
        reserve(value.length);
        System.arraycopy(value, 0, data, length, value.length);
        length += value.length;
    }

    public void writeResource(WitResource resource, boolean transfer) {
        Objects.requireNonNull(resource);
        resource.checkOpen();
        if (transfer) {
            resource.checkOwned();
            if (transfers.put(resource, Boolean.TRUE) != null) throw new IllegalArgumentException("Resource transferred more than once");
        }
        references.add(resource);
        writeS32(resource.handle());
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(data, length);
    }

    public void commitTransfers() {
        for (WitResource resource : references) {
            resource.checkOpen();
        }
        for (WitResource resource : transfers.keySet()) {
            resource.move();
        }
        transfers.clear();
    }
}
