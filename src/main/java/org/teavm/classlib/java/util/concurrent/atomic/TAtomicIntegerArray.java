package org.teavm.classlib.java.util.concurrent.atomic;
public class TAtomicIntegerArray { private final int[] a; public TAtomicIntegerArray(int n) { a = new int[n]; } public TAtomicIntegerArray(int[] v) { a = v.clone(); }
 public int length() { return a.length; } public int get(int i) { return a[i]; } public void set(int i, int v) { a[i] = v; } }
