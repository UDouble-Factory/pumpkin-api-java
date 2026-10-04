package pumpkin.runtime;

import java.util.Arrays;

public final class Reader {
    private final byte[] data;
    private int position;

    public Reader(byte[] data) {
        this.data = data;
    }

    private void require(int size) {
        if (size < 0 || size > data.length - position) throw new IllegalArgumentException("Truncated native response");
    }

    public boolean readBool() {
        int value = readU8();
        if (value > 1) throw new IllegalArgumentException("Invalid boolean");
        return value == 1;
    }

    public byte readS8() {
        require(1);
        return data[position++];
    }

    public int readU8() {
        return readS8() & 255;
    }

    public int readU16() {
        return readU8() | (readU8() << 8);
    }

    public short readS16() {
        return (short) readU16();
    }

    public int readS32() {
        return readU16() | (readU16() << 16);
    }

    public long readU32() {
        return Integer.toUnsignedLong(readS32());
    }

    public long readS64() {
        return readU32() | (readU32() << 32);
    }

    public long readU64() {
        return readS64();
    }

    public float readF32() {
        return Float.intBitsToFloat(readS32());
    }

    public double readF64() {
        return Double.longBitsToDouble(readS64());
    }

    public int readChar() {
        int value = readS32();
        if (!Character.isValidCodePoint(value) || value >= 0xD800 && value <= 0xDFFF) throw new IllegalArgumentException("Invalid Unicode scalar");
        return value;
    }

    public int readLength() {
        long value = readU32();
        if (value > Integer.MAX_VALUE) throw new IllegalArgumentException("Value is too large for Java");
        return (int) value;
    }

    public int readTag(int count) {
        long value = readU32();
        if (value >= count) throw new IllegalArgumentException("Invalid discriminant");
        return (int) value;
    }

    public String readString() {
        int length = readLength();
        if (length > (data.length - position) / 2) throw new IllegalArgumentException("Truncated string");
        char[] result = new char[length];
        for (int i = 0; i < length; i++) {
            result[i] = (char) readU16();
        }
        return new String(result);
    }

    public byte[] readBytes() {
        int length = readLength();
        require(length);
        byte[] result = Arrays.copyOfRange(data, position, position + length);
        position += length;
        return result;
    }

    public void finish() {
        if (position != data.length) throw new IllegalArgumentException("Unexpected native response data");
    }
}
