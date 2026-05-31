package run.halo.wereadplugin.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.wereadplugin.extension.WereadNote;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 微信读书笔记查询接口
 *
 * @author haike
 * @date 2026-05-25
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/halo-weread-plugin")
public class WeReadNoteController {

    private final ReactiveExtensionClient extensionClient;

    public WeReadNoteController(ReactiveExtensionClient extensionClient) {
        this.extensionClient = extensionClient;
    }

    /**
     * 按书籍 ID 获取所有笔记，返回按章节分组的结构
     * GET /api/admin/halo-weread-plugin/books/{bookId}/notes
     *
     * 返回结构：
     * {
     *   "chapters": [
     *     {
     *       "chapterTitle": "第一章",
     *       "chapterUid": 1,
     *       "bookmarks": [ { type, content, createTime, colorStyle, markId } ],
     *       "reviews":   [ { type, content, abstractContent, createTime, markId } ]
     *     }
     *   ],
     *   "bookReviews": [ { content, createTime } ],
     *   "total": 12
     * }
     */
    @GetMapping("/books/{bookId}/notes")
    public Mono<ResponseEntity<Map<String, Object>>> getNotesByBook(
            @PathVariable("bookId") String bookId) {

        return extensionClient
                .list(WereadNote.class,
                      note -> bookId.equals(note.getSpec() != null ? note.getSpec().getBookId() : null),
                      null)
                .collectList()
                .map(notes -> {
                    // 分离书评
                    List<Map<String, Object>> bookReviews = notes.stream()
                            .filter(n -> "bookReview".equals(n.getSpec().getType()))
                            .sorted(Comparator.comparingLong(n -> n.getSpec().getCreateTime() != null
                                    ? n.getSpec().getCreateTime() : 0L))
                            .map(n -> {
                                Map<String, Object> m = new LinkedHashMap<>();
                                m.put("markId", n.getSpec().getMarkId());
                                m.put("content", n.getSpec().getContent());
                                m.put("createTime", n.getSpec().getCreateTime());
                                return m;
                            })
                            .collect(Collectors.toList());

                    // 按章节 UID 分组（划线 + 想法）
                    Map<Long, List<WereadNote>> byChapter = notes.stream()
                            .filter(n -> !"bookReview".equals(n.getSpec().getType()))
                            .collect(Collectors.groupingBy(
                                    n -> n.getSpec().getChapterUid() != null ? n.getSpec().getChapterUid() : 0L
                            ));

                    // 章节排序（chapterUid 升序 = 书籍顺序）
                    List<Map<String, Object>> chapters = byChapter.entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .map(entry -> {
                                List<WereadNote> chapterNotes = entry.getValue();
                                String chapterTitle = chapterNotes.stream()
                                        .map(n -> n.getSpec().getChapterTitle())
                                        .filter(t -> t != null && !t.isBlank())
                                        .findFirst()
                                        .orElse("未分章节");

                                List<Map<String, Object>> bookmarks = chapterNotes.stream()
                                        .filter(n -> "bookmark".equals(n.getSpec().getType()))
                                        .sorted(Comparator.comparingLong(n -> n.getSpec().getCreateTime() != null
                                                ? n.getSpec().getCreateTime() : 0L))
                                        .map(n -> {
                                            Map<String, Object> m = new LinkedHashMap<>();
                                            m.put("markId", n.getSpec().getMarkId());
                                            m.put("content", n.getSpec().getContent());
                                            m.put("colorStyle", n.getSpec().getColorStyle());
                                            m.put("createTime", n.getSpec().getCreateTime());
                                            return m;
                                        })
                                        .collect(Collectors.toList());

                                List<Map<String, Object>> reviews = chapterNotes.stream()
                                        .filter(n -> "review".equals(n.getSpec().getType()))
                                        .sorted(Comparator.comparingLong(n -> n.getSpec().getCreateTime() != null
                                                ? n.getSpec().getCreateTime() : 0L))
                                        .map(n -> {
                                            Map<String, Object> m = new LinkedHashMap<>();
                                            m.put("markId", n.getSpec().getMarkId());
                                            m.put("content", n.getSpec().getContent());
                                            m.put("abstractContent", n.getSpec().getAbstractContent());
                                            m.put("createTime", n.getSpec().getCreateTime());
                                            return m;
                                        })
                                        .collect(Collectors.toList());

                                Map<String, Object> chapterMap = new LinkedHashMap<>();
                                chapterMap.put("chapterUid", entry.getKey());
                                chapterMap.put("chapterTitle", chapterTitle);
                                chapterMap.put("bookmarks", bookmarks);
                                chapterMap.put("reviews", reviews);
                                return chapterMap;
                            })
                            .collect(Collectors.toList());

                    long totalBookmarks = notes.stream()
                            .filter(n -> "bookmark".equals(n.getSpec().getType()))
                            .count();
                    long totalReviews = notes.stream()
                            .filter(n -> "review".equals(n.getSpec().getType()))
                            .count();

                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("chapters", chapters);
                    result.put("bookReviews", bookReviews);
                    result.put("totalBookmarks", totalBookmarks);
                    result.put("totalReviews", totalReviews);
                    result.put("total", notes.size());

                    return ResponseEntity.ok(result);
                })
                .onErrorResume(e -> {
                    log.error("获取书籍笔记失败 bookId={}", bookId, e);
                    return Mono.just(ResponseEntity.internalServerError()
                            .<Map<String, Object>>body(Map.of("message", "获取笔记失败: " + e.getMessage())));
                });
    }
}
