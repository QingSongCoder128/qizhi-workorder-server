package com.qizhi.ai.config;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiRuntimeConfigService {

    public static final String DATA_ID = "ai-process-service.yaml";
    public static final String GROUP = "DEFAULT_GROUP";
    private static final List<String> DEFAULT_ORDER =
            List.of("CATEGORY", "RATING", "PRE_AUDIT");

    private final NacosConfigManager nacosConfigManager;
    private final AtomicReference<AiRuntimeConfig> current = new AtomicReference<>();

    @Value("${ai.model.api-url:}")
    private String initialUrl;
    @Value("${ai.model.api-key:}")
    private String initialKey;
    @Value("${ai.model.model-name:}")
    private String initialModel;
    @Value("${ai.model.temperature:0.7}")
    private double initialTemperature;
    @Value("${ai.model.max-tokens:2000}")
    private int initialMaxTokens;
    @Value("${ai.model.timeout-ms:10000}")
    private int initialTimeoutMs;
    @Value("${ai.supervisor.max-concurrent:5}")
    private int initialMaxConcurrent;
    @Value("${ai.graph.max-retry:2}")
    private int initialMaxRetry;
    @Value("${ai.graph.retry-interval-ms:500}")
    private long initialRetryInterval;

    @PostConstruct
    public void initialize() throws Exception {
        current.set(validate(new AiRuntimeConfig(initialUrl, initialKey, initialModel,
                initialTemperature, initialMaxTokens, initialTimeoutMs, initialMaxConcurrent,
                initialMaxRetry, initialRetryInterval, DEFAULT_ORDER)));
        ConfigService configService = nacosConfigManager.getConfigService();
        String yaml = configService.getConfig(DATA_ID, GROUP, 5000);
        if (yaml != null && !yaml.isBlank()) {
            applyYaml(yaml);
        }
        configService.addListener(DATA_ID, GROUP, new Listener() {
            private final Executor executor = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "ai-config-listener");
                thread.setDaemon(true);
                return thread;
            });

            @Override
            public Executor getExecutor() {
                return executor;
            }

            @Override
            public void receiveConfigInfo(String configInfo) {
                try {
                    applyYaml(configInfo);
                    log.info("AI运行时配置已原子热更新: model={}, order={}, keyConfigured={}",
                            current.get().modelName(), current.get().nodeOrder(),
                            !current.get().apiKey().isBlank());
                } catch (Exception e) {
                    log.error("AI运行时配置校验失败，继续使用上一版本: {}", e.getMessage());
                }
            }
        });
    }

    public AiRuntimeConfig get() {
        return current.get();
    }

    public synchronized AiRuntimeConfig publish(Map<String, Object> update) throws Exception {
        ConfigService configService = nacosConfigManager.getConfigService();
        String existing = configService.getConfig(DATA_ID, GROUP, 5000);
        Map<String, Object> root = yaml().load(existing);
        if (root == null) {
            root = new LinkedHashMap<>();
        }
        Map<String, Object> ai = child(root, "ai");
        Map<String, Object> model = child(ai, "model");
        Map<String, Object> supervisor = child(ai, "supervisor");
        Map<String, Object> graph = child(ai, "graph");
        putIfPresent(model, "api-url", update.get("apiUrl"));
        putIfPresent(model, "api-key", update.get("apiKey"));
        putIfPresent(model, "model-name", update.get("modelName"));
        putIfPresent(model, "temperature", update.get("temperature"));
        putIfPresent(model, "max-tokens", update.get("maxTokens"));
        putIfPresent(model, "timeout-ms", update.get("timeoutMs"));
        putIfPresent(supervisor, "max-concurrent", update.get("maxConcurrent"));
        putIfPresent(graph, "max-retry", update.get("maxRetry"));
        putIfPresent(graph, "retry-interval-ms", update.get("retryIntervalMs"));
        putIfPresent(graph, "node-order", update.get("nodeOrder"));

        String content = yaml().dump(root);
        AiRuntimeConfig candidate = parse(content);
        validate(candidate);
        if (!configService.publishConfig(DATA_ID, GROUP, content, "yaml")) {
            throw new IllegalStateException("Nacos 发布配置失败");
        }
        current.set(candidate);
        return candidate;
    }

    private void applyYaml(String content) {
        current.set(validate(parse(content)));
    }

    @SuppressWarnings("unchecked")
    private AiRuntimeConfig parse(String content) {
        Map<String, Object> root = yaml().load(content);
        Map<String, Object> ai = map(root.get("ai"));
        Map<String, Object> model = map(ai.get("model"));
        Map<String, Object> supervisor = map(ai.get("supervisor"));
        Map<String, Object> graph = map(ai.get("graph"));
        AiRuntimeConfig previous = current.get();
        return new AiRuntimeConfig(
                string(model.get("api-url"), previous.apiUrl()),
                string(model.get("api-key"), previous.apiKey()),
                string(model.get("model-name"), previous.modelName()),
                decimal(model.get("temperature"), previous.temperature()),
                integer(model.get("max-tokens"), previous.maxTokens()),
                integer(model.get("timeout-ms"), previous.timeoutMs()),
                integer(supervisor.get("max-concurrent"), previous.maxConcurrent()),
                integer(graph.get("max-retry"), previous.maxRetry()),
                longValue(graph.get("retry-interval-ms"), previous.retryIntervalMs()),
                stringList(graph.get("node-order"), previous.nodeOrder()));
    }

    private AiRuntimeConfig validate(AiRuntimeConfig config) {
        if (config.apiUrl() == null || !config.apiUrl().matches("^https?://.+")) {
            throw new IllegalArgumentException("AI URL 必须是 http/https 地址");
        }
        if (config.apiKey() == null || config.apiKey().isBlank()
                || config.modelName() == null || config.modelName().isBlank()) {
            throw new IllegalArgumentException("AI Key 和 Model 必须已配置");
        }
        if (config.maxConcurrent() < 1 || config.maxRetry() < 0
                || config.timeoutMs() < 1000 || config.maxTokens() < 1) {
            throw new IllegalArgumentException("AI 数值参数不合法");
        }
        if (config.nodeOrder().size() != 3
                || !new HashSet<>(config.nodeOrder()).equals(new HashSet<>(DEFAULT_ORDER))) {
            throw new IllegalArgumentException("Graph 节点必须包含 CATEGORY、RATING、PRE_AUDIT 且各一次");
        }
        return config;
    }

    private static Map<String, Object> child(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        }
        Map<String, Object> created = new LinkedHashMap<>();
        parent.put(key, created);
        return created;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && (!(value instanceof String string) || !string.isBlank())) {
            target.put(key, value);
        }
    }

    private static String string(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static int integer(Object value, int fallback) {
        return value instanceof Number number ? number.intValue()
                : value == null ? fallback : Integer.parseInt(String.valueOf(value));
    }

    private static long longValue(Object value, long fallback) {
        return value instanceof Number number ? number.longValue()
                : value == null ? fallback : Long.parseLong(String.valueOf(value));
    }

    private static double decimal(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue()
                : value == null ? fallback : Double.parseDouble(String.valueOf(value));
    }

    private static List<String> stringList(Object value, List<String> fallback) {
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(String::valueOf)
                    .map(String::trim).map(String::toUpperCase).toList();
        }
        if (value instanceof String string && !string.isBlank()) {
            return Arrays.stream(string.split(",")).map(String::trim)
                    .map(String::toUpperCase).toList();
        }
        return fallback;
    }

    private static Yaml yaml() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        return new Yaml(options);
    }
}
