package run.halo.wereadplugin.controller.theme;

import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.wereadplugin.extension.WereadBook;
import run.halo.wereadplugin.service.WeReadNoteQueryService;
import run.halo.wereadplugin.service.WeReadShelfRenderer;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/api/ext/halo-weread-plugin", "/halo-weread-plugin"})
public class WeReadThemeController {

    private final ReactiveExtensionClient extensionClient;
    private final WeReadNoteQueryService noteQueryService;
    private final WeReadShelfRenderer shelfRenderer;

    public WeReadThemeController(
            ReactiveExtensionClient extensionClient,
            WeReadNoteQueryService noteQueryService,
            WeReadShelfRenderer shelfRenderer) {
        this.extensionClient = extensionClient;
        this.noteQueryService = noteQueryService;
        this.shelfRenderer = shelfRenderer;
    }

    /**
     * 供博客主题调用的公开书架，仅返回未隐藏的书籍。
     */
    @GetMapping("/books")
    public Mono<List<WereadBook>> listBooks() {
        return extensionClient.list(
                        WereadBook.class,
                        book -> book.getSpec() != null && !Boolean.TRUE.equals(book.getSpec().getHidden()),
                        (first, second) -> Long.compare(lastReadTime(second), lastReadTime(first)))
                .collectList();
    }

    /**
     * 获取公开书籍的划线、想法与书评。隐藏书籍不会通过此接口泄露笔记。
     */
    @GetMapping("/books/{bookId}/notes")
    public Mono<ResponseEntity<Map<String, Object>>> getBookNotes(
            @PathVariable("bookId") String bookId) {
        return extensionClient.list(
                        WereadBook.class,
                        book -> book.getSpec() != null
                                && bookId.equals(book.getSpec().getBookId())
                                && !Boolean.TRUE.equals(book.getSpec().getHidden()),
                        null)
                .next()
                .flatMap(book -> noteQueryService.getNotesByBook(bookId))
                .map(ResponseEntity::ok)
                .switchIfEmpty(Mono.just(ResponseEntity.notFound().build()));
    }

    /**
     * 返回可直接嵌入主题页面的书架与读书笔记界面。
     */
    @GetMapping(value = "/shelf-html", produces = MediaType.TEXT_HTML_VALUE)
    public Mono<String> getShelfHtml() {
        return listBooks().map(shelfRenderer::render);
    }

    private long lastReadTime(WereadBook book) {
        Long value = book.getSpec().getLastReadTime();
        return value != null ? value : 0L;
    }
}
