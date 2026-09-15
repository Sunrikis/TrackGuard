package cn.rfoid.config;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.nio.file.Path;
@ConfigurationProperties("app")
public record AppProperties(String storage, String python, String worker, String model,
                            int inferenceTimeoutSeconds, int maxVideoSeconds, int queueCapacity, int workerStartupTimeoutSeconds) {
    public AppProperties {
        Path root = Path.of(System.getProperty("rfoid.projectRoot", ".")).toAbsolutePath().normalize();
        storage = root.resolve(storage).normalize().toString();
        worker = root.resolve(worker).normalize().toString();
        model = root.resolve(model).normalize().toString();
        if (python.contains("/") || python.contains("\\")) python = root.resolve(python).normalize().toString();
    }
}
