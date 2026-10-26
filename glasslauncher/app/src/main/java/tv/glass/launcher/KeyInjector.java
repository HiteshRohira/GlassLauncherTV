package tv.glass.launcher;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * Persistent key injector for the phone remote. It is not part of the app's
 * runtime: {@link TvRemoteClient} starts it once over the TV's own authorized
 * local ADB connection, as the {@code shell} user, with
 * {@code CLASSPATH=<this APK> app_process /system/bin tv.glass.launcher.KeyInjector}.
 * The shell user may inject input (it is how {@code input keyevent} works),
 * but {@code input} starts a new Java runtime for every press; this process
 * starts once, so each key costs a few milliseconds.
 *
 * <p>Protocol on stdin/stdout, one line each way, nothing else: {@code k CODE}
 * (press), {@code l CODE} (long press), {@code q} (quit). Replies are
 * {@code ok} or {@code err}. Only the remote's fixed key codes are accepted.
 * It opens no socket and exits when its stdin closes (Glass or ADB went away).
 */
public final class KeyInjector {
    static final String VERSION = "1";
    private static final long LONG_PRESS_MS = 600;

    public static void main(String[] args) {
        PrintStream out = new PrintStream(System.out, true);
        Method inject;
        Object inputManager;
        try {
            Class<?> cls = Class.forName("android.hardware.input.InputManager");
            inputManager = cls.getMethod("getInstance").invoke(null);
            inject = cls.getMethod("injectInputEvent", InputEvent.class, int.class);
        } catch (Throwable t) {
            out.println("fail " + t.getClass().getSimpleName());
            return;
        }
        out.println("ready " + VERSION);
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.US_ASCII))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.equals("q")) break;
                String[] parts = line.split(" ");
                int code;
                try { code = parts.length == 2 ? Integer.parseInt(parts[1]) : -1; } catch (NumberFormatException e) { code = -1; }
                if (!allowedKey(code) || !(parts[0].equals("k") || parts[0].equals("l"))) { out.println("err"); continue; }
                boolean ok = parts[0].equals("k") ? press(inject, inputManager, code) : longPress(inject, inputManager, code);
                out.println(ok ? "ok" : "err");
            }
        } catch (Throwable ignored) {
            // stdin closed or broken: exit
        }
    }

    private static boolean press(Method inject, Object im, int code) {
        long now = SystemClock.uptimeMillis();
        return send(inject, im, event(now, now, KeyEvent.ACTION_DOWN, code, 0, 0))
                && send(inject, im, event(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, 0));
    }

    /** Down, then a repeat flagged as a long press, then up: what a held key
     *  produces, so apps see a real long press. */
    private static boolean longPress(Method inject, Object im, int code) {
        long down = SystemClock.uptimeMillis();
        if (!send(inject, im, event(down, down, KeyEvent.ACTION_DOWN, code, 0, 0))) return false;
        SystemClock.sleep(LONG_PRESS_MS);
        send(inject, im, event(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, code, 1, KeyEvent.FLAG_LONG_PRESS));
        return send(inject, im, event(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, 0));
    }

    private static KeyEvent event(long downTime, long time, int action, int code, int repeat, int flags) {
        return new KeyEvent(downTime, time, action, code, repeat, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                flags | KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
    }

    private static boolean send(Method inject, Object im, KeyEvent event) {
        try {
            return Boolean.TRUE.equals(inject.invoke(im, event, 0)); // INJECT_INPUT_EVENT_MODE_ASYNC
        } catch (Throwable t) {
            return false;
        }
    }

    /** The phone remote's fixed keys: directions, OK, Enter, Back, Home,
     *  volume, mute, media and Delete. */
    static boolean allowedKey(int code) {
        switch (code) {
            case 3: case 4: case 19: case 20: case 21: case 22: case 23: case 24: case 25:
            case 66: case 67: case 85: case 89: case 90: case 164: return true;
            default: return false;
        }
    }

    private KeyInjector() { }
}
