package run.halo.wereadplugin.service;

import org.springframework.stereotype.Component;
import run.halo.wereadplugin.extension.WereadBook;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Component
public class WeReadShelfRenderer {

    private static final int MAX_STAGGER_INDEX = 12;

    public String render(List<WereadBook> books) {
        StringBuilder html = new StringBuilder();
        html.append(styles());
        html.append("<section id=\"wrShelf\" class=\"wr-shelf\" aria-label=\"微信读书书架\">");
        if (books.isEmpty()) {
            html.append("""
                    <div class="wr-empty">
                      <strong>书架是空的</strong>
                      <p>同步微信读书后，书籍会显示在这里。</p>
                    </div>
                    """);
        } else {
            appendHeader(html, books);
            groupByYear(books).forEach((year, yearBooks) -> appendYear(html, year, yearBooks));
        }
        html.append("</section>");
        html.append("""
                <div id="wrNotesModal" class="wr-notes-modal" aria-hidden="true">
                  <div class="wr-notes-dialog" role="dialog" aria-modal="true" aria-labelledby="wrNotesTitle">
                    <button class="wr-notes-close" type="button" aria-label="关闭读书笔记">
                      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 6l12 12M18 6 6 18"/></svg>
                    </button>
                    <div id="wrNotesBody" class="wr-notes-body"></div>
                  </div>
                </div>
                """);
        html.append(script());
        return html.toString();
    }

    private Map<String, List<WereadBook>> groupByYear(List<WereadBook> books) {
        Map<String, List<WereadBook>> groups = new TreeMap<>(yearOrder());
        SimpleDateFormat yearFormat = new SimpleDateFormat("yyyy");
        for (WereadBook book : books) {
            Long lastReadTime = book.getSpec().getLastReadTime();
            String year = lastReadTime != null && lastReadTime > 0
                    ? yearFormat.format(new Date(lastReadTime))
                    : "其他";
            groups.computeIfAbsent(year, ignored -> new ArrayList<>()).add(book);
        }
        return groups;
    }

    private Comparator<String> yearOrder() {
        return (first, second) -> {
            boolean firstOther = "其他".equals(first);
            boolean secondOther = "其他".equals(second);
            if (firstOther != secondOther) {
                return firstOther ? 1 : -1;
            }
            return second.compareTo(first);
        };
    }

    private void appendHeader(StringBuilder html, List<WereadBook> books) {
        long finished = books.stream().filter(this::isFinished).count();
        int bookmarks = books.stream().mapToInt(book -> value(book.getSpec().getNoteCount())).sum();
        int reviews = books.stream().mapToInt(book -> value(book.getSpec().getReviewCount())).sum();
        html.append("""
                <header class="wr-head">
                  <div class="wr-head-title">
                    <h2>书架</h2>
                    <p>来自微信读书的同步藏书</p>
                  </div>
                  <div class="wr-head-stats">
                """)
                .append("<span><strong>").append(books.size()).append("</strong>藏书</span>")
                .append("<span><strong>").append(finished).append("</strong>已读完</span>")
                .append("<span><strong>").append(bookmarks).append("</strong>划线</span>")
                .append("<span><strong>").append(reviews).append("</strong>想法</span>")
                .append("</div></header>");
    }

    private void appendYear(StringBuilder html, String year, List<WereadBook> books) {
        List<WereadBook> sorted = books.stream()
                .sorted(Comparator.comparingLong(this::lastReadTime).reversed())
                .toList();
        html.append("<section class=\"wr-year\"><header class=\"wr-year-head\"><h3>")
                .append(escape(year)).append("</h3><span>")
                .append(sorted.size()).append(" 本</span><i></i></header><div class=\"wr-grid\">");
        for (int index = 0; index < sorted.size(); index++) {
            appendBook(html, sorted.get(index), Math.min(index, MAX_STAGGER_INDEX));
        }
        html.append("</div></section>");
    }

    private void appendBook(StringBuilder html, WereadBook book, int staggerIndex) {
        WereadBook.Spec spec = book.getSpec();
        int progress = progressOf(spec);
        boolean finished = isFinished(book);
        html.append("<article class=\"wr-book\" tabindex=\"0\" role=\"button\"")
                .append(" style=\"--i:").append(staggerIndex).append("\"")
                .append(" aria-label=\"打开《").append(escape(spec.getTitle())).append("》的读书笔记\"")
                .append(" data-book-id=\"").append(escape(spec.getBookId())).append("\"")
                .append(" data-title=\"").append(escape(spec.getTitle())).append("\"")
                .append(" data-author=\"").append(escape(spec.getAuthor())).append("\"")
                .append(" data-cover=\"").append(escape(spec.getCover())).append("\">")
                .append("<div class=\"wr-cover-wrap\">");
        if (spec.getCover() != null && !spec.getCover().isBlank()) {
            html.append("<img class=\"wr-cover\" loading=\"lazy\" alt=\"")
                    .append(escape(spec.getTitle())).append(" 封面\" src=\"")
                    .append(escape(spec.getCover())).append("\">");
        } else {
            html.append("<div class=\"wr-cover wr-cover-empty\" aria-hidden=\"true\"><span>")
                    .append(escape(firstChar(spec.getTitle()))).append("</span></div>");
        }
        if (finished) {
            html.append("<span class=\"wr-badge\">已读完</span>");
        } else if (progress > 0) {
            html.append("<span class=\"wr-badge\">").append(progress).append("%</span>")
                    .append("<div class=\"wr-progress\"><i style=\"--p:").append(progress).append("%\"></i></div>");
        }
        html.append("<div class=\"wr-overlay\" aria-hidden=\"true\"><span>")
                .append(value(spec.getNoteCount())).append(" 条划线 · ")
                .append(value(spec.getReviewCount())).append(" 则想法</span><em>查看读书笔记</em></div>")
                .append("</div><h3>").append(escape(spec.getTitle())).append("</h3><p>")
                .append(escape(spec.getAuthor())).append("</p></article>");
    }

    private String firstChar(String title) {
        return title == null || title.isBlank() ? "书" : title.substring(0, 1);
    }

    private boolean isFinished(WereadBook book) {
        WereadBook.Spec spec = book.getSpec();
        boolean hasFinishTime = spec.getFinishTime() != null && spec.getFinishTime() > 0;
        return hasFinishTime || progressOf(spec) >= 99;
    }

    private int progressOf(WereadBook.Spec spec) {
        if (spec.getProgress() == null) {
            return 0;
        }
        return (int) Math.max(0, Math.min(100, Math.round(spec.getProgress())));
    }

    private long lastReadTime(WereadBook book) {
        Long value = book.getSpec().getLastReadTime();
        return value != null ? value : 0L;
    }

    private int value(Integer value) {
        return value != null ? value : 0;
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String styles() {
        return """
                <style>
                .wr-shelf{--wr-ink:#1d1d1f;--wr-muted:#86868b;--wr-line:#d2d2d7;--wr-fill:#f5f5f7;--wr-accent:#0071e3;--wr-ease:cubic-bezier(.28,.11,.32,1);max-width:1200px;margin:0 auto;padding:clamp(40px,6vw,72px) clamp(20px,5vw,56px);color:var(--wr-ink);font-family:-apple-system,BlinkMacSystemFont,"SF Pro Text","PingFang SC","Helvetica Neue","Segoe UI",sans-serif;letter-spacing:0;-webkit-font-smoothing:antialiased}
                .wr-shelf *,.wr-notes-modal *{box-sizing:border-box}
                .wr-head{display:flex;flex-wrap:wrap;align-items:flex-end;justify-content:space-between;gap:24px 48px;animation:wr-rise .6s var(--wr-ease) backwards}
                .wr-head h2{margin:0;font-size:clamp(32px,5vw,48px);font-weight:700;line-height:1.1;letter-spacing:-.02em}
                .wr-head-title p{margin:10px 0 0;color:var(--wr-muted);font-size:15px;line-height:1.5}
                .wr-head-stats{display:flex;flex-wrap:wrap;gap:16px 36px;animation:wr-rise .6s var(--wr-ease) .12s backwards}
                .wr-head-stats span{color:var(--wr-muted);font-size:12px;line-height:1.5}
                .wr-head-stats strong{display:block;color:var(--wr-ink);font-size:28px;font-weight:600;line-height:1.2;letter-spacing:-.01em;font-variant-numeric:tabular-nums}
                .wr-year{padding-top:64px}
                .wr-year-head{display:flex;align-items:baseline;gap:14px;padding-bottom:16px;margin-bottom:32px;border-bottom:1px solid var(--wr-line)}
                .wr-year-head h3{margin:0;font-size:clamp(22px,3vw,28px);font-weight:700;letter-spacing:-.015em}
                .wr-year-head span{color:var(--wr-muted);font-size:13px;line-height:1;white-space:nowrap}
                .wr-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(140px,1fr));gap:40px 24px}
                .wr-book{min-width:0;cursor:pointer;animation:wr-rise .55s var(--wr-ease) backwards;animation-delay:calc(var(--i,0)*45ms + .15s);transition:transform .25s var(--wr-ease)}
                .wr-book:hover{transform:translateY(-4px)}
                .wr-book:focus-visible{outline:2px solid var(--wr-accent);outline-offset:4px;border-radius:12px}
                .wr-cover-wrap{position:relative;aspect-ratio:2 / 3;overflow:hidden;border-radius:12px;background:var(--wr-fill);box-shadow:0 2px 12px rgba(0,0,0,.06);transition:box-shadow .3s var(--wr-ease)}
                .wr-book:hover .wr-cover-wrap{box-shadow:0 16px 40px rgba(0,0,0,.14)}
                .wr-shelf .wr-cover{position:absolute;inset:0;width:100%;height:100%;margin:0!important;object-fit:cover;transition:transform .4s var(--wr-ease)}
                .wr-book:hover .wr-cover{transform:scale(1.03)}
                .wr-cover-empty{display:grid;place-items:center;color:#aeaeb2}
                .wr-cover-empty span{font-size:28px;font-weight:600}
                .wr-badge{position:absolute;top:10px;right:10px;z-index:2;padding:4px 10px;border-radius:999px;background:rgba(255,255,255,.88);backdrop-filter:blur(8px);box-shadow:0 1px 4px rgba(0,0,0,.12);color:var(--wr-ink);font-size:11px;font-weight:500;line-height:1.3;font-variant-numeric:tabular-nums}
                .wr-progress{position:absolute;right:0;bottom:0;left:0;z-index:2;height:3px;background:rgba(0,0,0,.08)}
                .wr-progress i{display:block;width:var(--p,0%);height:100%;background:var(--wr-accent);animation:wr-grow .8s var(--wr-ease) .4s backwards}
                .wr-overlay{position:absolute;inset:0;z-index:1;display:flex;flex-direction:column;justify-content:flex-end;gap:4px;padding:14px;background:linear-gradient(to top,rgba(0,0,0,.72),rgba(0,0,0,.06) 55%,transparent);color:#fff;font-size:12px;line-height:1.5;opacity:0;transition:opacity .25s var(--wr-ease)}
                .wr-overlay span,.wr-overlay em{transform:translateY(6px);transition:transform .25s var(--wr-ease)}
                .wr-overlay em{font-style:normal;font-weight:500;text-decoration:underline;text-underline-offset:3px;transition-delay:.04s}
                .wr-book:hover .wr-overlay,.wr-book:focus-visible .wr-overlay{opacity:1}
                .wr-book:hover .wr-overlay span,.wr-book:hover .wr-overlay em,.wr-book:focus-visible .wr-overlay span,.wr-book:focus-visible .wr-overlay em{transform:none}
                .wr-book h3{overflow:hidden;margin:12px 0 2px;font-size:13px;font-weight:500;line-height:1.4;text-overflow:ellipsis;white-space:nowrap}
                .wr-book p{overflow:hidden;margin:0;color:var(--wr-muted);font-size:12px;line-height:1.5;text-overflow:ellipsis;white-space:nowrap}
                .wr-empty{padding:80px 24px;border-radius:18px;background:var(--wr-fill);text-align:center}
                .wr-empty strong{display:block;font-size:20px;font-weight:600;letter-spacing:-.01em}
                .wr-empty p{margin:10px 0 0;color:var(--wr-muted);font-size:14px}
                @keyframes wr-rise{from{opacity:0;transform:translateY(16px)}to{opacity:1;transform:none}}
                @keyframes wr-grow{from{width:0}}
                @keyframes wr-fade{from{opacity:0}}
                @keyframes wr-spin{to{transform:rotate(360deg)}}
                .wr-notes-modal{position:fixed;z-index:10000;inset:0;display:none;align-items:center;justify-content:center;padding:24px;background:rgba(0,0,0,.4);backdrop-filter:blur(20px);color:var(--wr-ink,#1d1d1f);font-family:-apple-system,BlinkMacSystemFont,"SF Pro Text","PingFang SC","Helvetica Neue","Segoe UI",sans-serif;letter-spacing:0;transition:opacity .15s ease-in}
                .wr-notes-modal.is-open{display:flex;animation:wr-fade .25s ease-out}
                .wr-notes-modal.is-closing{opacity:0}
                .wr-notes-dialog{position:relative;width:min(720px,100%);max-height:88vh;overflow:auto;border-radius:18px;background:#fff;box-shadow:0 24px 70px rgba(0,0,0,.3);transition:opacity .15s ease-in,transform .15s ease-in}
                .is-open .wr-notes-dialog{animation:wr-modal-in .35s cubic-bezier(.28,.11,.32,1) backwards}
                .is-closing .wr-notes-dialog{opacity:0;transform:translateY(10px)}
                @keyframes wr-modal-in{from{opacity:0;transform:translateY(24px) scale(.98)}}
                .wr-notes-close{position:sticky;z-index:2;top:14px;float:right;display:grid;place-items:center;width:30px;height:30px;margin:14px 14px -44px 0;border:none;border-radius:50%;background:rgba(0,0,0,.06);color:#1d1d1f;cursor:pointer;transition:background .15s ease}
                .wr-notes-close:hover{background:rgba(0,0,0,.12)}
                .wr-notes-close svg{width:12px;fill:none;stroke:currentColor;stroke-width:2.2;stroke-linecap:round}
                .wr-notes-body{min-height:320px}
                .wr-note-hero{display:grid;grid-template-columns:96px minmax(0,1fr);gap:24px;padding:40px 48px 32px;background:#f5f5f7;border-bottom:1px solid #e8e8ed}
                .wr-notes-modal .wr-note-cover{width:96px;height:134px;margin:0!important;border-radius:8px;object-fit:cover;box-shadow:0 8px 20px rgba(0,0,0,.14)}
                .wr-note-hero h2{margin:4px 36px 8px 0;font-size:clamp(22px,3vw,28px);font-weight:700;line-height:1.25;letter-spacing:-.015em}
                .wr-note-author{margin:0;color:#86868b;font-size:14px}
                .wr-note-stats{display:flex;gap:24px;margin-top:22px}
                .wr-note-stats span{color:#86868b;font-size:12px;line-height:1.4}
                .wr-note-stats strong{display:block;margin-bottom:2px;color:#1d1d1f;font-size:22px;font-weight:600;line-height:1.2;font-variant-numeric:tabular-nums}
                .wr-manuscript{padding:32px 48px 56px}
                .wr-book-review{margin-bottom:28px;padding:20px 22px;border-radius:12px;background:#f5f5f7}
                .wr-book-review:before{content:"总评";display:block;margin-bottom:10px;color:#86868b;font-size:11px;font-weight:500;letter-spacing:.5px}
                .wr-book-review p{margin:0;font-size:15px;line-height:1.8}
                .wr-chapter{margin-top:32px}.wr-chapter:first-child{margin-top:0}
                .wr-chapter-title{display:grid;grid-template-columns:auto 1fr auto;align-items:center;gap:12px;margin-bottom:16px}
                .wr-chapter-index{color:#86868b;font-size:11px;font-weight:500;letter-spacing:.5px;font-variant-numeric:tabular-nums}
                .wr-chapter-title h3{margin:0;font-size:16px;font-weight:600;line-height:1.5}
                .wr-chapter-title span:last-child{color:#86868b;font-size:12px}
                .wr-mark{position:relative;margin:0 0 12px;padding:16px 18px 16px 21px;border-radius:12px;background:#f5f5f7;overflow:hidden}
                .wr-mark:before{content:"";position:absolute;top:0;bottom:0;left:0;width:3px;background:var(--mark,#d2aa54)}
                .wr-mark p{margin:0;font-size:15px;line-height:1.8;white-space:pre-wrap}
                .wr-mark time,.wr-thought time,.wr-book-review time{display:block;margin-top:10px;color:#aeaeb2;font-size:11px;line-height:1.3;text-align:right}
                .wr-thought{margin:14px 0 16px;padding:16px 18px;border-radius:12px;background:#f5f5f7}
                .wr-thought blockquote{margin:0 0 12px;padding:0 0 0 12px;border-left:2px solid #d2d2d7;color:#86868b;font-size:13px;line-height:1.7}
                .wr-thought p{margin:0;font-size:15px;line-height:1.75;white-space:pre-wrap}
                .wr-state{display:grid;min-height:320px;place-content:center;padding:40px;text-align:center;color:#86868b}
                .wr-state i{width:26px;height:26px;margin:0 auto 14px;border:2px solid #e8e8ed;border-top-color:#0071e3;border-radius:50%;animation:wr-spin .8s linear infinite}
                .wr-state strong{color:#1d1d1f;font-size:16px;font-weight:600}.wr-state p{margin:8px 0 0;font-size:13px}
                @media(max-width:640px){.wr-shelf{padding:32px 18px 48px}.wr-year{padding-top:48px}.wr-grid{grid-template-columns:repeat(auto-fill,minmax(100px,1fr));gap:24px 14px}.wr-book h3{font-size:12px}.wr-note-hero{grid-template-columns:72px minmax(0,1fr);gap:16px;padding:28px 22px 22px}.wr-note-cover{width:72px;height:101px}.wr-manuscript{padding:24px 22px 40px}}
                @media(prefers-reduced-motion:reduce){.wr-head,.wr-head-stats,.wr-book,.wr-progress i,.wr-notes-modal.is-open,.is-open .wr-notes-dialog{animation:none}.wr-notes-modal,.wr-notes-dialog,.wr-notes-close,.wr-book,.wr-cover,.wr-cover-wrap,.wr-overlay,.wr-overlay span,.wr-overlay em{transition:none}.wr-state i{animation-duration:1.6s}}
                </style>
                """;
    }

    private String script() {
        return """
                <script>
                (() => {
                  const shelf = document.getElementById('wrShelf');
                  const modal = document.getElementById('wrNotesModal');
                  const body = document.getElementById('wrNotesBody');
                  if (!shelf || !modal || !body || shelf.dataset.ready) return;
                  shelf.dataset.ready = 'true';
                  const esc = value => {
                    const node = document.createElement('span');
                    node.textContent = value == null ? '' : String(value);
                    return node.innerHTML.replaceAll('"','&quot;').replaceAll("'","&#39;");
                  };
                  const date = value => {
                    if (!value) return '';
                    return new Intl.DateTimeFormat('zh-CN', {year:'numeric',month:'long',day:'numeric'}).format(new Date(value));
                  };
                  const markColor = style => ({1:'#d2aa54',2:'#b85b50',3:'#557b91',4:'#79658d'}[style] || '#d2aa54');
                  const state = (title, message, loading = false) => `<div class="wr-state">${loading ? '<i></i>' : ''}<strong>${esc(title)}</strong><p>${esc(message)}</p></div>`;
                  const renderNotes = (book, notes) => {
                    const reviews = (notes.bookReviews || []).map(item => `<div class="wr-book-review"><p>${esc(item.content)}</p><time>${date(item.createTime)}</time></div>`).join('');
                    const chapters = (notes.chapters || []).map((chapter, index) => {
                      const marks = (chapter.bookmarks || []).map(item => `<article class="wr-mark" style="--mark:${markColor(item.colorStyle)}"><p>${esc(item.content)}</p><time>${date(item.createTime)}</time></article>`).join('');
                      const thoughts = (chapter.reviews || []).map(item => `<article class="wr-thought">${item.abstractContent ? `<blockquote>${esc(item.abstractContent)}</blockquote>` : ''}<p>${esc(item.content)}</p><time>${date(item.createTime)}</time></article>`).join('');
                      const count = (chapter.bookmarks || []).length + (chapter.reviews || []).length;
                      return `<section class="wr-chapter"><header class="wr-chapter-title"><span class="wr-chapter-index">CH.${String(index + 1).padStart(2,'0')}</span><h3>${esc(chapter.chapterTitle || '未分章节')}</h3><span>${count} 则</span></header>${marks}${thoughts}</section>`;
                    }).join('');
                    body.innerHTML = `<header class="wr-note-hero"><img class="wr-note-cover" src="${esc(book.cover)}" alt=""><div><h2 id="wrNotesTitle">${esc(book.title)}</h2><p class="wr-note-author">${esc(book.author)}</p><div class="wr-note-stats"><span><strong>${notes.totalBookmarks || 0}</strong>处划线</span><span><strong>${notes.totalReviews || 0}</strong>则想法</span><span><strong>${(notes.bookReviews || []).length}</strong>篇书评</span></div></div></header><main class="wr-manuscript">${reviews}${chapters || state('尚无读书笔记','同步书籍后，划线与想法会在这里出现。')}</main>`;
                  };
                  const openBook = async card => {
                    const book = card.dataset;
                    modal.classList.add('is-open');
                    modal.setAttribute('aria-hidden','false');
                    modal.querySelector('.wr-notes-dialog').scrollTop = 0;
                    document.body.style.overflow = 'hidden';
                    body.innerHTML = state('正在展卷','从书页间拾取划线与想法…',true);
                    try {
                      const response = await fetch(`/halo-weread-plugin/books/${encodeURIComponent(book.bookId)}/notes`);
                      if (!response.ok) throw new Error('request failed');
                      renderNotes(book, await response.json());
                    } catch (error) {
                      body.innerHTML = state('未能展卷','划线暂时没有加载成功，请稍后再试。');
                    }
                  };
                  const reduceMotion = () => matchMedia('(prefers-reduced-motion: reduce)').matches;
                  const close = () => {
                    if (!modal.classList.contains('is-open') || modal.classList.contains('is-closing')) return;
                    modal.classList.add('is-closing');
                    setTimeout(() => {
                      modal.classList.remove('is-open', 'is-closing');
                      modal.setAttribute('aria-hidden','true');
                      document.body.style.overflow = '';
                    }, reduceMotion() ? 0 : 150);
                  };
                  shelf.addEventListener('click', event => {
                    const card = event.target.closest('.wr-book');
                    if (card) openBook(card);
                  });
                  shelf.addEventListener('keydown', event => {
                    const card = event.target.closest('.wr-book');
                    if (card && (event.key === 'Enter' || event.key === ' ')) {
                      event.preventDefault();
                      openBook(card);
                    }
                  });
                  modal.querySelector('.wr-notes-close').addEventListener('click', close);
                  modal.addEventListener('click', event => { if (event.target === modal) close(); });
                  document.addEventListener('keydown', event => { if (event.key === 'Escape' && modal.classList.contains('is-open')) close(); });
                })();
                </script>
                """;
    }
}
