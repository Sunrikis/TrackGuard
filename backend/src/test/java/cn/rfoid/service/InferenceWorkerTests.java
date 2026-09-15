package cn.rfoid.service;

import cn.rfoid.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class InferenceWorkerTests {
    @TempDir Path temp;
    final ObjectMapper json = new ObjectMapper();
    InferenceWorker worker;

    InferenceWorker create(String... arguments) {
        var config = new AppProperties(temp.toString(), "unused", "worker.py", "model.pt", 2, 120, 8, 2);
        String executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        var command = new ArrayList<>(List.of(executable, "-cp", System.getProperty("java.class.path"), WorkerFixture.class.getName()));
        command.addAll(List.of(arguments));
        worker = new InferenceWorker(config, json, command, 250);
        return worker;
    }
    @AfterEach void close() { if (worker != null) worker.close(); }

    Path request(String mode) throws Exception {
        Path output = Files.createDirectory(temp.resolve(UUID.randomUUID() + "-中文 空格"));
        Files.writeString(output.resolve("request.json"), mode);
        return output;
    }

    void run(Path output, AtomicBoolean cancelled) throws Exception {
        worker.execute(output.getFileName().toString(), output.resolve("request.json"), output, cancelled::get, value -> {
            if (value == 40) cancelled.set(true);
        });
    }

    long pid() { return ((Number) worker.status().get("pid")).longValue(); }

    @Test void repeatedRequestsReuseProcessAndDoNotMixResults() throws Exception {
        create().start(); long pid = pid();
        Path a = request("ok"), b = request("ok");
        run(a, new AtomicBoolean()); run(b, new AtomicBoolean());
        assertEquals(pid, pid());
        assertEquals(a.getFileName().toString(), json.readTree(a.resolve("result.json").toFile()).path("id").asText());
        assertEquals(b.getFileName().toString(), json.readTree(b.resolve("result.json").toFile()).path("id").asText());
        assertEquals("READY", worker.status().get("state"));
    }

    @Test void cooperativeCancellationKeepsWorkerForNextRequest() throws Exception {
        create().start(); long pid = pid();
        assertThrows(CancellationException.class, () -> run(request("wait"), new AtomicBoolean()));
        run(request("ok"), new AtomicBoolean());
        assertEquals(pid, pid());
    }

    @Test void cancellationBeforeDispatchDoesNotAffectWorker() throws Exception {
        create().start(); long pid = pid();
        assertThrows(CancellationException.class, () -> run(request("ok"), new AtomicBoolean(true)));
        run(request("ok"), new AtomicBoolean());
        assertEquals(pid, pid());
    }

    @Test void unresponsiveCancellationRestartsWorkerOnNextRequest() throws Exception {
        create().start(); long pid = pid();
        assertThrows(CancellationException.class, () -> run(request("ignore-cancel"), new AtomicBoolean()));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        run(request("ok"), new AtomicBoolean());
        assertNotEquals(pid, pid());
    }

    @Test void timeoutRestartsWorkerWithoutCompletingTheNextRequestEarly() throws Exception {
        create().start(); long pid = pid();
        Path output = request("wait");
        assertThrows(InferenceWorker.TaskTimeoutException.class,
            () -> worker.execute("timeout", output.resolve("request.json"), output, () -> false, value -> {}));
        run(request("ok"), new AtomicBoolean());
        assertNotEquals(pid, pid());
    }

    @Test void crashAndMalformedResponseRecoverOnNextRequest() throws Exception {
        create().start();
        for (String mode : List.of("crash", "malformed")) {
            long pid = pid();
            assertThrows(IOException.class, () -> run(request(mode), new AtomicBoolean()));
            run(request("ok"), new AtomicBoolean());
            assertNotEquals(pid, pid());
        }
    }

    @Test void startupFailureAndTimeoutAreReportedSeparately() {
        create("failstart");
        assertThrows(InferenceWorker.StartupException.class, worker::start);
        worker.close();
        create("slowstart");
        assertThrows(InferenceWorker.StartupException.class, worker::start);
    }

    @Test void shutdownTerminatesWorkerAndPreventsRespawn() throws Exception {
        create().start(); long pid = pid();
        worker.close();
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        Files.delete(temp.resolve("worker/server.log"));
        assertThrows(InferenceWorker.StartupException.class, worker::start);
        assertEquals("STOPPED", worker.status().get("state"));
    }
}
