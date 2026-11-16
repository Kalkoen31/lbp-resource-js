package org.teavm.classlib.java.util.concurrent.atomic;
public class TAtomicLongArray { private final long[] a; public TAtomicLongArray(int n) { a = new long[n]; } public TAtomicLongArray(long[] v) { a = v.clone(); }
 public int length() { return a.length; } public long get(int i) { return a[i]; } public void set(int i, long v) { a[i] = v; } }
