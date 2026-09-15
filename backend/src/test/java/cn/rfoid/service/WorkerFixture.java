package cn.rfoid.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;

/** A subprocess, not a mock stream: exercises pipes, EOF, cancellation and process death. */
public class WorkerFixture {
    public static void main(String[] args) throws Exception {
        var json = new ObjectMapper();
        var out = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
        if (args.length > 0 && args[0].equals("slowstart")) Thread.sleep(10_000);
        if (args.length > 0 && args[0].equals("failstart")) { out.println("{\"type\":\"startup_error\"}"); return; }
        out.println("{\"type\":\"ready\",\"protocol\":1,\"startupMs\":1}");
        String active = "", mode = "";
        Path output = null;
        try (var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                var command = json.readTree(line);
                if (command.path("type").asText().equals("run")) {
                    active = command.path("id").asText();
                    mode = Files.readString(Path.of(command.path("request").asText())).strip();
                    output = Path.of(command.path("output").asText());
                    if (mode.equals("crash")) System.exit(7);
                    if (mode.equals("malformed")) { out.println("invalid protocol"); continue; }
                    if (mode.equals("wait") || mode.equals("ignore-cancel")) {
                        Files.writeString(output.resolve("progress.json"), "{\"progress\":40}");
                        continue;
                    }
                    Files.writeString(output.resolve("result.json"), json.writeValueAsString(Map.of("id", active, "pid", ProcessHandle.current().pid())));
                    // An old completion must not release the current request.
                    out.println(json.writeValueAsString(Map.of("type", "done", "id", "old-request")));
                    out.println(json.writeValueAsString(Map.of("type", "done", "id", active)));
                } else if (command.path("type").asText().equals("cancel") && command.path("id").asText().equals(active) && !mode.equals("ignore-cancel")) {
                    Files.writeString(output.resolve("result.json"), "{\"ok\":false,\"code\":\"CANCELLED\"}");
                    out.println(json.writeValueAsString(Map.of("type", "done", "id", active)));
                }
            }
        }
    }
}
