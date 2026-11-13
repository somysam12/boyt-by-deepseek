package com.mundo.core.gms;

import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;

/**
 * Builds a Bundle holding ONE Parcelable of a class that does not exist in this
 * process (e.g. com.google.android.gms.auth.api.signin.SignInAccount), so the
 * result Intent can be unparcelled by the guest app's own copy of that class.
 *
 * The Bundle is created in "parcelled" (lazy) form from hand-written bytes in the
 * exact layout Parcel.writeValue(Parcelable) produces:
 *
 *   int  N (=1)
 *   String key
 *   int  VAL_PARCELABLE (4)
 *   [API 33+: int length of what follows]   (Parcel.isLengthPrefixed)
 *   String className
 *   <body written by the class's writeToParcel>
 *
 * Intent.replaceExtras() and Intent.writeToParcel() copy parcelled Bundles verbatim,
 * so the foreign class is never loaded on our side. Do NOT call get()/keySet() on the
 * returned Bundle in this process.
 */
public final class ForeignParcelBundle {

    private static final int BUNDLE_MAGIC = 0x4C444E42; // 'B' 'N' 'D' 'L'
    private static final int VAL_PARCELABLE = 4;

    private ForeignParcelBundle() {}

    /** One Bundle entry whose value is a foreign SafeParcelable. */
    public static final class Entry {
        final String key;
        final String className;
        final SafeParcelHelper.Writable body;

        public Entry(String key, String className, SafeParcelHelper.Writable body) {
            this.key = key;
            this.className = className;
            this.body = body;
        }
    }

    public static Bundle create(String key, String className, SafeParcelHelper.Writable body) {
        return create(new Entry(key, className, body));
    }

    public static Bundle create(Entry... entries) {
        Parcel map = Parcel.obtain();
        Parcel outer = Parcel.obtain();
        try {
            map.writeInt(entries.length);
            for (Entry e : entries) {
                map.writeString(e.key);
                map.writeInt(VAL_PARCELABLE);
                int lenPos = -1;
                if (Build.VERSION.SDK_INT >= 33) {
                    lenPos = map.dataPosition();
                    map.writeInt(-1);
                }
                int valueStart = map.dataPosition();
                map.writeString(e.className);
                e.body.writeSafeParcel(map);
                int end = map.dataPosition();
                if (lenPos >= 0) {
                    map.setDataPosition(lenPos);
                    map.writeInt(end - valueStart);
                    map.setDataPosition(end);
                }
            }
            int length = map.dataSize();

            outer.writeInt(length);
            outer.writeInt(BUNDLE_MAGIC);
            outer.appendFrom(map, 0, length);
            outer.setDataPosition(0);
            return outer.readBundle(); // stays parcelled (lazy)
        } finally {
            map.recycle();
            outer.recycle();
        }
    }
}
