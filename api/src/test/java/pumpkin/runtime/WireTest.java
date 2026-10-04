package pumpkin.runtime;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WireTest {
    @Test
    void preservesIntegerAndFloatingPointBits() {
        Writer writer = new Writer();
        writer.writeU8(255);
        writer.writeS8(Byte.MIN_VALUE);
        writer.writeU16(65535);
        writer.writeS16(Short.MIN_VALUE);
        writer.writeU32(0xFFFF_FFFFL);
        writer.writeS32(Integer.MIN_VALUE);
        writer.writeU64(-1L);
        writer.writeS64(Long.MIN_VALUE);
        writer.writeS64(9007199254740993L);
        writer.writeF32(Float.intBitsToFloat(0x7fc12345));
        writer.writeF64(Double.longBitsToDouble(0x7ff8123456789abCL));

        Reader reader = new Reader(writer.toByteArray());
        assertEquals(255, reader.readU8());
        assertEquals(Byte.MIN_VALUE, reader.readS8());
        assertEquals(65535, reader.readU16());
        assertEquals(Short.MIN_VALUE, reader.readS16());
        assertEquals(0xFFFF_FFFFL, reader.readU32());
        assertEquals(Integer.MIN_VALUE, reader.readS32());
        assertEquals(-1L, reader.readU64());
        assertEquals(Long.MIN_VALUE, reader.readS64());
        assertEquals(9007199254740993L, reader.readS64());
        assertEquals(0x7fc12345, Float.floatToRawIntBits(reader.readF32()));
        assertEquals(0x7ff8123456789abCL, Double.doubleToRawLongBits(reader.readF64()));
        reader.finish();
    }

    @Test
    void preservesUnicodeAndEmbeddedNull() {
        String value = "호박🎃\u0000𝄞";
        Writer writer = new Writer();
        writer.writeString(value);
        writer.writeBytes(new byte[] {0, 127, -128, -1});
        writer.writeChar(0x10FFFF);
        Reader reader = new Reader(writer.toByteArray());
        assertEquals(value, reader.readString());
        assertArrayEquals(new byte[] {0, 127, -128, -1}, reader.readBytes());
        assertEquals(0x10FFFF, reader.readChar());
        reader.finish();
    }

    @Test
    void rejectsInvalidWireValues() {
        assertThrows(IllegalArgumentException.class, () -> new Writer().writeU8(256));
        assertThrows(IllegalArgumentException.class, () -> new Writer().writeU32(-1));
        assertThrows(IllegalArgumentException.class, () -> new Writer().writeU32(0x1_0000_0000L));
        assertThrows(IllegalArgumentException.class, () -> new Writer().writeString("\uD800"));
        assertThrows(IllegalArgumentException.class, () -> new Writer().writeChar(0xD800));
        assertThrows(IllegalArgumentException.class, () -> new Reader(new byte[] {2}).readBool());
        assertThrows(IllegalArgumentException.class, () -> new Reader(new byte[] {3, 0, 0, 0}).readString());
        assertThrows(IllegalArgumentException.class, () -> new Reader(new byte[] {0}).finish());
    }

    @Test
    void distinguishesNestedOptionAndResultStates() {
        assertNotEquals(Option.none(), Option.some(Option.none()));
        assertNotEquals(Result.success(null), Result.failure(null));
        assertThrows(IllegalStateException.class, () -> Option.none().value());
        assertThrows(IllegalStateException.class, () -> Result.failure("error").value());
        assertThrows(IllegalStateException.class, () -> Result.success(1).error());
    }
}
