package run.halo.wereadplugin.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * 微信读书客户端 - 增强版 (支持动态 Cookie 维护)
 * @author haike
 * @date 2026-04-25
 */
@Slf4j
@Component
public class WeReadClient {
    private static final String WEREAD_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ReactiveExtensionClient extensionClient;

    public WeReadClient(ReactiveExtensionClient extensionClient) {
        this.extensionClient = extensionClient;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public static class WeReadResponse {
        private final JsonNode body;
        private final String updatedCookie;
        public WeReadResponse(JsonNode body, String updatedCookie) {
            this.body = body;
            this.updatedCookie = updatedCookie;
        }
        public JsonNode getBody() { return body; }
        public String getUpdatedCookie() { return updatedCookie; }
    }

    private String mergeCookies(String oldCookie, List<String> setCookies) {
        if (setCookies == null || setCookies.isEmpty()) return oldCookie;
        List<String> cookieParts = new java.util.ArrayList<>();
        if (oldCookie != null && !oldCookie.isBlank()) {
            for (String part : oldCookie.split(";")) {
                String cookiePart = part.trim();
                if (cookiePart.contains("=")) {
                    cookieParts.add(cookiePart);
                }
            }
        }
        for (String sc : setCookies) {
            String kv = sc.split(";")[0].trim();
            int idx = kv.indexOf('=');
            if (idx > 0) {
                String name = kv.substring(0, idx);
                boolean replaced = false;
                for (int i = 0; i < cookieParts.size(); i++) {
                    String part = cookieParts.get(i);
                    int partIdx = part.indexOf('=');
                    if (partIdx > 0 && name.equals(part.substring(0, partIdx))) {
                        cookieParts.set(i, kv);
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) {
                    cookieParts.add(kv);
                }
            }
        }
        return String.join("; ", cookieParts);
    }

    /**
     * 自动同步最新的 Cookie 到系统配置中
     */
    private Mono<Void> persistCookie(String newCookie) {
        return extensionClient.fetch(ConfigMap.class, "halo-weread-plugin-config")
                .flatMap(config -> {
                    Map<String, String> data = config.getData();
                    if (data == null) data = new HashMap<>();
                    String current = data.get("cookie");
                    if (newCookie.equals(current)) return Mono.empty();
                    
                    data.put("cookie", newCookie);
                    config.setData(data);
                    log.info("WeRead: 检测到凭证变化，已自动更新并持久化 Cookie。");
                    return extensionClient.update(config);
                })
                .then();
    }

    private String sanitizeCookie(String cookie) {
        if (cookie == null || cookie.isBlank()) return "";
        List<String> cookieParts = new java.util.ArrayList<>();
        for (String part : cookie.split(";")) {
            part = part.trim();
            int idx = part.indexOf('=');
            if (idx > 0) {
                String key = part.substring(0, idx).trim();
                String value = part.substring(idx + 1).trim();
                // 如果值中包含非 ASCII 字符，对其进行 URL 编码以匹配浏览器规范并防止 HTTP 传输格式非法
                if (value.matches(".*[^\\x00-\\x7F].*")) {
                    try {
                        value = java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8.name());
                    } catch (Exception e) {
                        // ignore
                    }
                }
                cookieParts.add(key + "=" + value);
            }
        }
        return String.join("; ", cookieParts);
    }

    private String effectiveUserAgent(String userAgent) {
        return userAgent == null || userAgent.isBlank() ? WEREAD_USER_AGENT : userAgent;
    }

    private Mono<WeReadResponse> execute(HttpRequest request, String url, String cookie) {
        return Mono.fromFuture(httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()))
                .flatMap(response -> {
                    String body;
                    try {
                        body = decodeBody(response);
                    } catch (Exception e) {
                        return Mono.error(new RuntimeException("Decode error: " + e.getMessage(), e));
                    }
                    String logBody = body != null && body.length() > 300 ? body.substring(0, 300) + "..." : body;
                    log.info("WeRead API Response: url={}, status={}, body={}", url, response.statusCode(), logBody);
                    List<String> setCookies = response.headers().allValues("set-cookie");
                    String newMergedCookie = mergeCookies(cookie, setCookies);

                    // 异步更新 Cookie，不阻塞当前响应
                    Mono<Void> updateTask = newMergedCookie.equals(cookie) ? Mono.empty() : persistCookie(newMergedCookie);

                    if (response.statusCode() == 200) {
                        try {
                            if (body != null && (body.trim().startsWith("<!DOCTYPE") || body.trim().startsWith("<html"))) {
                                if (url.contains("/web/shelf")) {
                                    if (body.contains("window.__INITIAL_STATE__")) {
                                        int startIdx = body.indexOf("window.__INITIAL_STATE__");
                                        startIdx = body.indexOf("{", startIdx);
                                        int endIdx = body.indexOf("};", startIdx);
                                        if (endIdx != -1) {
                                            String jsonStr = body.substring(startIdx, endIdx + 1);
                                            JsonNode jsonBody = objectMapper.readTree(jsonStr);
                                            // 强校验：只有当 user.isLogin 为 true 时，才认为网页端成功登录
                                            if (jsonBody.path("user").path("isLogin").asBoolean(false)) {
                                                return updateTask.thenReturn(new WeReadResponse(jsonBody, newMergedCookie));
                                            }
                                        }
                                    }
                                }
                                return Mono.error(new RuntimeException("WeRead API returned HTML (Login Required). URL: " + url));
                            }
                            JsonNode jsonBody = objectMapper.readTree(body);
                            if (jsonBody.has("errcode") && jsonBody.get("errcode").asInt() != 0) {
                                return Mono.error(new RuntimeException("WeRead API Error: " + jsonBody.get("errcode").asInt() + ", " + jsonBody.path("errmsg").asText("")));
                            }
                            return updateTask.thenReturn(new WeReadResponse(jsonBody, newMergedCookie));
                        } catch (Exception e) {
                            return Mono.error(new RuntimeException("Parse error: " + e.getMessage()));
                        }
                    } else if (response.statusCode() == 401) {
                        return Mono.error(new RuntimeException("401 Unauthorized"));
                    } else {
                        return Mono.error(new RuntimeException("API Error: " + response.statusCode()));
                    }
                });
    }

    private String decodeBody(HttpResponse<byte[]> response) throws IOException {
        byte[] bytes = response.body();
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        String contentEncoding = response.headers()
                .firstValue("content-encoding")
                .orElse("")
                .toLowerCase(Locale.ROOT);
        if (contentEncoding.contains("gzip")) {
            try (GZIPInputStream inputStream = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        if (contentEncoding.contains("deflate")) {
            try (InflaterInputStream inputStream = new InflaterInputStream(new ByteArrayInputStream(bytes))) {
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private HttpRequest.Builder requestBuilder(String url, String cookie, String userAgent) {
        String cleanCookie = sanitizeCookie(cookie);
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1)
                .header("User-Agent", effectiveUserAgent(userAgent))
                .header("Accept-Encoding", "gzip, deflate")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Accept", "application/json, text/plain, */*")
                .header("Content-Type", "application/json")
                .header("Cookie", cleanCookie);
    }

    private Mono<WeReadResponse> executeGet(String url, String cookie, String userAgent) {
        HttpRequest request = requestBuilder(url, cookie, userAgent)
                .GET()
                .build();
        return execute(request, url, cookie);
    }

    private Mono<WeReadResponse> executePostJson(String url, String body, String cookie, String userAgent) {
        HttpRequest request = requestBuilder(url, cookie, userAgent)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return execute(request, url, cookie);
    }

    public Mono<WeReadResponse> refreshCookie(String cookie, String userAgent) {
        String cleanCookie = sanitizeCookie(cookie);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://weread.qq.com/"))
                .version(HttpClient.Version.HTTP_1_1)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .header("User-Agent", effectiveUserAgent(userAgent))
                .header("Accept-Encoding", "gzip, deflate")
                .header("Cookie", cleanCookie)
                .build();
        return Mono.fromFuture(httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding()))
                .flatMap(response -> {
                    List<String> setCookies = response.headers().allValues("set-cookie");
                    String newMergedCookie = mergeCookies(cookie, setCookies);
                    Mono<Void> updateTask = newMergedCookie.equals(cookie) ? Mono.empty() : persistCookie(newMergedCookie);
                    return updateTask.thenReturn(new WeReadResponse(null, newMergedCookie));
                });
    }

    public Mono<WeReadResponse> getNotebooks(String cookie, String userAgent) {
        return executeGet("https://weread.qq.com/api/user/notebook", cookie, userAgent);
    }

    public Mono<WeReadResponse> getShelfSync(String cookie, String userAgent) {
        return executeGet("https://weread.qq.com/web/shelf", cookie, userAgent);
    }
    
    public Mono<WeReadResponse> getBookInfo(String cookie, String bookId, String userAgent) {
        return executeGet("https://weread.qq.com/web/book/info?bookId=" + bookId, cookie, userAgent);
    }

    public Mono<WeReadResponse> getBookProgress(String cookie, String bookId, String userAgent) {
        return executeGet("https://weread.qq.com/web/book/getProgress?bookId=" + bookId, cookie, userAgent);
    }

    public Mono<WeReadResponse> getBookChapters(String cookie, String bookId, String userAgent) {
        try {
            String body = objectMapper.writeValueAsString(Map.of("bookIds", List.of(bookId)));
            return executePostJson("https://weread.qq.com/web/book/chapterInfos", body, cookie, userAgent);
        } catch (Exception e) {
            return Mono.error(new RuntimeException("Build chapter request error: " + e.getMessage()));
        }
    }

    /**
     * 获取某书的所有划线（高亮标注）
     * 接口：/web/book/bookmarklist，失败时回退到 i.weread.qq.com
     */
    public Mono<WeReadResponse> getBookmarks(String cookie, String bookId, String userAgent) {
        String primaryUrl = "https://weread.qq.com/web/book/bookmarklist?bookId=" + bookId;
        String fallbackUrl = "https://i.weread.qq.com/book/bookmarklist?bookId=" + bookId;
        return executeGetWithFallback(primaryUrl, fallbackUrl, "updated", cookie, userAgent, bookId, "bookmarklist");
    }

    /**
     * 获取某书的所有划线感想（想法）和整本书评
     * listType=11 表示全量，mine=1 表示只查自己，syncKey=0 表示全量拉取
     * 接口：/web/review/list，失败时回退到 i.weread.qq.com
     */
    public Mono<WeReadResponse> getBookReviews(String cookie, String bookId, String userAgent) {
        String query = "?bookId=" + bookId + "&listType=11&mine=1&synckey=0";
        String primaryUrl = "https://weread.qq.com/web/review/list" + query;
        String fallbackUrl = "https://i.weread.qq.com/review/list" + query;
        return executeGetWithFallback(primaryUrl, fallbackUrl, "reviews", cookie, userAgent, bookId, "review/list");
    }

    private Mono<WeReadResponse> executeGetWithFallback(
            String primaryUrl,
            String fallbackUrl,
            String expectedArrayField,
            String cookie,
            String userAgent,
            String bookId,
            String apiName
    ) {
        return executeGet(primaryUrl, cookie, userAgent)
                .flatMap(response -> {
                    if (hasArrayField(response.getBody(), expectedArrayField)) {
                        return Mono.just(response);
                    }
                    log.warn(
                            "WeRead: {} primary response missing field '{}', bookId={}, trying fallback",
                            apiName,
                            expectedArrayField,
                            bookId
                    );
                    return executeGet(fallbackUrl, response.getUpdatedCookie(), userAgent)
                            .onErrorResume(e -> {
                                log.warn("WeRead: {} fallback failed: bookId={}, error={}", apiName, bookId, e.getMessage());
                                return Mono.just(response);
                            });
                })
                .onErrorResume(e -> {
                    log.warn("WeRead: {} primary failed: bookId={}, error={}, trying fallback", apiName, bookId, e.getMessage());
                    return executeGet(fallbackUrl, cookie, userAgent);
                });
    }

    private boolean hasArrayField(JsonNode body, String fieldName) {
        return body != null && body.has(fieldName) && body.path(fieldName).isArray();
    }
}
