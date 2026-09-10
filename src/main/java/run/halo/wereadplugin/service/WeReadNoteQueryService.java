package run.halo.wereadplugin.service;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.wereadplugin.extension.WereadNote;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class WeReadNoteQueryService {

    private final ReactiveExtensionClient extensionClient;

    public WeReadNoteQueryService(ReactiveExtensionClient extensionClient) {
        this.extensionClient = extensionClient;
    }

    public Mono<Map<String, Object>> getNotesByBook(String bookId) {
        return extensionClient
                .list(WereadNote.class,
                        note -> note.getSpec() != null && bookId.equals(note.getSpec().getBookId()),
                        null)
                .collectList()
                .map(this::buildNoteSummary);
    }

    private Map<String, Object> buildNoteSummary(List<WereadNote> notes) {
        List<Map<String, Object>> bookReviews = notes.stream()
                .filter(note -> "bookReview".equals(note.getSpec().getType()))
                .sorted(Comparator.comparingLong(this::createTime))
                .map(note -> noteMap(note, false))
                .toList();

        Map<Long, List<WereadNote>> byChapter = notes.stream()
                .filter(note -> !"bookReview".equals(note.getSpec().getType()))
                .collect(Collectors.groupingBy(
                        note -> note.getSpec().getChapterUid() != null
                                ? note.getSpec().getChapterUid()
                                : 0L
                ));

        List<Map<String, Object>> chapters = byChapter.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> chapterMap(entry.getKey(), entry.getValue()))
                .toList();

        long totalBookmarks = notes.stream()
                .filter(note -> "bookmark".equals(note.getSpec().getType()))
                .count();
        long totalReviews = notes.stream()
                .filter(note -> "review".equals(note.getSpec().getType()))
                .count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("chapters", chapters);
        result.put("bookReviews", bookReviews);
        result.put("totalBookmarks", totalBookmarks);
        result.put("totalReviews", totalReviews);
        result.put("total", notes.size());
        return result;
    }

    private Map<String, Object> chapterMap(Long chapterUid, List<WereadNote> notes) {
        String chapterTitle = notes.stream()
                .map(note -> note.getSpec().getChapterTitle())
                .filter(title -> title != null && !title.isBlank())
                .findFirst()
                .orElse("未分章节");

        List<Map<String, Object>> bookmarks = notes.stream()
                .filter(note -> "bookmark".equals(note.getSpec().getType()))
                .sorted(Comparator.comparingLong(this::createTime))
                .map(note -> noteMap(note, true))
                .toList();

        List<Map<String, Object>> reviews = notes.stream()
                .filter(note -> "review".equals(note.getSpec().getType()))
                .sorted(Comparator.comparingLong(this::createTime))
                .map(note -> noteMap(note, true))
                .toList();

        Map<String, Object> chapter = new LinkedHashMap<>();
        chapter.put("chapterUid", chapterUid);
        chapter.put("chapterTitle", chapterTitle);
        chapter.put("bookmarks", bookmarks);
        chapter.put("reviews", reviews);
        return chapter;
    }

    private Map<String, Object> noteMap(WereadNote note, boolean includeDetails) {
        WereadNote.Spec spec = note.getSpec();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("markId", spec.getMarkId());
        result.put("content", spec.getContent());
        result.put("createTime", spec.getCreateTime());
        if (includeDetails) {
            result.put("abstractContent", spec.getAbstractContent());
            result.put("colorStyle", spec.getColorStyle());
        }
        return result;
    }

    private long createTime(WereadNote note) {
        return note.getSpec().getCreateTime() != null ? note.getSpec().getCreateTime() : 0L;
    }
}
