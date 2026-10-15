import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;

/**
 * A JUnit-5-shaped runner for running the suites locally.
 *
 * <p>Maven Central is unreachable from the sandbox this project is
 * developed in, so the real JUnit jars cannot be fetched. CI uses the
 * real thing through Gradle; this exists only so the suites can be run
 * before pushing. It understands the handful of annotations the tests
 * actually use and nothing else.
 *
 * <p>Takes test class names as arguments.
 */
public final class Runner {
    public static void main(String[] args) throws Exception {
        int pass = 0, fail = 0, skip = 0;
        List<String> failures = new ArrayList<>();
        for (String name : args) {
            Class<?> c;
            try { c = Class.forName(name); } catch (Throwable t) {
                failures.add(name + ": cannot load: " + t); fail++; continue;
            }
            List<Method> methods = new ArrayList<>();
            for (Method m : c.getDeclaredMethods()) {
                for (Annotation a : m.getAnnotations()) {
                    String an = a.annotationType().getName();
                    if (an.equals("org.junit.jupiter.api.Test")
                            || an.equals("org.junit.jupiter.params.ParameterizedTest")) {
                        methods.add(m);
                    }
                }
            }
            methods.sort(Comparator.comparing(Method::getName));
            for (Method m : methods) {
                Object[][] rows = {{}};
                for (Annotation a : m.getAnnotations()) {
                    if (a.annotationType().getName()
                            .equals("org.junit.jupiter.params.provider.CsvSource")) {
                        String[] lines = (String[]) a.annotationType()
                                .getMethod("value").invoke(a);
                        rows = new Object[lines.length][];
                        for (int i = 0; i < lines.length; i++) {
                            rows[i] = coerce(m.getParameterTypes(), split(lines[i]));
                        }
                    }
                }
                for (Object[] row : rows) {
                    Object inst;
                    try {
                        Constructor<?> ctor = c.getDeclaredConstructor();
                        ctor.setAccessible(true);
                        inst = ctor.newInstance();
                    } catch (Throwable t) {
                        failures.add(c.getName() + ": cannot construct: " + t);
                        fail++; continue;
                    }
                    m.setAccessible(true);
                    try {
                        m.invoke(inst, row);
                        pass++;
                    } catch (InvocationTargetException e) {
                        Throwable t = e.getCause();
                        if (t.getClass().getName()
                                .equals("org.opentest4j.TestAbortedException")) {
                            skip++;
                        } else {
                            fail++;
                            failures.add(c.getSimpleName() + "." + m.getName()
                                    + (row.length > 0 ? Arrays.toString(row) : "")
                                    + ": " + t);
                            StackTraceElement[] st = t.getStackTrace();
                            for (int i = 0; i < Math.min(3, st.length); i++) {
                                failures.add("      at " + st[i]);
                            }
                        }
                    }
                }
            }
        }
        for (String f : failures) { System.out.println("FAIL " + f); }
        System.out.println("RESULT pass=" + pass + " fail=" + fail + " skip=" + skip);
        if (fail > 0) { System.exit(1); }
    }

    private static String[] split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (char ch : line.toCharArray()) {
            if (ch == '\'') { q = !q; }
            else if (ch == ',' && !q) { out.add(cur.toString().trim()); cur.setLength(0); }
            else { cur.append(ch); }
        }
        out.add(cur.toString().trim());
        return out.toArray(new String[0]);
    }

    private static Object[] coerce(Class<?>[] types, String[] raw) {
        Object[] out = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            String s = i < raw.length ? raw[i] : "";
            if (types[i] == double.class) { out[i] = Double.parseDouble(s); }
            else if (types[i] == int.class) { out[i] = Integer.parseInt(s); }
            else if (types[i] == long.class) { out[i] = Long.parseLong(s); }
            else if (types[i] == boolean.class) { out[i] = Boolean.parseBoolean(s); }
            else { out[i] = s; }
        }
        return out;
    }
}
