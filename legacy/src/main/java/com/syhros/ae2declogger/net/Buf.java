package com.syhros.ae2declogger.net;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * Packet and save-file encoding shared by the three legacy versions: a netty buffer (present in all of them) with
 * variable-length numbers, strings, enums and lists.
 */
public final class Buf {

    /** Writes one value. */
    public interface Writer<T> {
        void write(Buf buf, T value);
    }

    /** Reads one value. */
    public interface Reader<T> {
        T read(Buf buf);
    }

    private static final int MAX_STRING = 32767;
    private static final int MAX_LIST = 100_000;

    public final ByteBuf raw;

    public Buf(ByteBuf raw) {
        this.raw = raw;
    }

    public static Buf create() {
        return new Buf(Unpooled.buffer());
    }

    public static Buf of(byte[] bytes) {
        return new Buf(Unpooled.wrappedBuffer(bytes));
    }

    public byte[] toBytes() {
        byte[] out = new byte[raw.readableBytes()];
        raw.getBytes(raw.readerIndex(), out);
        return out;
    }

    public void writeBoolean(boolean v) {
        raw.writeBoolean(v);
    }

    public boolean readBoolean() {
        return raw.readBoolean();
    }

    public void writeByte(int v) {
        raw.writeByte(v);
    }

    public byte readByte() {
        return raw.readByte();
    }

    public void writeLong(long v) {
        raw.writeLong(v);
    }

    public long readLong() {
        return raw.readLong();
    }

    public void writeFloat(float v) {
        raw.writeFloat(v);
    }

    public float readFloat() {
        return raw.readFloat();
    }

    public void writeDouble(double v) {
        raw.writeDouble(v);
    }

    public double readDouble() {
        return raw.readDouble();
    }

    public void writeVarInt(int v) {
        while ((v & ~0x7F) != 0) {
            raw.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        raw.writeByte(v);
    }

    public int readVarInt() {
        int result = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            byte b = raw.readByte();
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
        }
        throw new IllegalStateException("VarInt too long");
    }

    public void writeVarLong(long v) {
        while ((v & ~0x7FL) != 0) {
            raw.writeByte((int) (v & 0x7F) | 0x80);
            v >>>= 7;
        }
        raw.writeByte((int) v);
    }

    public long readVarLong() {
        long result = 0;
        for (int shift = 0; shift < 70; shift += 7) {
            byte b = raw.readByte();
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
        }
        throw new IllegalStateException("VarLong too long");
    }

    public void writeString(String s) {
        byte[] bytes = (s.length() > MAX_STRING ? s.substring(0, MAX_STRING) : s).getBytes(StandardCharsets.UTF_8);
        writeVarInt(bytes.length);
        raw.writeBytes(bytes);
    }

    public String readString() {
        int len = readVarInt();
        if (len < 0 || len > MAX_STRING * 4) {
            throw new IllegalStateException("string too long: " + len);
        }
        byte[] bytes = new byte[len];
        raw.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public void writeBytes(byte[] bytes) {
        writeVarInt(bytes.length);
        raw.writeBytes(bytes);
    }

    public byte[] readBytes() {
        int len = readVarInt();
        if (len < 0 || len > raw.readableBytes()) {
            throw new IllegalStateException("bad byte array length: " + len);
        }
        byte[] bytes = new byte[len];
        raw.readBytes(bytes);
        return bytes;
    }

    public void writeEnum(Enum<?> e) {
        writeVarInt(e.ordinal());
    }

    /** Reads an enum; an ordinal this side does not know (newer sender) becomes {@code fallback}. */
    public <E extends Enum<E>> E readEnum(Class<E> type, E fallback) {
        int i = readVarInt();
        E[] values = type.getEnumConstants();
        return i >= 0 && i < values.length ? values[i] : fallback;
    }

    public <T> void writeList(List<T> list, Writer<T> writer) {
        writeVarInt(list.size());
        for (T t : list) {
            writer.write(this, t);
        }
    }

    /** Reads a list, leaving out entries the reader returned null for. */
    public <T> List<T> readList(Reader<T> reader) {
        int n = readVarInt();
        if (n < 0 || n > MAX_LIST) {
            throw new IllegalStateException("list too long: " + n);
        }
        List<T> list = new ArrayList<>(Math.min(n, 256));
        for (int i = 0; i < n; i++) {
            T t = reader.read(this);
            if (t != null) {
                list.add(t);
            }
        }
        return list;
    }

    public <T> void writeNullable(T value, Writer<T> writer) {
        writeBoolean(value != null);
        if (value != null) {
            writer.write(this, value);
        }
    }

    public <T> T readNullable(Reader<T> reader) {
        return readBoolean() ? reader.read(this) : null;
    }

    public void writeLongArray(long[] values) {
        writeVarInt(values.length);
        for (long v : values) {
            writeLong(v);
        }
    }

    public long[] readLongArray() {
        int n = readVarInt();
        if (n < 0 || n > MAX_LIST) {
            throw new IllegalStateException("array too long: " + n);
        }
        long[] values = new long[n];
        for (int i = 0; i < n; i++) {
            values[i] = readLong();
        }
        return values;
    }
}
