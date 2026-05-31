package run.halo.wereadplugin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.index.IndexSpecs;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;
import run.halo.wereadplugin.extension.WereadBook;
import run.halo.wereadplugin.extension.WereadNote;
@Slf4j
@Component
public class WereadPlugin extends BasePlugin {

    private final SchemeManager schemeManager;

    public WereadPlugin(PluginContext pluginContext, SchemeManager schemeManager) {
        super(pluginContext);
        this.schemeManager = schemeManager;
        // 在构造函数注册以确保索引正确建立
        unregisterIfPresent(WereadBook.class);
        unregisterIfPresent(WereadNote.class);
        this.schemeManager.register(WereadBook.class, indexSpecs -> indexSpecs.add(
                IndexSpecs.<WereadBook, String>single("spec.bookId", String.class)
                        .indexFunc(book -> book.getSpec() == null ? null : book.getSpec().getBookId())
        ));
        this.schemeManager.register(WereadNote.class, indexSpecs -> {
            indexSpecs.add(IndexSpecs.<WereadNote, String>single("spec.bookId", String.class)
                    .indexFunc(note -> note.getSpec() == null ? null : note.getSpec().getBookId()));
            indexSpecs.add(IndexSpecs.<WereadNote, String>single("spec.markId", String.class)
                    .indexFunc(note -> note.getSpec() == null ? null : note.getSpec().getMarkId()));
            indexSpecs.add(IndexSpecs.<WereadNote, String>single("spec.type", String.class)
                    .indexFunc(note -> note.getSpec() == null ? null : note.getSpec().getType()));
        });
    }

    private void unregisterIfPresent(Class<? extends run.halo.app.extension.Extension> type) {
        try {
            Scheme scheme = this.schemeManager.get(type);
            this.schemeManager.unregister(scheme);
        } catch (Exception ignored) {
            // Scheme 尚未注册时无需处理。
        }
    }

    @Override
    public void start() {
        log.info("Weread Plugin 插件已启动。");
    }

    @Override
    public void stop() {
        log.info("Weread插件停止！");
    }
}
