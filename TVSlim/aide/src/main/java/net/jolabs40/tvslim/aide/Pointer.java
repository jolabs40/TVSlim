package net.jolabs40.tvslim.aide;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;
import java.io.InterruptedIOException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * A mouse added to the TV through {@code /dev/uhid}, as scrcpy does: to Android it is a real input device, so the
 * TV draws its pointer, which no injected event makes it do. The shell user may open {@code /dev/uhid} (group
 * {@code uhid}). Created on the first move, gone with the file descriptor, so a killed helper leaves no mouse behind
 * and no button down.
 */
final class Pointer {

    private static final String PATH = "/dev/uhid";

    // linux/uhid.h
    private static final int UHID_DESTROY = 1;
    private static final int UHID_CREATE2 = 11;
    private static final int UHID_INPUT2 = 12;
    private static final short BUS_VIRTUAL = 0x06;

    /** Three buttons, then X, Y and the wheel, each a signed byte of relative motion. */
    private static final byte[] DESCRIPTOR = {
            0x05, 0x01, 0x09, 0x02, (byte) 0xA1, 0x01,
            0x09, 0x01, (byte) 0xA1, 0x00,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, (byte) 0x95, 0x03, 0x75, 0x01, (byte) 0x81, 0x02,
            (byte) 0x95, 0x01, 0x75, 0x05, (byte) 0x81, 0x01,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, (byte) 0x81, 0x25, 0x7F, 0x75, 0x08, (byte) 0x95, 0x03,
            (byte) 0x81, 0x06,
            (byte) 0xC0, (byte) 0xC0,
    };

    private static final int MAIN_BUTTON = 1;

    private FileDescriptor device;

    /** Whether the helper may add a mouse here, told to the phone before any is made. */
    static boolean available() {
        File file = new File(PATH);
        return file.canRead() && file.canWrite();
    }

    /** Moves by any amount: a report carries at most 127 each way, so a long move takes several. */
    synchronized boolean move(int dx, int dy, int wheel) {
        if (!open()) return false;
        while (dx != 0 || dy != 0 || wheel != 0) {
            int x = clamp(dx);
            int y = clamp(dy);
            int w = clamp(wheel);
            if (!report(0, x, y, w)) return false;
            dx -= x;
            dy -= y;
            wheel -= w;
        }
        return true;
    }

    synchronized boolean click() {
        return open() && report(MAIN_BUTTON, 0, 0, 0) && report(0, 0, 0, 0);
    }

    /** Also from the shutdown hook, hence the lock. */
    synchronized void close() {
        FileDescriptor current = device;
        device = null;
        if (current == null) return;
        try {
            Os.write(current, event(UHID_DESTROY, 0).array(), 0, 4);
        } catch (ErrnoException | InterruptedIOException ignored) {
            // Closing destroys it as well.
        }
        try {
            Os.close(current);
        } catch (ErrnoException ignored) {
        }
    }

    private boolean open() {
        if (device != null) return true;
        try {
            FileDescriptor fd = Os.open(PATH, OsConstants.O_RDWR, 0);
            ByteBuffer create = event(UHID_CREATE2, 128 + 64 + 64 + 2 + 2 + 4 * 4 + DESCRIPTOR.length);
            create.put("TV Slim pointer".getBytes(StandardCharsets.US_ASCII));
            // Through Buffer: ByteBuffer's own position(int) is missing from older Android versions.
            ((Buffer) create).position(4 + 128 + 64 + 64);
            create.putShort((short) DESCRIPTOR.length).putShort(BUS_VIRTUAL);
            create.putInt(0).putInt(0).putInt(0).putInt(0);
            create.put(DESCRIPTOR);
            Os.write(fd, create.array(), 0, create.capacity());
            device = fd;
            return true;
        } catch (ErrnoException | InterruptedIOException e) {
            Log.w(Injection.TAG, "no pointer: " + e);
            return false;
        }
    }

    private boolean report(int buttons, int dx, int dy, int wheel) {
        ByteBuffer input = event(UHID_INPUT2, 2 + 4);
        input.putShort((short) 4).put((byte) buttons).put((byte) dx).put((byte) dy).put((byte) wheel);
        try {
            Os.write(device, input.array(), 0, input.capacity());
            return true;
        } catch (ErrnoException | InterruptedIOException e) {
            Log.w(Injection.TAG, "pointer lost: " + e);
            close();
            return false;
        }
    }

    /** The event's type, then its body, zeroed: the kernel reads what follows the fields as zeros too. */
    private static ByteBuffer event(int type, int body) {
        ByteBuffer buffer = ByteBuffer.allocate(4 + body).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(type);
        return buffer;
    }

    private static int clamp(int value) {
        return Math.max(-127, Math.min(127, value));
    }
}
