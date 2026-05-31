package run.halo.wereadplugin.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.wereadplugin.extension.WereadBook;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/admin/halo-weread-plugin")
public class WeReadBookController {

    private final ReactiveExtensionClient extensionClient;

    public WeReadBookController(ReactiveExtensionClient extensionClient) {
        this.extensionClient = extensionClient;
    }

    /**
     * 获取所有已同步的书籍列表（按最后阅读时间倒序）
     */
    @GetMapping("/books")
    public Mono<List<WereadBook>> listBooks() {
        return extensionClient.list(WereadBook.class, e -> true, (e1, e2) -> {
            Long t1 = e1.getSpec().getLastReadTime();
            Long t2 = e2.getSpec().getLastReadTime();
            return Long.compare(t2 != null ? t2 : 0L, t1 != null ? t1 : 0L);
        }).collectList();
    }

    /**
     * 删除指定书籍
     */
    @DeleteMapping("/books/{name}")
    public Mono<ResponseEntity<Object>> deleteBook(@PathVariable("name") String name) {
        log.info("尝试删除书籍资源: {}", name);
        return extensionClient.fetch(WereadBook.class, name)
                .flatMap(book -> extensionClient.delete(book)
                        .thenReturn(ResponseEntity.ok().body((Object) Map.of("message", "已删除: " + book.getSpec().getTitle()))))
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("未找到书籍资源: {}", name);
                    return Mono.just(ResponseEntity.notFound().build());
                }))
                .onErrorResume(e -> {
                    log.error("删除书籍失败: {}", name, e);
                    return Mono.just(ResponseEntity.internalServerError().body((Object) Map.of("message", "删除失败: " + e.getMessage())));
                });
    }

    /**
     * 切换书籍显示/隐藏状态
     */
    @PatchMapping("/books/{name}/toggle-visibility")
    public Mono<ResponseEntity<Object>> toggleVisibility(@PathVariable("name") String name) {
        log.info("尝试切换书籍显示状态: {}", name);
        return extensionClient.fetch(WereadBook.class, name)
                .flatMap(book -> {
                    if (book.getSpec() == null) {
                        return Mono.error(new RuntimeException("书籍 Spec 数据为空"));
                    }
                    Boolean currentHidden = book.getSpec().getHidden();
                    boolean newHiddenStatus = currentHidden == null || !currentHidden;
                    book.getSpec().setHidden(newHiddenStatus);
                    
                    return extensionClient.update(book)
                            .retry(2) // 简单重试两次，应对可能的并发修改
                            .doOnSuccess(updated -> log.info("成功更新书籍 {} 隐藏状态为 {}", name, newHiddenStatus))
                            .thenReturn(ResponseEntity.ok().body((Object) Map.of(
                                    "message", newHiddenStatus ? "已隐藏" : "已显示",
                                    "hidden", newHiddenStatus
                            )));
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("未找到书籍资源，无法切换显示状态: {}", name);
                    return Mono.just(ResponseEntity.notFound().build());
                }))
                .onErrorResume(e -> {
                    log.error("操作失败，请确保插件已完全重启以应用新的模型字段: {}", name, e);
                    return Mono.just(ResponseEntity.internalServerError().body((Object) Map.of(
                        "message", "操作失败: " + e.getMessage() + "。请尝试刷新页面或重启插件。"
                    )));
                });
    }

    /**
     * 清空所有已同步书籍
     */
    @DeleteMapping("/books")
    public Mono<ResponseEntity<Object>> deleteAllBooks() {
        return extensionClient.list(WereadBook.class, e -> true, null)
                .flatMap(extensionClient::delete)
                .then(Mono.just(ResponseEntity.ok().body((Object) Map.of("message", "已清空所有书籍"))))
                .onErrorResume(e -> Mono.just(
                        ResponseEntity.internalServerError().body((Object) Map.of("message", "清空失败: " + e.getMessage()))));
    }
}
