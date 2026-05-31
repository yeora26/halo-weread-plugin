package run.halo.wereadplugin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.wereadplugin.client.WeReadClient;
import run.halo.wereadplugin.extension.WereadBook;
import run.halo.wereadplugin.extension.WereadNote;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class WeReadSyncService {

    private final WeReadClient weReadClient;
    private final ReactiveExtensionClient extensionClient;

    public WeReadSyncService(WeReadClient weReadClient,
                             ReactiveExtensionClient extensionClient) {
        this.weReadClient = weReadClient;
        this.extensionClient = extensionClient;
    }

    public Mono<Void> syncWeReadData() {
        return extensionClient.fetch(ConfigMap.class, "halo-weread-plugin-config")
                .onErrorResume(e -> Mono.empty())
                .flatMap(configMap -> {
                    Map<String, String> data = configMap.getData() != null ? configMap.getData() : Map.of();
                    String cookie = data.getOrDefault("cookie", "");
                    String userAgent = data.getOrDefault("userAgent", "");
                    if (cookie.isBlank()) {
                        return Mono.error(new RuntimeException("未配置 WeRead Cookie"));
                    }

                    // 安全地打印 Cookie 键名列表以诊断缺失
                    List<String> keys = parseCookieKeys(cookie);
                    log.info("WeRead: 开始同步前验证 Cookie, configuredUserAgent={}, CookieKeys={}", userAgent, keys);
                    logCookieDiagnostics("配置", cookie);

                    // 1. 先验证一次 Cookie，顺便吸收服务器可能返回的最新 wr_skey
                    return weReadClient.refreshCookie(cookie, userAgent)
                            .flatMap(refreshResp -> {
                                logCookieDiagnostics("刷新后", refreshResp.getUpdatedCookie());
                                return weReadClient.getNotebooks(refreshResp.getUpdatedCookie(), userAgent);
                            })
                            .flatMap(resp -> {
                                String latestCookie = resp.getUpdatedCookie();
                                log.info("WeRead: Cookie 基础验证通过，开始深度检测划线详情接口...");
                                return validateBookmarkAccess(latestCookie, userAgent, resp.getBody())
                                        .then(processNotebookResponse(latestCookie, userAgent, Map.of(), resp.getBody()));
                            })
                            .onErrorResume(e -> {
                                log.error("WeRead: 同步过程中验证失败，同步终止: {}", e.getMessage());
                                return Mono.error(e);
                            });
                });
    }

    private Map<String, ProgressInfo> parseShelfProgress(JsonNode shelfBody) {
        Map<String, ProgressInfo> map = new HashMap<>();
        JsonNode books = shelfBody.has("shelf") ? shelfBody.path("shelf").path("books") : shelfBody.path("books");
        if (books.isArray()) {
            for (JsonNode book : books) {
                String bid = book.path("bookId").asText();
                if (!bid.isEmpty()) {
                    double progressVal = book.path("progress").asDouble(0);
                    if (progressVal > 0 && progressVal <= 1.0) {
                        progressVal = progressVal * 100;
                    }
                    map.put(bid, new ProgressInfo(
                        progressVal,
                        book.path("readingTime").asInt(0),
                        book.path("updateTime").asLong(0)
                    ));
                }
            }
        }
        return map;
    }

    private static class ProgressInfo {
        double progress;
        int readingTime;
        long updateTime;
        ProgressInfo(double p, int rt, long ut) {
            this.progress = p;
            this.readingTime = rt;
            this.updateTime = ut;
        }
    }

    private record BookSyncItem(JsonNode bookEntry, String cookie) {
    }

    private record NoteSyncStats(
            int bookmarkCount,
            int reviewCount,
            int bookReviewCount,
            boolean bookmarkReliable,
            boolean reviewReliable
    ) {
    }

    private List<String> parseCookieKeys(String cookie) {
        if (cookie == null || cookie.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(cookie.split(";"))
                .map(s -> s.split("=")[0].trim())
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private void logCookieDiagnostics(String stage, String cookie) {
        List<String> keys = parseCookieKeys(cookie);
        boolean hasWrVid = keys.contains("wr_vid");
        boolean hasWrName = keys.contains("wr_name");
        boolean hasWrSkey = keys.contains("wr_skey");
        if (!hasWrVid || (!hasWrName && !hasWrSkey)) {
            log.warn(
                    "WeRead: {} Cookie 可能不完整，hasWrVid={}, hasWrName={}, hasWrSkey={}, keys={}",
                    stage,
                    hasWrVid,
                    hasWrName,
                    hasWrSkey,
                    keys
            );
            return;
        }
        if (!hasWrSkey) {
            log.warn("WeRead: {} Cookie 缺少 wr_skey，书籍列表可能可用，但划线/想法接口容易返回登录态错误。keys={}", stage, keys);
        }
    }

    private Mono<Void> validateBookmarkAccess(String cookie, String userAgent, JsonNode notebookBody) {
        JsonNode booksNode = notebookBody.path("books");
        if (!booksNode.isArray()) {
            return Mono.error(new RuntimeException("微信读书 Cookie 基础验证失败：notebook 响应中没有 books 数组"));
        }

        JsonNode sampleBook = null;
        for (JsonNode bookEntry : booksNode) {
            if (bookEntry.path("noteCount").asInt(0) > 0) {
                sampleBook = bookEntry;
                break;
            }
        }

        if (sampleBook == null) {
            log.info("WeRead: notebook 中没有 noteCount > 0 的书籍，跳过划线详情接口深度检测。");
            return Mono.empty();
        }

        JsonNode book = sampleBook.path("book");
        String bookId = book.path("bookId").asText("");
        String title = book.path("title").asText(bookId);
        int noteCount = sampleBook.path("noteCount").asInt(0);
        if (bookId.isBlank()) {
            return Mono.error(new RuntimeException("微信读书 Cookie 深度检测失败：notebook 书籍缺少 bookId"));
        }

        return weReadClient.getBookmarks(cookie, bookId, userAgent)
                .flatMap(resp -> {
                    JsonNode updated = resp.getBody().path("updated");
                    if (updated.isArray()) {
                        if (updated.isEmpty() && noteCount > 0) {
                            return Mono.<Void>error(new RuntimeException(
                                    "微信读书 Cookie 已半失效：书籍《" + title + "》概览显示有 "
                                            + noteCount + " 条划线，但 bookmarklist 返回空数组。请重新登录微信读书并同步 CookieCloud。"
                            ));
                        }
                        log.info("WeRead: 划线详情接口深度检测通过，bookId={}, title={}, count={}", bookId, title, updated.size());
                        return Mono.empty();
                    }
                    return Mono.<Void>error(new RuntimeException(
                            "微信读书 Cookie 已半失效：书籍《" + title + "》概览显示有 " + noteCount
                                    + " 条划线，但 bookmarklist 没有返回 updated 数组。请重新登录微信读书并同步 CookieCloud；"
                                    + "如果配置了 User-Agent，请清空后使用插件默认值。"
                    ));
                })
                .onErrorResume(e -> Mono.<Void>error(new RuntimeException(
                        "微信读书 Cookie 深度检测失败：无法读取书籍《" + title + "》的划线详情。"
                                + "请重新登录微信读书并同步 CookieCloud；如果配置了 User-Agent，请清空后使用插件默认值。原始错误："
                                + (e.getMessage() != null ? e.getMessage() : e.toString())
                )));
    }

    private Mono<Void> processNotebooks(
            String cookie,
            String userAgent,
            Map<String, ProgressInfo> shelfProgressMap
    ) {
        log.info("开始同步微信读书数据 (全量书架模式)...");
        return weReadClient.getNotebooks(cookie, userAgent)
                .flatMap(response -> processNotebookResponse(
                        response.getUpdatedCookie(),
                        userAgent,
                        shelfProgressMap,
                        response.getBody()
                ));
    }

    private Mono<Void> processNotebookResponse(
            String cookie,
            String userAgent,
            Map<String, ProgressInfo> shelfProgressMap,
            JsonNode notebookBody
    ) {
        log.info("开始同步微信读书数据 (全量书架模式)...");
        return Mono.just(new WeReadClient.WeReadResponse(notebookBody, cookie))
                .flatMapMany(response -> {
                    String latestCookie = response.getUpdatedCookie();
                    JsonNode booksNode = response.getBody().path("books");
                    log.info("WeRead: 获取到 notebooks 响应，books 节点类型={}, 是否为数组={}", booksNode.getNodeType(), booksNode.isArray());
                    if (booksNode.isArray()) {
                        log.info("WeRead: 获取到 {} 本有笔记的书籍", booksNode.size());
                        return Flux.fromIterable(booksNode)
                                .map(bookEntry -> new BookSyncItem(bookEntry, latestCookie));
                    }
                    log.warn("WeRead: books 节点为空或非数组，完整的响应体为: {}", response.getBody().toString());
                    return Flux.empty();
                })
                .flatMap(item -> {
                    JsonNode bookEntry = item.bookEntry();
                    String requestCookie = item.cookie();
                    JsonNode baseBookInfo = bookEntry.path("book");
                    String bookId = baseBookInfo.path("bookId").asText();
                    int noteCount = bookEntry.path("noteCount").asInt(0);
                    int reviewCount = bookEntry.path("reviewCount").asInt(0);
                    long sortTime = bookEntry.path("sort").asLong(0) * 1000L;
                    log.info("WeRead: 处理书籍: bookId={}, title={}, noteCount={}, reviewCount={}", bookId, baseBookInfo.path("title").asText(""), noteCount, reviewCount);

                    // 优先从预加载的书架数据中获取进度
                    ProgressInfo shelfInfo = shelfProgressMap.get(bookId);

                    return weReadClient.getBookInfo(requestCookie, bookId, userAgent)
                            .onErrorResume(e -> {
                                log.warn("获取书籍详情失败 bookId={}: {}", bookId, e.getMessage());
                                return Mono.just(new WeReadClient.WeReadResponse(
                                        JsonNodeFactory.instance.objectNode(),
                                        requestCookie
                                ));
                            })
                            .flatMap(infoResp -> {
                                String bookCookie = infoResp.getUpdatedCookie();
                                Mono<WeReadClient.WeReadResponse> progressMono;
                                if (shelfInfo != null) {
                                    // 如果书架里有，直接构造结果，省去一次 API 调用
                                    ObjectNode mockProgress = JsonNodeFactory.instance.objectNode();
                                    mockProgress.putObject("book")
                                            .put("progress", shelfInfo.progress)
                                            .put("readingTime", shelfInfo.readingTime)
                                            .put("updateTime", shelfInfo.updateTime);
                                    progressMono = Mono.just(new WeReadClient.WeReadResponse(mockProgress, bookCookie));
                                } else {
                                    progressMono = weReadClient.getBookProgress(bookCookie, bookId, userAgent)
                                            .onErrorResume(e -> {
                                                log.warn("获取阅读进度失败 bookId={}: {}", bookId, e.getMessage());
                                                return Mono.just(new WeReadClient.WeReadResponse(
                                                        JsonNodeFactory.instance.objectNode(),
                                                        bookCookie
                                                ));
                                            });
                                }

                                return progressMono.flatMap(progressResp -> saveBook(
                                        bookId,
                                        baseBookInfo,
                                        infoResp.getBody(),
                                        progressResp.getBody(),
                                        noteCount,
                                        reviewCount,
                                        sortTime
                                ).then(syncBookNotes(
                                        progressResp.getUpdatedCookie(),
                                        bookId,
                                        userAgent,
                                        noteCount,
                                        reviewCount
                                )).flatMap(stats -> updateBookNoteCounts(bookId, stats)));
                            });
                }, 5)
                .then();
    }

    // -------------------------------------------------------------------------
    // 笔记同步
    // -------------------------------------------------------------------------

    /**
     * 同步单本书的所有笔记：划线 + 想法 + 书评
     * 不依赖 notebook 概览中的计数字段，避免 count 延迟或语义差异导致跳过真实笔记。
     */
    private Mono<NoteSyncStats> syncBookNotes(
            String cookie,
            String bookId,
            String userAgent,
            int noteCount,
            int reviewCount
    ) {
        log.info("WeRead: 开始同步书籍笔记: bookId={}, noteCount={}, reviewCount={}", bookId, noteCount, reviewCount);

        Mono<JsonNode> bookmarksMono = weReadClient.getBookmarks(cookie, bookId, userAgent)
                .map(r -> {
                    JsonNode body = r.getBody();
                    if (noteCount > 0 && (body == null || body.isEmpty() || !body.path("updated").isArray())) {
                        log.warn("WeRead: 获取书籍(bookId={})的划线返回了空数据。系统记录该书应有 {} 条划线，获取失败可能由于 wr_skey 失效、Cookie 不完整或 UA 不匹配导致。", bookId, noteCount);
                    } else if (noteCount > 0 && body.path("updated").isArray() && body.path("updated").isEmpty()) {
                        log.warn("WeRead: 获取书籍(bookId={})的划线数组为空。notebook 概览记录该书有 {} 条划线，请重点检查 CookieCloud 是否同步到最新 wr_skey。", bookId, noteCount);
                    }
                    return body;
                })
                .onErrorResume(e -> {
                    log.warn("获取划线失败 bookId={}: {}", bookId, e.getMessage());
                    return Mono.just(JsonNodeFactory.instance.objectNode());
                });

        Mono<JsonNode> reviewsMono = weReadClient.getBookReviews(cookie, bookId, userAgent)
                .map(r -> {
                    JsonNode body = r.getBody();
                    if (reviewCount > 0 && (body == null || body.isEmpty() || !body.path("reviews").isArray())) {
                        log.warn("WeRead: 获取书籍(bookId={})的想法返回了空数据。系统记录该书应有 {} 条想法，获取失败可能由于 wr_skey 失效或 UA 不匹配导致。", bookId, reviewCount);
                    } else if (reviewCount > 0 && body.path("reviews").isArray() && body.path("reviews").isEmpty()) {
                        log.warn("WeRead: 获取书籍(bookId={})的想法数组为空。notebook 概览记录该书有 {} 条想法，请重点检查 CookieCloud 是否同步到最新 wr_skey。", bookId, reviewCount);
                    }
                    return body;
                })
                .onErrorResume(e -> {
                    log.warn("获取想法失败 bookId={}: {}", bookId, e.getMessage());
                    return Mono.just(JsonNodeFactory.instance.objectNode());
                });

        Mono<JsonNode> chaptersMono = weReadClient.getBookChapters(cookie, bookId, userAgent)
                .map(WeReadClient.WeReadResponse::getBody)
                .onErrorResume(e -> {
                    log.warn("获取章节目录失败 bookId={}: {}", bookId, e.getMessage());
                    return Mono.just(JsonNodeFactory.instance.objectNode());
                });

        return Mono.zip(bookmarksMono, reviewsMono, chaptersMono)
                .flatMap(tuple -> {
                    JsonNode bookmarksBody = tuple.getT1();
                    JsonNode reviewsBody = tuple.getT2();
                    JsonNode chaptersBody = tuple.getT3();
                    Map<Long, String> chapterTitles = parseChapterTitles(bookmarksBody, chaptersBody);
                    log.info(
                            "WeRead: 获取到划线/想法/章节响应: bookId={}, bookmarksBody={}, reviewsBody={}, chapters={}",
                            bookId,
                            bookmarksBody.toString(),
                            reviewsBody.toString(),
                            chapterTitles.size()
                    );
                    NoteSyncStats stats = countNoteStats(bookmarksBody, reviewsBody);
                    return Flux.concat(
                        parseAndSaveBookmarks(bookId, bookmarksBody, chapterTitles),
                        parseAndSaveReviews(bookId, reviewsBody, chapterTitles)
                    ).then(Mono.just(stats));
                });
    }

    private NoteSyncStats countNoteStats(JsonNode bookmarksBody, JsonNode reviewsBody) {
        JsonNode bookmarks = bookmarksBody.path("updated");
        boolean bookmarkReliable = bookmarks.isArray();
        int bookmarkCount = countValidBookmarks(bookmarks);
        int reviewCount = 0;
        int bookReviewCount = 0;

        JsonNode reviews = reviewsBody.path("reviews");
        boolean reviewReliable = reviews.isArray();
        if (reviews.isArray()) {
            for (JsonNode item : reviews) {
                JsonNode review = item.path("review");
                if (review.path("reviewId").asText("").isBlank()) {
                    continue;
                }
                if (isBookReview(review)) {
                    bookReviewCount++;
                } else {
                    reviewCount++;
                }
            }
        }

        return new NoteSyncStats(bookmarkCount, reviewCount, bookReviewCount, bookmarkReliable, reviewReliable);
    }

    private int countValidBookmarks(JsonNode updated) {
        if (!updated.isArray()) {
            return 0;
        }
        int count = 0;
        for (JsonNode item : updated) {
            if (!item.path("bookmarkId").asText("").isBlank()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 解析划线列表并保存
     * 微信读书 bookmarklist 响应格式：
     * { "updated": [ { "bookmarkId", "chapterUid", "chapterTitle", "markText", "range", "style", "createTime" }, ... ] }
     */
    private Flux<Void> parseAndSaveBookmarks(String bookId, JsonNode body, Map<Long, String> chapterTitles) {
        JsonNode updated = body.path("updated");
        log.info("WeRead: 解析划线: bookId={}, updated类型={}, size={}", bookId, updated.getNodeType(), updated.size());
        if (!updated.isArray() || updated.isEmpty()) {
            return Flux.empty();
        }

        return Flux.fromIterable(updated)
                .flatMap(item -> {
                    String markId = item.path("bookmarkId").asText("");
                    if (markId.isBlank()) return Mono.empty();

                    WereadNote.Spec spec = new WereadNote.Spec();
                    spec.setBookId(bookId);
                    spec.setType("bookmark");
                    spec.setMarkId(markId);
                    spec.setContent(item.path("markText").asText(""));
                    spec.setChapterTitle(resolveChapterTitle(item, chapterTitles));
                    spec.setChapterUid(item.path("chapterUid").asLong(0));
                    spec.setRange(item.path("range").asText(""));
                    spec.setColorStyle(item.path("colorStyle").asInt(item.path("style").asInt(0)));
                    spec.setCreateTime(item.path("createTime").asLong(0) * 1000L);

                    String resourceName = "wereadnote-bm-" + sanitizeId(markId);
                    return saveNote(resourceName, spec);
                });
    }

    /**
     * 解析划线感想（想法）和书评列表并保存
     * 微信读书 review/list 响应格式：
     * { "reviews": [ { "review": { "reviewId", "type", "content", "abstract",
     *                               "chapterUid", "chapterTitle", "createTime", "bookId" } } ] }
     * type=1 表示划线感想，type=4 表示整本书评
     */
    private Flux<Void> parseAndSaveReviews(String bookId, JsonNode body, Map<Long, String> chapterTitles) {
        JsonNode reviews = body.path("reviews");
        log.info("WeRead: 解析想法: bookId={}, reviews类型={}, size={}", bookId, reviews.getNodeType(), reviews.size());
        if (!reviews.isArray() || reviews.isEmpty()) {
            return Flux.empty();
        }

        return Flux.fromIterable(reviews)
                .flatMap(item -> {
                    JsonNode review = item.path("review");
                    String reviewId = review.path("reviewId").asText("");
                    if (reviewId.isBlank()) return Mono.empty();

                    String noteType = isBookReview(review) ? "bookReview" : "review";

                    WereadNote.Spec spec = new WereadNote.Spec();
                    spec.setBookId(bookId);
                    spec.setType(noteType);
                    spec.setMarkId(reviewId);
                    spec.setContent(review.path("content").asText(""));
                    spec.setAbstractContent(review.path("abstract").asText(""));
                    spec.setChapterTitle(resolveChapterTitle(review, chapterTitles));
                    spec.setChapterUid(review.path("chapterUid").asLong(0));
                    spec.setRange(review.path("range").asText(""));
                    spec.setCreateTime(review.path("createTime").asLong(0) * 1000L);

                    String resourceName = "wereadnote-rv-" + sanitizeId(reviewId);
                    return saveNote(resourceName, spec);
                });
    }

    private boolean isBookReview(JsonNode review) {
        int reviewType = review.path("type").asInt(0);
        return reviewType == 4 || reviewType == 6;
    }

    private Mono<Void> updateBookNoteCounts(String bookId, NoteSyncStats stats) {
        String resourceName = "wereadbook-" + bookId;
        return extensionClient.fetch(WereadBook.class, resourceName)
                .flatMap(book -> {
                    WereadBook.Spec spec = book.getSpec();
                    if (spec == null) {
                        return Mono.empty();
                    }
                    if (stats.bookmarkReliable()) {
                        spec.setNoteCount(stats.bookmarkCount());
                    }
                    if (stats.reviewReliable()) {
                        spec.setReviewCount(stats.reviewCount());
                    }
                    book.setSpec(spec);
                    log.info(
                            "WeRead: 回写实际笔记数量: bookId={}, noteCount={}({}), reviewCount={}({}), bookReviewCount={}",
                            bookId,
                            stats.bookmarkCount(),
                            stats.bookmarkReliable() ? "reliable" : "kept",
                            stats.reviewCount(),
                            stats.reviewReliable() ? "reliable" : "kept",
                            stats.bookReviewCount()
                    );
                    return extensionClient.update(book);
                })
                .onErrorResume(e -> {
                    log.warn("回写书籍笔记数量失败 bookId={}: {}", bookId, e.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    private Map<Long, String> parseChapterTitles(JsonNode bookmarksBody, JsonNode chaptersBody) {
        Map<Long, String> chapterTitles = new HashMap<>();
        putChapterTitles(chapterTitles, bookmarksBody.path("chapters"));

        JsonNode data = chaptersBody.path("data");
        if (data.isArray()) {
            for (JsonNode bookChapterData : data) {
                putChapterTitles(chapterTitles, bookChapterData.path("updated"));
            }
        }
        return chapterTitles;
    }

    private void putChapterTitles(Map<Long, String> chapterTitles, JsonNode chapters) {
        if (!chapters.isArray()) {
            return;
        }
        for (JsonNode chapter : chapters) {
            long chapterUid = chapter.path("chapterUid").asLong(0);
            String title = firstText(chapter, "title", "chapterTitle", "chapterName");
            if (chapterUid > 0 && !title.isBlank()) {
                chapterTitles.put(chapterUid, title);
            }
        }
    }

    private String resolveChapterTitle(JsonNode node, Map<Long, String> chapterTitles) {
        String title = firstText(node, "chapterTitle", "chapterName", "title");
        if (!title.isBlank()) {
            return title;
        }
        long chapterUid = node.path("chapterUid").asLong(0);
        return chapterTitles.getOrDefault(chapterUid, "");
    }

    private String firstText(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            String value = node.path(fieldName).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    /**
     * 持久化单条笔记（create or update）
     */
    private Mono<Void> saveNote(String resourceName, WereadNote.Spec spec) {
        return extensionClient.fetch(WereadNote.class, resourceName)
                .onErrorResume(e -> Mono.empty())
                .flatMap(existing -> {
                    existing.setSpec(spec);
                    return extensionClient.update(existing);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    WereadNote note = new WereadNote();
                    Metadata meta = new Metadata();
                    meta.setName(resourceName);
                    note.setMetadata(meta);
                    note.setSpec(spec);
                    return extensionClient.create(note);
                }))
                .then();
    }

    /**
     * 将 ID 中可能存在的特殊字符替换为安全字符，用于生成 Kubernetes 资源名称
     */
    private String sanitizeId(String id) {
        return id.toLowerCase().replaceAll("[^a-z0-9\\-]", "-");
    }

    // -------------------------------------------------------------------------
    // 书籍保存（保持不变）
    // -------------------------------------------------------------------------

    private Mono<Void> saveBook(String bookId, JsonNode baseInfo, JsonNode detailInfo, JsonNode progress,
                               int noteCount, int reviewCount, long sortTime) {
        WereadBook.Spec spec = new WereadBook.Spec();
        spec.setBookId(bookId);

        String cover = detailInfo.has("cover") ? detailInfo.path("cover").asText() : baseInfo.path("cover").asText();
        if (cover != null) cover = cover.replace("/s_", "/t7_");
        spec.setCover(cover);

        String author = detailInfo.has("author") ? detailInfo.path("author").asText() : baseInfo.path("author").asText();
        if (author != null) author = author.replaceAll("\\[(.*?)\\]", "【$1】");
        spec.setAuthor(author);

        spec.setTitle(detailInfo.has("title") ? detailInfo.path("title").asText() : baseInfo.path("title").asText());
        spec.setPcUrl(calculatePcUrl(bookId));

        spec.setIntro(detailInfo.path("intro").asText(""));
        spec.setPublisher(detailInfo.path("publisher").asText(""));
        spec.setPublishTime(detailInfo.path("publishTime").asText(""));
        spec.setIsbn(detailInfo.path("isbn").asText(""));
        spec.setCategory(detailInfo.path("category").asText(baseInfo.path("category").asText("")));
        spec.setTotalWords(detailInfo.path("totalWords").asInt(0));

        double finalProgress = 0;
        if (progress.has("book")) {
            JsonNode bookProgress = progress.path("book");
            finalProgress = bookProgress.path("progress").asDouble(0);
            spec.setReadingTime(bookProgress.path("readingTime").asInt(0) / 60);

            long finishTime = bookProgress.path("finishTime").asLong(0) * 1000L;
            if (finishTime > 0) spec.setFinishTime(finishTime);
        }
        spec.setProgress(finalProgress);
        spec.setLastReadTime(sortTime > 0 ? sortTime : System.currentTimeMillis());

        int finished = detailInfo.path("finished").asInt(0);
        if (finished == 1 || finalProgress >= 100) {
            spec.setReadInfo(3);
        } else if (finalProgress > 0) {
            spec.setReadInfo(2);
        } else {
            spec.setReadInfo(1);
        }

        spec.setNoteCount(noteCount);
        spec.setReviewCount(reviewCount);

        String resourceName = "wereadbook-" + bookId;
        return extensionClient.fetch(WereadBook.class, resourceName)
                .onErrorResume(e -> Mono.empty())
                .flatMap(existing -> {
                    existing.setSpec(spec);
                    return extensionClient.update(existing);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    WereadBook newBook = new WereadBook();
                    run.halo.app.extension.Metadata metadata = new run.halo.app.extension.Metadata();
                    metadata.setName(resourceName);
                    newBook.setMetadata(metadata);
                    newBook.setSpec(spec);
                    return extensionClient.create(newBook);
                }))
                .then();
    }

    private String calculatePcUrl(String bookId) {
        try {
            String md5 = md5(bookId);
            Object[] fa = getFa(bookId);
            StringBuilder sb = new StringBuilder(md5.substring(0, 3));
            sb.append(fa[0]);
            sb.append("2").append(md5.substring(md5.length() - 2));

            for (String part : ((String) fa[1]).split(",")) {
                String hexLen = Integer.toHexString(part.length());
                if (hexLen.length() == 1) sb.append("0");
                sb.append(hexLen).append(part);
            }

            if (sb.length() < 20) {
                sb.append(md5.substring(0, 20 - sb.length()));
            }

            sb.append(md5(sb.toString()).substring(0, 3));
            return "https://weread.qq.com/web/reader/" + sb.toString();
        } catch (Exception e) {
            return "https://weread.qq.com/web/reader/" + bookId;
        }
    }

    private Object[] getFa(String id) {
        if (id.matches("^\\d*$")) {
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < id.length(); i += 9) {
                String sub = id.substring(i, Math.min(i + 9, id.length()));
                parts.add(Long.toHexString(Long.parseLong(sub)));
            }
            return new Object[]{"3", String.join(",", parts)};
        } else {
            StringBuilder sb = new StringBuilder();
            for (char c : id.toCharArray()) {
                sb.append(Integer.toHexString((int) c));
            }
            return new Object[]{"4", sb.toString()};
        }
    }

    private String md5(String input) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] messageDigest = md.digest(input.getBytes());
        StringBuilder hexString = new StringBuilder();
        for (byte b : messageDigest) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
