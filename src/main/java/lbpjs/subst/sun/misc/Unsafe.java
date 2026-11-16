package lbpjs.subst.sun.misc;

import java.lang.reflect.Field;

/** Stand-in for sun.misc.Unsafe in the TeaVM build: every call fails, so JOML uses NIO. */
public final class Unsafe
{
    private Unsafe() { throw new UnsupportedOperationException("sun.misc.Unsafe"); }

    private static UnsupportedOperationException no() { return new UnsupportedOperationException("sun.misc.Unsafe"); }

    public int arrayBaseOffset(Class<?> c) { throw no(); }
    public long objectFieldOffset(Field f) { throw no(); }
    public double getDouble(Object o, long offset) { throw no(); }
    public float getFloat(long address) { throw no(); }
    public float getFloat(Object o, long offset) { throw no(); }
    public int getInt(Object o, long offset) { throw no(); }
    public long getLong(long address) { throw no(); }
    public long getLong(Object o, long offset) { throw no(); }
    public void putDouble(Object o, long offset, double v) { throw no(); }
    public void putFloat(Object o, long offset, float v) { throw no(); }
    public void putInt(Object o, long offset, int v) { throw no(); }
    public void putLong(Object o, long offset, long v) { throw no(); }
}
