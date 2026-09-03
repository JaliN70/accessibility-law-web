(function () {
  'use strict';

  const app = document.getElementById('app');
  const backBtn = document.getElementById('backBtn');
  const pageTitle = document.getElementById('pageTitle');
  const pageSub = document.getElementById('pageSub');
  const headerMojLink = document.getElementById('headerMojLink');
  const toast = document.getElementById('toast');

  document.getElementById('sysDate').textContent = META.updated;
  document.getElementById('sysAuthor').textContent = META.author;
  document.getElementById('lawDate').textContent = LAW.amended;

  let toastTimer;

  const APPENDIX_SECTIONS = {
    'APP-1': '基本尺寸',
    'APP-2': '視覺障礙者引導設施設計指引',
    'APP-3': '設施設計指引',
    'APP-4': '其他設施',
  };

  function normalizeText(s) {
    return String(s ?? '').normalize('NFKC');
  }

  for (const cat of LAW.categories || []) {
    for (const item of cat.articles || []) {
      if (item.label) item.label = normalizeText(item.label);
      if (item.body) item.body = normalizeText(item.body);
      for (const m of item.media || []) {
        if (m.caption) m.caption = normalizeText(m.caption);
      }
    }
  }

  function isAppendixCat(cat) {
    return cat && cat.id === 'ACCESS-appendix';
  }

  function appendixSectionTitle(item, cat) {
    if (cat && cat.id === 'ACCESS-appendix' && /^APP-[1-4]$/.test(item.art)) {
      return APPENDIX_SECTIONS[item.art] || item.art;
    }
    return null;
  }

  function showToast(msg) {
    toast.textContent = msg;
    toast.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toast.classList.remove('show'), 2200);
  }

  function primaryPdfPage(item) {
    const media = item.media || [];
    const fig = media.find((m) => m.type === 'figure' && m.page > 0);
    if (fig) return fig.page;
    const tbl = media.find((m) => m.type === 'table' && m.page > 0);
    if (tbl) return tbl.page;
    const mediaPages = media.map((m) => m.page).filter((p) => p > 0);
    if (mediaPages.length) return Math.max(...mediaPages);
    if (item.pages && item.pages.length) return Math.max(...item.pages);
    return 2;
  }

  function pdfUrl(page) {
    const q = new URLSearchParams({ file: LAW.pdfFile });
    if (LAW.pageCount) q.set('pages', String(LAW.pageCount));
    if (LAW.pageDir) q.set('pageDir', LAW.pageDir);
    if (page && page > 0) q.set('page', String(page));
    if (location.protocol === 'file:') q.set('mode', 'png');
    return `pdf-view.html?${q.toString()}`;
  }

  function setHeaderLink(page) {
    headerMojLink.href = pdfUrl(page);
    headerMojLink.setAttribute('aria-label', page ? `PDF 原文第 ${page} 頁` : 'PDF 原文');
  }

  function escapeHtml(s) {
    return normalizeText(s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function formatBody(text) {
    return escapeHtml(text).replace(/\n\n/g, '</p><p>').replace(/\n/g, '<br>');
  }

  function renderMediaBlock(media) {
    const kind = media.type === 'table' ? '表格' : '圖';
    const href = pdfUrl(media.page || 2);
    return `
      <a class="spec-media-link spec-media-link--${escapeHtml(media.type)}"
         href="${escapeHtml(href)}" target="_blank" rel="noopener"
         id="media-${escapeHtml(media.ref)}">
        <span class="spec-media-tag">${kind}</span>
        <span class="spec-media-caption">${escapeHtml(media.caption)}</span>
        <span class="spec-media-action">詳內文連結
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
            <path d="M18 13v6a2 2 0 01-2 2H5a2 2 0 01-2-2V8a2 2 0 012-2h6"/>
            <polyline points="15 3 21 3 21 9"/>
            <line x1="10" y1="14" x2="21" y2="3"/>
          </svg>
        </span>
      </a>`;
  }

  function isTableOcrParagraph(text, media) {
    if (!media.some((m) => m.type === 'table')) return false;
    if (/^表[\s\d\-A-Za-z]/.test(text)) return false;
    if (/^[\d\s]{3,}$/.test(text)) return true;
    if (/^\d+\s+\d+/.test(text)) return true;
    return false;
  }

  function renderArticleContent(item) {
    const media = item.media || [];
    if (/^表-|^圖-/.test(item.art) && media.length) {
      return media.map((m) => renderMediaBlock(m)).join('');
    }
    const shownPages = new Set();
    const parts = [];

    if (item.body) {
      item.body.split('\n\n').filter(Boolean).forEach((para) => {
        const trimmed = para.trim();
        if (isTableOcrParagraph(trimmed, media)) return;

        const matched = media.find((m) =>
          trimmed === m.caption
          || trimmed.includes(m.caption)
          || trimmed.includes('\u5982\u5716' + m.ref)
          || (trimmed.includes(m.ref) && trimmed.includes('\u5982\u5716'))
        );
        if (matched && matched.type === 'table' && trimmed === matched.caption) {
          if (!shownPages.has(matched.page)) {
            shownPages.add(matched.page);
            parts.push(renderMediaBlock(matched));
          }
          return;
        }
        parts.push(`<p>${formatBody(trimmed)}</p>`);
        if (matched && matched.type === 'figure' && !shownPages.has(matched.page)) {
          shownPages.add(matched.page);
          parts.push(renderMediaBlock(matched));
        }
      });
    }

    media.forEach((m) => {
      if (!shownPages.has(m.page)) {
        shownPages.add(m.page);
        parts.push(renderMediaBlock(m));
      }
    });

    return parts.join('');
  }

  function getCategory(chapterId) {
    return LAW.categories.find((c) => c.id === chapterId);
  }

  function getArticle(chapterId, artId) {
    const cat = getCategory(chapterId);
    return cat && cat.articles.find((a) => a.art === artId);
  }

  function chapterArticles(cat) {
    if (cat.id === 'ACCESS-appendix') {
      return cat.articles.filter((item) => {
        if (/^表-|^圖-/.test(item.art)) return true;
        if (/^APP-[1-4]$/.test(item.art)) return true;
        if (/^A\d{3}$/.test(item.art)) return true;
        if (/^A\d{3}\.\d+/.test(item.art)) return item.body.trim().length > 20;
        return false;
      });
    }
    return cat.articles.filter((item) => {
      if (!/^\d{3,4}\.\d+/.test(item.art)) return true;
      return item.body.trim().length > 20;
    });
  }

  function articleListNum(art, cat) {
    if (isAppendixCat(cat)) {
      if (/^表-|^圖-/.test(art)) {
        const m = art.match(/^(表|圖)-(.+)$/);
        return m ? `${m[1]} ${m[2]}` : art;
      }
      if (/^APP-[1-4]$/.test(art)) return art.replace('APP-', '');
    }
    return art;
  }

  function articleListLabel(item, cat) {
    if (isAppendixCat(cat)) {
      const section = appendixSectionTitle(item, cat);
      if (section) return section;
      if (/^表-|^圖-/.test(item.art)) return item.label || item.art;
    }
    return item.label;
  }

  function articleListHint(item, cat) {
    if (!isAppendixCat(cat)) return '';
    if (/^APP-[1-4]$/.test(item.art)) return '附錄章節';
    if (/^表-|^圖-/.test(item.art)) return '詳內文連結';
    return '';
  }

  function articleBtnClass(item, cat) {
    if (isAppendixCat(cat)) {
      if (/^表-/.test(item.art)) return 'art-btn art-btn--guide art-btn--table';
      if (/^圖-/.test(item.art)) return 'art-btn art-btn--guide art-btn--figure';
      if (/^APP-[1-4]$/.test(item.art)) return 'art-btn art-btn--guide';
    }
    return 'art-btn';
  }

  function articleDisplayTitle(art, item, cat) {
    const section = appendixSectionTitle(item, cat);
    if (section) return section;
    if (/^表-|^圖-/.test(art)) return item.label || art;
    return art;
  }

  function parseHash() {
    const raw = location.hash.replace(/^#\/?/, '');
    if (!raw) return [];
    return raw.split('/').map((p) => decodeURIComponent(p)).filter(Boolean);
  }

  function buildHash(parts) {
    if (!parts.length) return '#/';
    return '#/' + parts.map((p) => encodeURIComponent(p)).join('/');
  }

  function navigate(parts) {
    const hash = buildHash(parts);
    if (location.hash !== hash) {
      location.hash = hash;
    } else {
      route();
    }
  }

  function renderHome() {
    backBtn.hidden = true;
    pageTitle.textContent = '無障礙設計規範';
    pageSub.textContent = LAW.name;
    setHeaderLink(null);

    const totalArts = LAW.categories.reduce((n, c) => n + chapterArticles(c).length, 0);

    const cards = LAW.categories.map((cat, i) => {
      const count = chapterArticles(cat).length;
      return `
      <li class="fade-up" style="animation-delay:${i * 0.04}s">
        <button type="button" class="cat-card cat-card--chapter" data-chapter="${escapeHtml(cat.id)}" aria-label="${escapeHtml(cat.subtitle)}">
          <span class="cat-icon" aria-hidden="true">${cat.icon}</span>
          <h2 class="cat-title">${escapeHtml(cat.title)}</h2>
          <p class="cat-sub">${escapeHtml(cat.subtitle)}</p>
          <span class="cat-count">${count} 節</span>
        </button>
      </li>`;
    }).join('');

    app.innerHTML = `
      <section class="hero fade-up">
        <span class="hero-eyebrow">Accessibility Design Code</span>
        <h2 class="hero-title">${escapeHtml(LAW.name)}</h2>
        <p class="hero-desc">${LAW.categories.length} 章 · ${totalArts} 節 · 修正 ${escapeHtml(LAW.amended)}</p>
        <a class="hero-cta" href="${escapeHtml(LAW.pdfFile)}" target="_blank" rel="noopener">
          PDF 原文
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
        </a>
      </section>
      <p class="section-label">章節目錄</p>
      <ul class="cat-grid cat-grid--chapters">${cards}</ul>
    `;
  }

  function renderChapter(chapterId) {
    const cat = getCategory(chapterId);
    if (!cat) {
      navigate([]);
      return;
    }

    backBtn.hidden = false;
    pageTitle.textContent = cat.title;
    pageSub.textContent = LAW.shortName;
    setHeaderLink(null);

    const articles = chapterArticles(cat);

    const articlesHtml = articles.map((item, i) => {
      const listLabel = articleListLabel(item, cat);
      const listHint = articleListHint(item, cat);
      const btnClass = articleBtnClass(item, cat);
      const isMediaGuide = /^表-|^圖-/.test(item.art);
      const mediaPage = isMediaGuide ? primaryPdfPage(item) : 0;
      const inner = `
                <span class="art-num">${escapeHtml(articleListNum(item.art, cat))}</span>
                <span class="art-label">${escapeHtml(listLabel)}${listHint ? `<span class="art-note${listHint === '詳內文連結' ? ' art-note--link' : ''}">${escapeHtml(listHint)}</span>` : ''}</span>
                <svg class="art-arrow" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                  ${isMediaGuide
                    ? '<path d="M18 13v6a2 2 0 01-2 2H5a2 2 0 01-2-2V8a2 2 0 012-2h6"/><polyline points="15 3 21 3 21 9"/><line x1="10" y1="14" x2="21" y2="3"/>'
                    : '<polyline points="9 18 15 12 9 6"/>'}
                </svg>`;
      return `
        <li class="fade-up" style="animation-delay:${Math.min(i, 12) * 0.02}s">
          ${isMediaGuide
            ? `<a class="${btnClass}" href="${escapeHtml(pdfUrl(mediaPage))}" target="_blank" rel="noopener"
                 data-art="${escapeHtml(item.art)}" aria-label="${escapeHtml(listLabel)} · 詳內文連結">${inner}</a>`
            : `<button type="button" class="${btnClass}" data-art="${escapeHtml(item.art)}" aria-label="${escapeHtml(articleDisplayTitle(item.art, item, cat))}">${inner}</button>`}
        </li>`;
    }).join('');

    app.innerHTML = `
      <header class="cat-header fade-up">
        <div class="cat-header-icon" aria-hidden="true">${cat.icon}</div>
        <h2 class="cat-header-title">${escapeHtml(cat.subtitle)}</h2>
        <p class="cat-header-sub">${escapeHtml(LAW.name)}</p>
        <p class="cat-header-desc">${articles.length} 節 · App 內閱讀</p>
      </header>
      <p class="section-label">條文目錄</p>
      <ul class="art-list">${articlesHtml}</ul>
    `;
  }

  function renderArticle(chapterId, artId) {
    const cat = getCategory(chapterId);
    const item = getArticle(chapterId, artId);
    if (!item) {
      navigate(chapterId ? [chapterId] : []);
      return;
    }

    backBtn.hidden = false;
    const detailTitle = articleDisplayTitle(artId, item, cat);
    pageTitle.textContent = detailTitle;
    pageSub.textContent = cat.title;
    setHeaderLink(primaryPdfPage(item));

    app.innerHTML = `
      <article class="art-detail fade-up">
        <header class="art-detail-head">
          <p class="art-detail-breadcrumb">${escapeHtml(LAW.shortName)} · ${escapeHtml(cat.subtitle)}</p>
          <h2 class="art-detail-title">${escapeHtml(detailTitle)}</h2>
        </header>
        <div class="art-detail-body">${renderArticleContent(item)}</div>
        <a class="hero-cta art-detail-pdf" href="${escapeHtml(pdfUrl(primaryPdfPage(item)))}" target="_blank" rel="noopener">
          PDF 原文（第 ${primaryPdfPage(item)} 頁）
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
        </a>
      </article>
    `;
  }

  function route() {
    const parts = parseHash();
    if (parts.length === 0) {
      renderHome();
      return;
    }
    if (parts.length === 1) {
      renderChapter(parts[0]);
      return;
    }
    renderArticle(parts[0], parts[1]);
  }

  function goBack() {
    const parts = parseHash();
    if (parts.length >= 2) {
      navigate([parts[0]]);
    } else {
      navigate([]);
    }
  }

  app.addEventListener('click', (e) => {
    const chapterBtn = e.target.closest('[data-chapter]');
    if (chapterBtn) {
      e.preventDefault();
      navigate([chapterBtn.dataset.chapter]);
      return;
    }

    const artBtn = e.target.closest('.art-btn[data-art]');
    if (artBtn && artBtn.tagName === 'BUTTON') {
      e.preventDefault();
      const parts = parseHash();
      if (parts.length >= 1) {
        navigate([parts[0], artBtn.dataset.art]);
      }
    }
  });

  backBtn.addEventListener('click', goBack);
  window.addEventListener('hashchange', route);

  route();
})();
