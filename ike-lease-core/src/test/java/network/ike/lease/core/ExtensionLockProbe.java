package network.ike.lease.core;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * A Maven build's side of the lock, in a separate process for
 * {@link BuildLockInteropTest}: the released {@code ike-workspace-extension}'s
 * own {@code BuildLock}, called exactly as its build-session spy calls it.
 * It prints how it came out and holds the lock until its standard input
 * closes, the same contract as the extension's own test probe.
 *
 * <p>The extension's class is package-private, so it is reached
 * reflectively; on the class path that is allowed.
 *
 * <p>Arguments: the tree's root, the wait timeout in milliseconds.
 */
public final class ExtensionLockProbe {

    /** The goals the probe records, as a Maven build would. */
    static final List<String> GOALS = List.of("clean", "compile");

    /** The {@code -pl} the probe records. */
    static final List<String> PROJECTS = List.of("komet/kview");

    private ExtensionLockProbe() { }

    /**
     * Takes the lock through the extension and holds it.
     *
     * @param args the root and the timeout in milliseconds
     * @throws Exception if the extension cannot be reached
     */
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Duration timeout = Duration.ofMillis(Long.parseLong(args[1]));
        Class<?> lock = Class.forName("network.ike.extension.BuildLock");
        Class<?> holderType = Class.forName("network.ike.extension.BuildLock$Holder");
        Method current = holderType.getDeclaredMethod("current", Path.class, List.class, List.class);
        current.setAccessible(true);
        Object holder = current.invoke(null, root, GOALS, PROJECTS);
        Method acquire = lock.getDeclaredMethod("acquire", holderType, Duration.class, Duration.class,
                Consumer.class);
        acquire.setAccessible(true);
        Consumer<String> log = line -> System.out.println("WAITING " + line);
        Object outcome = acquire.invoke(null, holder, timeout, Duration.ofMillis(200), log);
        String kind = outcome.getClass().getSimpleName();
        String result = switch (kind) {
            case "Acquired" -> "ACQUIRED waited=" + ((Duration) accessor(outcome, "waited")).toMillis();
            case "TimedOut" -> {
                Method message = lock.getDeclaredMethod("timedOutMessage", outcome.getClass());
                message.setAccessible(true);
                yield "TIMEDOUT " + message.invoke(null, outcome);
            }
            case "Nested" -> "NESTED " + accessor(outcome, "reason");
            default -> "UNAVAILABLE " + accessor(outcome, "reason");
        };
        System.out.println(result);
        System.out.flush();
        if (kind.equals("Acquired")) {
            while (System.in.read() >= 0) {
                // Hold until the test closes our input.
            }
            Object handle = accessor(outcome, "handle");
            Method close = handle.getClass().getDeclaredMethod("close");
            close.setAccessible(true);
            close.invoke(handle);
        }
    }

    private static Object accessor(Object record, String name) throws Exception {
        Method method = record.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(record);
    }
}
