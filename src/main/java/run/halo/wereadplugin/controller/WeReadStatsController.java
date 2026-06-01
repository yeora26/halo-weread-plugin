package run.halo.wereadplugin.controller;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.wereadplugin.client.WeReadClient;

@RestController
@RequestMapping("/api/admin/halo-weread-plugin")
public class WeReadStatsController {

    private final ReactiveExtensionClient extensionClient;
    private final WeReadClient weReadClient;

    public WeReadStatsController(ReactiveExtensionClient extensionClient, WeReadClient weReadClient) {
        this.extensionClient = extensionClient;
        this.weReadClient = weReadClient;
    }

    @GetMapping("/reading-stats")
    public Mono<ResponseEntity<Object>> getReadingStats(
            @RequestParam("mode") String mode,
            @RequestParam(value = "baseTime", required = false) Long baseTime) {
        return extensionClient.fetch(ConfigMap.class, "halo-weread-plugin-config")
                .flatMap(config -> {
                    Map<String, String> data = config.getData();
                    String apiKey = data != null ? data.getOrDefault("wereadApiKey", "") : "";
                    return weReadClient.getReadingStats(apiKey, mode, baseTime);
                })
                .map(json -> ResponseEntity.ok((Object) json))
                .onErrorResume(e -> Mono.just(ResponseEntity.badRequest().body(Map.of(
                        "message",
                        e.getMessage() != null ? e.getMessage() : e.toString()
                ))));
    }
}
