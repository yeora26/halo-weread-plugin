package run.halo.wereadplugin.controller;

import org.springframework.web.bind.annotation.*;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.wereadplugin.client.CookieCloudClient;
import reactor.core.publisher.Mono;
import org.springframework.http.ResponseEntity;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.HashMap;

@RestController
@RequestMapping("/api/admin/halo-weread-plugin")
public class WeReadConfigController {

    private final ReactiveExtensionClient client;
    private final CookieCloudClient cookieCloudClient;

    public WeReadConfigController(ReactiveExtensionClient client, CookieCloudClient cookieCloudClient) {
        this.client = client;
        this.cookieCloudClient = cookieCloudClient;
    }

    @GetMapping("/cookie")
    public Mono<Map<String, String>> getCookie() {
        return client.fetch(ConfigMap.class, "halo-weread-plugin-config")
                .onErrorResume(e -> Mono.empty())
                .map(configMap -> {
                    Map<String, String> result = new HashMap<>();
                    Map<String, String> data = configMap.getData();
                    if (data != null) {
                        result.put("cookieCloudUrl", data.getOrDefault("cookieCloudUrl", ""));
                        result.put("cookieCloudUuid", data.getOrDefault("cookieCloudUuid", ""));
                        result.put("cookieCloudPassword", data.getOrDefault("cookieCloudPassword", ""));
                        result.put("wereadApiKey", data.getOrDefault("wereadApiKey", ""));
                        result.put("autoRefreshCookie", data.getOrDefault("autoRefreshCookie", "false"));
                        result.put("cookieLastRefreshTime", data.getOrDefault("cookieLastRefreshTime", ""));
                        String cookie = data.getOrDefault("cookie", "");
                        result.put("cookieValid", String.valueOf(isCookieValid(cookie)));
                        result.put("cookieUserName", parseCookieValue(cookie, "wr_name"));
                    }
                    return result;
                })
                .defaultIfEmpty(new HashMap<>());
    }

    @PostMapping("/cookie")
    public Mono<Void> saveCookie(@RequestBody Map<String, String> payload) {
        return client.fetch(ConfigMap.class, "halo-weread-plugin-config")
                .onErrorResume(e -> Mono.empty())
                .flatMap(configMap -> {
                    if (configMap.getData() == null) configMap.setData(new HashMap<>());
                    configMap.getData().remove("cookieRefreshIntervalHours");
                    configMap.getData().remove("loginMethod");
                    if (payload.containsKey("cookieCloudUrl")) configMap.getData().put("cookieCloudUrl", payload.get("cookieCloudUrl"));
                    if (payload.containsKey("cookieCloudUuid")) configMap.getData().put("cookieCloudUuid", payload.get("cookieCloudUuid"));
                    if (payload.containsKey("cookieCloudPassword")) configMap.getData().put("cookieCloudPassword", payload.get("cookieCloudPassword"));
                    if (payload.containsKey("userAgent")) configMap.getData().put("userAgent", payload.get("userAgent"));
                    if (payload.containsKey("wereadApiKey")) configMap.getData().put("wereadApiKey", payload.get("wereadApiKey"));
                    if (payload.containsKey("autoRefreshCookie")) configMap.getData().put("autoRefreshCookie", payload.get("autoRefreshCookie"));
                    return client.update(configMap);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    ConfigMap newConfig = new ConfigMap();
                    run.halo.app.extension.Metadata metadata = new run.halo.app.extension.Metadata();
                    metadata.setName("halo-weread-plugin-config");
                    newConfig.setMetadata(metadata);
                    Map<String, String> data = new HashMap<>();
                    if (payload.containsKey("cookieCloudUrl")) data.put("cookieCloudUrl", payload.get("cookieCloudUrl"));
                    if (payload.containsKey("cookieCloudUuid")) data.put("cookieCloudUuid", payload.get("cookieCloudUuid"));
                    if (payload.containsKey("cookieCloudPassword")) data.put("cookieCloudPassword", payload.get("cookieCloudPassword"));
                    if (payload.containsKey("userAgent")) data.put("userAgent", payload.get("userAgent"));
                    if (payload.containsKey("wereadApiKey")) data.put("wereadApiKey", payload.get("wereadApiKey"));
                    if (payload.containsKey("autoRefreshCookie")) data.put("autoRefreshCookie", payload.get("autoRefreshCookie"));
                    newConfig.setData(data);
                    return client.create(newConfig);
                }))
                .then();
    }

    @PostMapping("/cookie-cloud/sync")
    public Mono<ResponseEntity<Map<String, String>>> syncFromCookieCloud(@RequestBody Map<String, String> req) {
        String url = req.get("url");
        String uuid = req.get("uuid");
        String password = req.get("password");
        String userAgent = req.get("userAgent");

        return cookieCloudClient.fetchWeReadCookie(url, uuid, password)
                .flatMap(cookie -> {
                    return client.fetch(ConfigMap.class, "halo-weread-plugin-config")
                            .onErrorResume(e -> {
                                ConfigMap cm = new ConfigMap();
                                Metadata metadata = new Metadata();
                                metadata.setName("halo-weread-plugin-config");
                                cm.setMetadata(metadata);
                                cm.setData(new HashMap<>());
                                return Mono.just(cm);
                            })
                            .flatMap(cm -> {
                                if (cm.getData() == null) {
                                    cm.setData(new HashMap<>());
                                }
                                cm.getData().remove("cookieRefreshIntervalHours");
                                cm.getData().remove("loginMethod");
                                cm.getData().put("cookie", cookie);
                                cm.getData().put("cookieCloudUrl", url);
                                cm.getData().put("cookieCloudUuid", uuid);
                                cm.getData().put("cookieCloudPassword", password);
                                cm.getData().put("cookieLastRefreshTime", String.valueOf(System.currentTimeMillis()));
                                if (userAgent != null) {
                                    cm.getData().put("userAgent", userAgent);
                                }

                                if (cm.getMetadata().getCreationTimestamp() == null) {
                                    return client.create(cm);
                                }
                                return client.update(cm);
                            })
                            .map(cm -> ResponseEntity.ok(Map.of("message", "从 CookieCloud 解析微信读书 Cookie 成功并已覆盖！", "cookie", cookie)));
                })
                .onErrorResume(e -> Mono.just(ResponseEntity.badRequest().body(Map.of("message", e.getMessage() != null ? e.getMessage() : e.toString()))));
    }

    private boolean isCookieValid(String cookie) {
        return !parseCookieValue(cookie, "wr_vid").isBlank()
                && (!parseCookieValue(cookie, "wr_name").isBlank() || !parseCookieValue(cookie, "wr_skey").isBlank());
    }

    private String parseCookieValue(String cookie, String key) {
        if (cookie == null || cookie.isBlank()) {
            return "";
        }
        for (String part : cookie.split(";")) {
            String trimmed = part.trim();
            int idx = trimmed.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            String name = trimmed.substring(0, idx).trim();
            if (!key.equals(name)) {
                continue;
            }
            String value = trimmed.substring(idx + 1).trim();
            try {
                return URLDecoder.decode(value, StandardCharsets.UTF_8);
            } catch (Exception e) {
                return value;
            }
        }
        return "";
    }
}
