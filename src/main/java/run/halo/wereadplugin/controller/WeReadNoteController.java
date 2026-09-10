package run.halo.wereadplugin.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import run.halo.wereadplugin.service.WeReadNoteQueryService;

import java.util.Map;

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

    private final WeReadNoteQueryService noteQueryService;

    public WeReadNoteController(WeReadNoteQueryService noteQueryService) {
        this.noteQueryService = noteQueryService;
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

        return noteQueryService.getNotesByBook(bookId)
                .map(ResponseEntity::ok)
                .onErrorResume(e -> {
                    log.error("获取书籍笔记失败 bookId={}", bookId, e);
                    return Mono.just(ResponseEntity.internalServerError()
                            .<Map<String, Object>>body(Map.of("message", "获取笔记失败: " + e.getMessage())));
                });
    }
}
