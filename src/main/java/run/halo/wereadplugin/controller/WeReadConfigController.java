package run.halo.wereadplugin.controller;

import org.springframework.web.bind.annotation.*;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.wereadplugin.client.CookieCloudClient;
import reactor.core.publisher.Mono;
import org.springframework.http.ResponseEntity;

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
                    if (payload.containsKey("cookieCloudUrl")) configMap.getData().put("cookieCloudUrl", payload.get("cookieCloudUrl"));
                    if (payload.containsKey("cookieCloudUuid")) configMap.getData().put("cookieCloudUuid", payload.get("cookieCloudUuid"));
                    if (payload.containsKey("cookieCloudPassword")) configMap.getData().put("cookieCloudPassword", payload.get("cookieCloudPassword"));
                    if (payload.containsKey("userAgent")) configMap.getData().put("userAgent", payload.get("userAgent"));
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
                                cm.getData().put("cookie", cookie);
                                cm.getData().put("cookieCloudUrl", url);
                                cm.getData().put("cookieCloudUuid", uuid);
                                cm.getData().put("cookieCloudPassword", password);
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
}
