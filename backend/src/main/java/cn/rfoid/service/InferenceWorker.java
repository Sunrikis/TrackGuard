package cn.rfoid.service;

import cn.rfoid.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** One private, persistent Python subprocess. Only TaskService's serial queue submits work. */
@Service
public class InferenceWorker {
    private static final Logger LOG = LoggerFactory.getLogger(InferenceWorker.class);
    private final AppProperties config;
    private final ObjectMapper json;
    private final List<String> command;
    private final long cancelGraceMs;
    private volatile Session current;
    private volatile boolean closed;

    @Autowired
    public InferenceWorker(AppProperties config, ObjectMapper json) {
        this(config, json, List.of(config.python(), "-u", config.worker(), "--serve"), 5000);
    }

    // A real subprocess fixture exercises the protocol and lifecycle without requiring a GPU in Java tests.
    InferenceWorker(AppProperties config, ObjectMapper json, List<String> command, long cancelGraceMs) {
        this.config = config; this.json = json; this.command = List.copyOf(command); this.cancelGraceMs = cancelGraceMs;
    }

    private static final class Session {
        final Process process;
        final BufferedWriter input;
        Thread errorReader;
        final long started = System.nanoTime();
        final CompletableFuture<JsonNode> ready = new CompletableFuture<>();
        final ConcurrentMap<String, CompletableFuture<Void>> pending = new ConcurrentHashMap<>();
        volatile String state = "STARTING";
        Session(Process process) {
            this.process = process;
            input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        }
    }

    public static class StartupException extends IOException {
        StartupException(String message, Throwable cause) { super(message, cause); }
    }

    public static class TaskTimeoutException extends IOException {
        TaskTimeoutException() { super("Inference deadline exceeded"); }
    }

    public record RunMetrics(double workerWaitMs) {}

    private synchronized Session session() throws IOException {
        if (closed) throw new IOException("Inference worker has been shut down");
        if (current != null && current.process.isAlive() && !current.ready.isCompletedExceptionally()) return current;
        if (current != null) discard(current, new IOException("Worker exited"));
        Path logs = Path.of(config.storage(), "worker");
        Files.createDirectories(logs);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("PYTHONUTF8", "1");
        builder.environment().put("YOLO_AUTOINSTALL", "false");
        builder.environment().put("YOLO_CONFIG_DIR", Path.of(config.storage(), "model-config").toString());
        // TrackGuardApplication loads .env values as Java system properties. Copy the
        // allowlisted inference and API settings into the private worker environment.
        for (String name : List.of("RFOID_BATCH_SIZE", "RFOID_IMAGE_SIZE", "RFOID_HALF", "RFOID_CPU_THREADS",
                                   "DEEPSEEK_VISION_ENABLED", "DEEPSEEK_API_KEY", "DEEPSEEK_API_URL",
                                   "DEEPSEEK_VISION_MODEL", "DEEPSEEK_TIMEOUT_SECONDS", "DEEPSEEK_MAX_IMAGES")) {
            String value = System.getProperty(name);
            if (value != null) builder.environment().put(name, value);
        }
        Session created = new Session(builder.start());
        created.errorReader = Thread.ofPlatform().daemon().name("trackguard-worker-log-" + created.process.pid()).start(() -> {
            // Own the log in Java so shutdown can explicitly close it before returning.
            try (var errors = created.process.getErrorStream();
                 var log = Files.newOutputStream(logs.resolve("server.log"), StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                errors.transferTo(log);
            } catch (IOException e) {
                LOG.warn("Could not copy inference worker stderr to server.log", e);
            }
        });
        current = created;
        Thread.ofPlatform().daemon().name("trackguard-worker-protocol-" + created.process.pid()).start(() -> read(created));
        LOG.info("Started persistent inference worker, pid={}", created.process.pid());
        return created;
    }

    private void read(Session session) {
        try (var reader = new BufferedReader(new InputStreamReader(session.process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode message = json.readTree(line);
                switch (message.path("type").asText()) {
                    case "ready" -> {
                        if (message.path("protocol").asInt() != 1) throw new IOException("Unsupported worker protocol");
                        session.state = "READY";
                        session.ready.complete(message);
                        LOG.info("Inference worker ready, pid={}, startupMs={}", session.process.pid(), message.path("startupMs"));
                    }
                    case "done" -> {
                        CompletableFuture<Void> pending = session.pending.remove(message.path("id").asText());
                        if (pending != null) {
                            session.state = "READY";
                            if (message.path("restartRequired").asBoolean(false)) {
                                discard(session, new IOException("Worker requested recovery after an inference error"));
                            }
                            pending.complete(null);
                        }
                    }
                    case "startup_error" -> throw new IOException("Model initialization failed; see storage/worker/server.log");
                    default -> throw new IOException("Invalid inference worker response");
                }
            }
            throw new EOFException("Inference worker disconnected");
        } catch (Exception e) {
            discard(session, e);
        }
    }

    private synchronized void discard(Session session, Exception reason) {
        session.state = "FAILED";
        session.ready.completeExceptionally(reason);
        session.pending.values().forEach(f -> f.completeExceptionally(reason));
        session.pending.clear();
        if (session.process.isAlive()) {
            session.process.descendants().forEach(ProcessHandle::destroyForcibly);
            session.process.destroyForcibly();
        }
        // Termination is asynchronous; Windows keeps redirected log files locked until exit.
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try {
            while (session.process.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    LOG.warn("Inference worker did not exit after termination, pid={}", session.process.pid());
                    break;
                }
                try { session.process.waitFor(remaining, TimeUnit.NANOSECONDS); }
                catch (InterruptedException e) { interrupted = true; }
            }
            while (session.errorReader.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    LOG.warn("Inference worker log reader did not stop, pid={}", session.process.pid());
                    break;
                }
                try { TimeUnit.NANOSECONDS.timedJoin(session.errorReader, remaining); }
                catch (InterruptedException e) { interrupted = true; }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
        try { session.input.close(); } catch (IOException ignored) { }
        if (current == session) current = null;
    }

    private void send(Session session, Map<String, Object> message) throws IOException {
        synchronized (session.input) {
            session.input.write(json.writeValueAsString(message));
            session.input.newLine();
            session.input.flush();
        }
    }

    private Session awaitReady(BooleanSupplier cancelled) throws IOException, InterruptedException {
        Session session;
        try { session = session(); }
        catch (IOException e) { throw new StartupException("Cannot start inference worker", e); }
        while (true) {
            if (cancelled.getAsBoolean()) throw new CancellationException();
            try {
                session.ready.get(100, TimeUnit.MILLISECONDS);
                return session;
            } catch (TimeoutException e) {
                if (Duration.ofNanos(System.nanoTime() - session.started).toSeconds() >= config.workerStartupTimeoutSeconds()) {
                    discard(session, e);
                    throw new StartupException("Inference worker startup timed out", e);
                }
            } catch (ExecutionException e) {
                discard(session, e);
                throw new StartupException("Inference worker initialization failed", e.getCause());
            }
        }
    }

    void start() throws IOException, InterruptedException { awaitReady(() -> false); }

    public RunMetrics execute(String id, Path request, Path output, BooleanSupplier cancelled, IntConsumer progress)
            throws IOException, InterruptedException {
        long waiting = System.nanoTime();
        Session session = awaitReady(cancelled);
        double workerWaitMs = (System.nanoTime() - waiting) / 1_000_000.0;
        if (cancelled.getAsBoolean()) throw new CancellationException();
        var finished = new CompletableFuture<Void>();
        if (!session.pending.isEmpty()) throw new IllegalStateException("Only one inference task may run at a time");
        session.pending.put(id, finished);
        long started = System.nanoTime(), cancelAt = 0;
        int lastProgress = 0;
        try {
            session.state = "BUSY";
            send(session, Map.of("type", "run", "id", id, "request", request.toString(), "output", output.toString()));
            while (true) {
                if (cancelled.getAsBoolean() && cancelAt == 0) {
                    cancelAt = System.nanoTime();
                    send(session, Map.of("type", "cancel", "id", id));
                }
                if (cancelAt != 0 && (System.nanoTime() - cancelAt) / 1_000_000 >= cancelGraceMs) {
                    discard(session, new IOException("Worker did not acknowledge cancellation"));
                    throw new CancellationException();
                }
                if (cancelAt == 0 && Duration.ofNanos(System.nanoTime() - started).toSeconds() >= config.inferenceTimeoutSeconds()) {
                    throw new TaskTimeoutException();
                }
                try {
                    finished.get(100, TimeUnit.MILLISECONDS);
                    if (cancelled.getAsBoolean()) throw new CancellationException();
                    return new RunMetrics(workerWaitMs);
                } catch (TimeoutException ignored) {
                    Path path = output.resolve("progress.json");
                    if (Files.isRegularFile(path)) {
                        try {
                            int value = json.readTree(path.toFile()).path("progress").asInt();
                            if (value > lastProgress) { progress.accept(value); lastProgress = value; }
                        } catch (IOException ignoredRead) { /* The atomic rename can be briefly unavailable on Windows. */ }
                    }
                } catch (ExecutionException e) {
                    throw new IOException("Persistent worker failed; it will restart for the next task", e.getCause());
                }
            }
        } catch (IOException | InterruptedException e) {
            discard(session, e);
            throw e;
        } catch (RuntimeException e) {
            if (!(e instanceof CancellationException)) discard(session, e);
            throw e;
        } finally {
            session.pending.remove(id, finished);
        }
    }

    public Map<String, Object> status() {
        Session session = current;
        if (session == null || !session.process.isAlive()) return Map.of("mode", "PERSISTENT", "state", closed ? "STOPPED" : "UNAVAILABLE", "ready", false);
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("mode", "PERSISTENT"); info.put("state", session.state); info.put("pid", session.process.pid());
        info.put("ready", session.ready.isDone() && !session.ready.isCompletedExceptionally());
        JsonNode ready = session.ready.isCompletedExceptionally() ? null : session.ready.getNow(null);
        if (ready != null) for (String field : List.of("startupMs", "dependencyMs", "device", "batchSize", "imageSize", "precision")) {
            if (ready.has(field)) info.put(field, json.convertValue(ready.get(field), Object.class));
        }
        return info;
    }

    @PreDestroy public synchronized void close() {
        closed = true;
        if (current != null) discard(current, new IOException("Backend stopped"));
    }
}
