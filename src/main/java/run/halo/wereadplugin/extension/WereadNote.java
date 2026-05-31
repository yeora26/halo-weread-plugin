package run.halo.wereadplugin.extension;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 微信读书笔记（划线、想法、书评）
 *
 * @author haike
 * @date 2026-04-23
 * @version 2.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@GVK(group = "run.halo.plugin.wereadplugin",
     version = "v1beta1",
     kind = "WereadNote",
     plural = "wereadnotes",
     singular = "wereadnote")
public class WereadNote extends AbstractExtension {

    private Spec spec;

    @Data
    public static class Spec {
        /**
         * 关联的书籍 ID
         */
        private String bookId;

        /**
         * 笔记类型：
         * - bookmark: 划线（高亮）
         * - review:   划线感想/想法
         * - bookReview: 整本书的书评
         */
        private String type;

        /**
         * 划线原文或想法/书评内容
         */
        private String content;

        /**
         * 划线感想关联的原文摘要（review 类型使用）
         */
        private String abstractContent;

        /**
         * 所属章节标题
         */
        private String chapterTitle;

        /**
         * 章节 UID，用于排序与分组（越小越靠前）
         */
        private Long chapterUid;

        /**
         * 划线在书中的位置范围标记（bookmark 类型）
         */
        private String range;

        /**
         * 划线的唯一 ID（对应微信读书 bookmarkId 或 reviewId）
         */
        private String markId;

        /**
         * 创建时间（毫秒时间戳）
         */
        private Long createTime;

        /**
         * 划线颜色样式（1=黄色, 2=红色, 3=蓝色, 4=紫色）
         */
        private Integer colorStyle;
    }
}
