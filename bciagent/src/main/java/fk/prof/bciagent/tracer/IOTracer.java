package fk.prof.bciagent.tracer;

import com.google.common.annotations.VisibleForTesting;
import fk.prof.bciagent.FdAccessor;

import java.io.FileDescriptor;
import java.util.Objects;

public class IOTracer {

    /**
     * Threshold in nanoseconds.
     * Blocking IO calls that takes more time than this threshold shall be recorded.
     */
    private static final long DEFAULT_THRESHOLD = Long.MAX_VALUE;

    private static volatile long latencyThreshold = DEFAULT_THRESHOLD;

    public static long getLatencyThresholdNanos() {
        return latencyThreshold;
    }

    static void setLatencyThresholdNanos(long threshold) {
        latencyThreshold = threshold;
    }

    private SocketOpTracer sTracer;

    private FileOpTracer fTracer;

    @VisibleForTesting
    public IOTracer(SocketOpTracer sTracer, FileOpTracer fTracer) {
        Objects.requireNonNull(sTracer);
        Objects.requireNonNull(fTracer);

        this.sTracer = sTracer;
        this.fTracer = fTracer;
    }

    public SocketOpTracer forSocket() {
        return sTracer;
    }

    public FileOpTracer forFile() {
        return fTracer;
    }

    public IOTracer() {
        this(new SocketOpTracer(), new FileOpTracer());
    }

    public static class SocketOpTracer {

        private native void _accept(int fd, String address, long ts, long elapsed);

        public void accept(FileDescriptor fd, String address, long elapsed) {
            if (elapsed >= latencyThreshold && fd.valid()) {
                _accept(FdAccessor.getFd(fd), address, System.currentTimeMillis(), elapsed);
            }
        }

        public void accept(int fd, String address, long elapsed) {
            if (elapsed >= latencyThreshold && fd >= 0) {
                _accept(fd, address, System.currentTimeMillis(), elapsed);
            }
        }

        private native void _connect(int fd, String address, long ts, long elapsed);

        public void connect(FileDescriptor fd, String address, long elapsed) {
            if (elapsed >= latencyThreshold && fd.valid()) {
                _connect(FdAccessor.getFd(fd), address, System.currentTimeMillis(), elapsed);
            }
        }

        public void connect(int fd, String address, long elapsed) {
            if (elapsed >= latencyThreshold && fd >= 0) {
                _connect(fd, address, System.currentTimeMillis(), elapsed);
            }
        }

        private native void _read(int fd, long count, long ts, long elapsed, boolean timeout);

        public void read(FileDescriptor fd, long count, long elapsed, boolean timeout) {
            if (elapsed >= latencyThreshold && fd.valid()) {
                _read(FdAccessor.getFd(fd), count, System.currentTimeMillis(), elapsed, timeout);
            }
        }

        private native void _write(int fd, long count, long ts, long elapsed);

        public void write(FileDescriptor fd, long count, long elapsed) {
            if (elapsed >= latencyThreshold && fd.valid()) {
                _write(FdAccessor.getFd(fd), count, System.currentTimeMillis(), elapsed);
            }
        }
    }

    public static class FileOpTracer {

        private native void _open(int fd, String path, long ts, long elapsed);

        public void open(FileDescriptor fd, String path, long elapsed) {
            if (elapsed >= latencyThreshold && fd.valid()) {
                _open(FdAccessor.getFd(fd), path, System.currentTimeMillis(), elapsed);
            }
        }

        private native void _read(int fd, long count, long ts, long elapsed);

        public void read(FileDescriptor fd, long count, long elapsed) {
            if (elapsed >= latencyThreshold && fd.valid()) {
                _read(FdAccessor.getFd(fd), count, System.currentTimeMillis(), elapsed);
            }
        }

        private native void _write(int fd, long count, long ts, long elapsed);

        public void write(FileDescriptor fd, long count, long elapsed) {
            if (elapsed >= latencyThreshold && fd.valid()) {
                _write(FdAccessor.getFd(fd), count, System.currentTimeMillis(), elapsed);
            }
        }
    }
}