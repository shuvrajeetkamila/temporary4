package org.junit.jupiter.api;
import org.opentest4j.TestAbortedException;
public final class Assumptions {
    public static void assumeTrue(boolean c) { if (!c) throw new TestAbortedException("assumption not met"); }
    public static void assumeTrue(boolean c, String m) { if (!c) throw new TestAbortedException(m); }
    public static void assumeFalse(boolean c) { assumeTrue(!c); }
    public static void assumeFalse(boolean c, String m) { assumeTrue(!c, m); }
}
