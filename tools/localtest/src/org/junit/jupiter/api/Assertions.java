package org.junit.jupiter.api;
import java.util.Arrays;
import java.util.function.Supplier;
public final class Assertions {
    private static String msg(Object m) { return m == null ? "" : String.valueOf(m); }
    private static String msg(Supplier<String> m) { return m == null ? "" : m.get(); }
    public static void fail(String m) { throw new AssertionError(m); }
    public static <T> T fail() { throw new AssertionError("failed"); }
    public static void assertTrue(boolean c) { if (!c) throw new AssertionError("expected true"); }
    public static void assertTrue(boolean c, String m) { if (!c) throw new AssertionError(msg(m)); }
    public static void assertTrue(boolean c, Supplier<String> m) { if (!c) throw new AssertionError(msg(m)); }
    public static void assertFalse(boolean c) { assertTrue(!c); }
    public static void assertFalse(boolean c, String m) { assertTrue(!c, m); }
    public static void assertFalse(boolean c, Supplier<String> m) { assertTrue(!c, m); }
    public static void assertNull(Object o) { if (o != null) throw new AssertionError("expected null, was " + o); }
    public static void assertNull(Object o, String m) { if (o != null) throw new AssertionError(msg(m) + " (was " + o + ")"); }
    public static void assertNotNull(Object o) { if (o == null) throw new AssertionError("expected non-null"); }
    public static void assertNotNull(Object o, String m) { if (o == null) throw new AssertionError(msg(m)); }
    public static void assertSame(Object a, Object b) { if (a != b) throw new AssertionError("expected same"); }
    public static void assertSame(Object a, Object b, String m) { if (a != b) throw new AssertionError(msg(m)); }
    public static void assertNotSame(Object a, Object b) { if (a == b) throw new AssertionError("expected different"); }
    public static void assertEquals(Object a, Object b) { if (a == null ? b != null : !a.equals(b)) throw new AssertionError("expected " + a + " but was " + b); }
    public static void assertEquals(Object a, Object b, String m) { if (a == null ? b != null : !a.equals(b)) throw new AssertionError(msg(m) + " -- expected " + a + " but was " + b); }
    public static void assertEquals(long a, long b) { if (a != b) throw new AssertionError("expected " + a + " but was " + b); }
    public static void assertEquals(long a, long b, String m) { if (a != b) throw new AssertionError(msg(m) + " -- expected " + a + " but was " + b); }
    public static void assertEquals(long a, long b, Supplier<String> m) { if (a != b) throw new AssertionError(msg(m) + " -- expected " + a + " but was " + b); }
    public static void assertEquals(int a, int b) { if (a != b) throw new AssertionError("expected " + a + " but was " + b); }
    public static void assertEquals(int a, int b, String m) { if (a != b) throw new AssertionError(msg(m) + " -- expected " + a + " but was " + b); }
    public static void assertEquals(double a, double b, double eps) { if (!(Math.abs(a - b) <= eps) ) throw new AssertionError("expected " + a + " +/- " + eps + " but was " + b); }
    public static void assertEquals(double a, double b, double eps, String m) { if (!(Math.abs(a - b) <= eps)) throw new AssertionError(msg(m) + " -- expected " + a + " +/- " + eps + " but was " + b); }
    public static void assertEquals(double a, double b, double eps, Supplier<String> m) { if (!(Math.abs(a - b) <= eps)) throw new AssertionError(msg(m) + " -- expected " + a + " +/- " + eps + " but was " + b); }
    public static void assertArrayEquals(int[] a, int[] b) { if (!Arrays.equals(a, b)) throw new AssertionError("arrays differ"); }
    public static void assertArrayEquals(double[] a, double[] b, double eps) { if (a.length != b.length) throw new AssertionError("lengths differ"); for (int i = 0; i < a.length; i++) { if (Math.abs(a[i]-b[i]) > eps) throw new AssertionError("index " + i); } }
    public static <T extends Throwable> T assertThrows(Class<T> type, Runnable r) { return assertThrows(type, r, null); }
    @SuppressWarnings("unchecked")
    public static <T extends Throwable> T assertThrows(Class<T> type, Runnable r, String m) {
        try { r.run(); } catch (Throwable t) { if (type.isInstance(t)) { return (T) t; } throw new AssertionError(msg(m) + " -- wrong exception " + t); }
        throw new AssertionError(msg(m) + " -- nothing thrown, expected " + type.getSimpleName());
    }
    public static void assertDoesNotThrow(Runnable r) { try { r.run(); } catch (Throwable t) { throw new AssertionError("threw " + t); } }
}
