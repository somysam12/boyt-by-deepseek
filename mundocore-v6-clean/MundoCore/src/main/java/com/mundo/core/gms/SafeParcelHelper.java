package com.mundo.core.gms;

import android.os.Parcel;

import java.util.ArrayList;
import java.util.List;

/**
 * SafeParcel wire-format reader/writer, matching Google's
 * com.google.android.gms.common.internal.safeparcel.SafeParcelWriter/Reader.
 *
 * Field header (one int):
 *   low 16 bits  = field id
 *   high 16 bits = size in bytes, or 0xFFFF meaning "size follows as next int"
 *
 * Object header: field id 20293 (0x4F45) with an explicit size int, followed by
 * the fields. Variable-length data (strings, nested objects, lists) always uses
 * the 0xFFFF form with an explicit size int.
 */
public final class SafeParcelHelper {

    public static final int OBJECT_HEADER = 0x4F45; // 20293

    private SafeParcelHelper() {}

    // =====================================================================
    // Writer
    // =====================================================================

    /** Begins an object; returns the position to pass to {@link #finishObject}. */
    public static int beginObject(Parcel dest) {
        return beginVariableData(dest, OBJECT_HEADER);
    }

    public static void finishObject(Parcel dest, int start) {
        finishVariableData(dest, start);
    }

    private static void writeHeader(Parcel dest, int id, int size) {
        if (size >= 0xFFFF) {
            dest.writeInt(0xFFFF0000 | id);
            dest.writeInt(size);
        } else {
            dest.writeInt((size << 16) | id);
        }
    }

    private static int beginVariableData(Parcel dest, int id) {
        dest.writeInt(0xFFFF0000 | id);
        dest.writeInt(0); // size placeholder
        return dest.dataPosition();
    }

    private static void finishVariableData(Parcel dest, int start) {
        int end = dest.dataPosition();
        dest.setDataPosition(start - 4);
        dest.writeInt(end - start);
        dest.setDataPosition(end);
    }

    public static void writeInt(Parcel dest, int id, int value) {
        writeHeader(dest, id, 4);
        dest.writeInt(value);
    }

    public static void writeLong(Parcel dest, int id, long value) {
        writeHeader(dest, id, 8);
        dest.writeLong(value);
    }

    public static void writeBoolean(Parcel dest, int id, boolean value) {
        writeHeader(dest, id, 4);
        dest.writeInt(value ? 1 : 0);
    }

    /** Null strings are omitted (Google's writer does the same with writeNull=false). */
    public static void writeString(Parcel dest, int id, String value) {
        if (value == null) return;
        int start = beginVariableData(dest, id);
        dest.writeString(value);
        finishVariableData(dest, start);
    }

    /**
     * Writes a list of SafeParcelables exactly like SafeParcelWriter.writeTypedList:
     * count, then per item one int (0 = null, otherwise the item's byte size, which
     * Parcel.readTypedObject treats as the non-null marker) followed by the body.
     */
    public static void writeTypedList(Parcel dest, int id, List<? extends Writable> list) {
        if (list == null) return;
        int start = beginVariableData(dest, id);
        dest.writeInt(list.size());
        for (Writable item : list) {
            if (item == null) {
                dest.writeInt(0);
            } else {
                int sizePos = dest.dataPosition();
                dest.writeInt(1);
                int itemStart = dest.dataPosition();
                item.writeSafeParcel(dest);
                int itemEnd = dest.dataPosition();
                dest.setDataPosition(sizePos);
                dest.writeInt(itemEnd - itemStart);
                dest.setDataPosition(itemEnd);
            }
        }
        finishVariableData(dest, start);
    }

    /** Anything that can write itself as a SafeParcel object (header included). */
    public interface Writable {
        void writeSafeParcel(Parcel dest);
    }

    // ---- Scope / Status helpers (shared by several parcels) -----------------

    public static final class Scope implements Writable {
        public final String uri;

        public Scope(String uri) { this.uri = uri; }

        @Override
        public void writeSafeParcel(Parcel dest) {
            int s = beginObject(dest);
            writeInt(dest, 1, 1);
            writeString(dest, 2, uri);
            finishObject(dest, s);
        }
    }

    /** com.google.android.gms.common.api.Status: 1=code, 2=message, 1000=versionCode. */
    public static void writeStatus(Parcel dest, int statusCode, String message) {
        int s = beginObject(dest);
        writeInt(dest, 1, statusCode);
        writeString(dest, 2, message);
        writeInt(dest, 1000, 1);
        finishObject(dest, s);
    }

    // =====================================================================
    // Reader
    // =====================================================================

    public static int readHeader(Parcel p) {
        return p.readInt();
    }

    public static int fieldId(int header) {
        return header & 0xFFFF;
    }

    public static int readSize(Parcel p, int header) {
        if ((header & 0xFFFF0000) != 0xFFFF0000) {
            return (header >> 16) & 0xFFFF;
        }
        return p.readInt();
    }

    /** Validates the object header and returns the end position of the object. */
    public static int validateObjectHeader(Parcel p) {
        int header = readHeader(p);
        int size = readSize(p, header);
        int start = p.dataPosition();
        if (fieldId(header) != OBJECT_HEADER) {
            throw new IllegalArgumentException("Expected SafeParcel object header, got 0x"
                    + Integer.toHexString(header));
        }
        int end = start + size;
        if (end < start || end > p.dataSize()) {
            throw new IllegalArgumentException("Size read is invalid start=" + start + " end=" + end);
        }
        return end;
    }

    public static void skip(Parcel p, int header) {
        int size = readSize(p, header);
        p.setDataPosition(p.dataPosition() + size);
    }

    public static int readInt(Parcel p, int header) {
        int size = readSize(p, header);
        int pos = p.dataPosition();
        int v = p.readInt();
        p.setDataPosition(pos + size);
        return v;
    }

    public static long readLong(Parcel p, int header) {
        int size = readSize(p, header);
        int pos = p.dataPosition();
        long v = p.readLong();
        p.setDataPosition(pos + size);
        return v;
    }

    public static boolean readBoolean(Parcel p, int header) {
        return readInt(p, header) != 0;
    }

    public static String readString(Parcel p, int header) {
        int size = readSize(p, header);
        if (size == 0) return null;
        int pos = p.dataPosition();
        String s = p.readString();
        p.setDataPosition(pos + size);
        return s;
    }

    /**
     * Reads a nested Parcelable that was written with writeParcelable(id, obj),
     * i.e. the raw writeToParcel() output inside a sized block. Returns the
     * absolute start/end of the block via the returned int[2], or null.
     */
    public static int[] readBlock(Parcel p, int header) {
        int size = readSize(p, header);
        if (size == 0) return null;
        int start = p.dataPosition();
        p.setDataPosition(start + size);
        return new int[]{start, start + size};
    }

    /** Reads a typed list of Scope objects (GoogleSignInOptions field 2). */
    public static List<String> readScopeList(Parcel p, int header) {
        int size = readSize(p, header);
        int pos = p.dataPosition();
        int end = pos + size;
        List<String> out = new ArrayList<>();
        if (size == 0) return out;
        int count = p.readInt();
        for (int i = 0; i < count && p.dataPosition() < end; i++) {
            int marker = p.readInt(); // 0 = null, else item size
            if (marker == 0) continue;
            String uri = readScopeObject(p);
            if (uri != null) out.add(uri);
        }
        p.setDataPosition(end);
        return out;
    }

    private static String readScopeObject(Parcel p) {
        int end = validateObjectHeader(p);
        String uri = null;
        while (p.dataPosition() < end) {
            int h = readHeader(p);
            if (fieldId(h) == 2) uri = readString(p, h);
            else skip(p, h);
        }
        p.setDataPosition(end);
        return uri;
    }
}
