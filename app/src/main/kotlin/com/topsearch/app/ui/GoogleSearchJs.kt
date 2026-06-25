package com.topsearch.app.ui

/**
 * Tập trung toàn bộ JavaScript được inject vào WebView cho Google Search.
 */
internal object GoogleSearchJs {

    /** Dump Google consent/interstitial page so selectors can be adjusted from Logcat. */
    val CONSENT_DEBUG_JS = """
(function() {
    try {
        function txt(el) {
            return ((el && (el.innerText || el.textContent || el.value || el.getAttribute('aria-label'))) || '')
                .replace(/\s+/g, ' ')
                .trim()
                .substring(0, 120);
        }
        function css(el) {
            if (!el) return '';
            var s = (el.tagName || '').toLowerCase();
            if (el.id) s += '#' + el.id;
            if (typeof el.className === 'string' && el.className.trim()) {
                s += '.' + el.className.trim().split(/\s+/).slice(0, 3).join('.');
            }
            return s;
        }
        var controls = Array.prototype.slice.call(
            document.querySelectorAll('button, input[type="submit"], input[type="button"], [role="button"], a')
        ).slice(0, 40).map(function(el) {
            return {
                tag: css(el),
                id: el.id || '',
                name: el.getAttribute('name') || '',
                jsname: el.getAttribute('jsname') || '',
                aria: el.getAttribute('aria-label') || '',
                text: txt(el),
                visible: !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length)
            };
        });
        return JSON.stringify({
            url: location.href,
            title: document.title || '',
            bodyText: txt(document.body).substring(0, 500),
            forms: Array.prototype.slice.call(document.forms).map(function(f) {
                return {
                    action: f.action || '',
                    method: f.method || '',
                    id: f.id || '',
                    controls: Array.prototype.slice.call(f.elements).slice(0, 12).map(function(e) {
                        return { tag: css(e), name: e.name || '', value: (e.value || '').substring(0, 80), text: txt(e) };
                    })
                };
            }),
            dialogs: Array.prototype.slice.call(document.querySelectorAll('[role="dialog"], dialog')).map(function(d) {
                var r = d.getBoundingClientRect();
                return { tag: css(d), text: txt(d).substring(0, 260), h: Math.round(r.height), scrollH: d.scrollHeight };
            }),
            controls: controls
        });
    } catch(e) {
        return JSON.stringify({ error: e.message, url: location.href });
    }
})()
""".trimIndent()

    /**
     * Accept Google consent when a consent page/dialog appears.
     * Returns a JSON string: {detected, clicked, reason, ...}.
     */
    val ACCEPT_CONSENT_JS = """
(function() {
    try {
        function norm(text) {
            text = (text || '').toLowerCase();
            try { text = text.normalize('NFD').replace(/[\u0300-\u036f]/g, ''); } catch(e) {}
            return text.replace(/\s+/g, ' ').trim();
        }
        function label(el) {
            return norm(
                (el && (el.innerText || el.textContent || el.value || el.getAttribute('aria-label'))) || ''
            );
        }
        function visible(el) {
            if (!el) return false;
            var r = el.getBoundingClientRect();
            var st = window.getComputedStyle(el);
            return r.width > 0 && r.height > 0 && st.visibility !== 'hidden' && st.display !== 'none';
        }
        function isConsentPage() {
            var body = norm(document.body ? document.body.innerText || document.body.textContent || '' : '');
            var hasConsentControl = !!document.querySelector('form[action*="consent"], button#L2AGLb');
            var hasConsentUrl = location.hostname.indexOf('consent.google.') >= 0 ||
                location.href.indexOf('/consent') >= 0;
            var hasConsentText = body.indexOf('before you continue') >= 0 ||
                body.indexOf('truoc khi tiep tuc') >= 0 ||
                body.indexOf('ก่อนไปที่ google') >= 0 ||
                body.indexOf('ก่อนจะไปที่ google') >= 0;
            var hasSearchBox = !!document.querySelector('textarea[name="q"], input[name="q"]');
            return hasConsentControl || hasConsentUrl || (hasConsentText && !hasSearchBox);
        }
        if (!isConsentPage()) {
            return JSON.stringify({ detected: false, clicked: false, reason: 'not-consent', url: location.href });
        }

        Array.prototype.forEach.call(document.querySelectorAll('[role="dialog"], dialog, main, body'), function(el) {
            try { el.scrollTop = el.scrollHeight; } catch(e) {}
        });
        try { window.scrollTo(0, document.documentElement.scrollHeight || document.body.scrollHeight || 0); } catch(e) {}

        function isBadChoice(t) {
            return t.indexOf('reject') >= 0 ||
                t.indexOf('decline') >= 0 ||
                t.indexOf('ปฏิเสธ') >= 0 ||
                t.indexOf('อ่านเพิ่มเติม') >= 0 ||
                t.indexOf('more') >= 0 ||
                t.indexOf('learn') >= 0;
        }
        function isAcceptChoice(t) {
            return t.indexOf('accept all') >= 0 ||
                t.indexOf('i agree') >= 0 ||
                t === 'agree' ||
                t.indexOf('ยอมรับทั้งหมด') >= 0 ||
                t.indexOf('ยอมรับ') >= 0 ||
                t.indexOf('dong y') >= 0 ||
                t.indexOf('chấp nhận tất cả') >= 0 ||
                t.indexOf('chap nhan tat ca') >= 0;
        }
        function click(el, reason) {
            if (!el || !visible(el)) return null;
            el.scrollIntoView({ block: 'center', inline: 'center' });
            el.click();
            return { detected: true, clicked: true, reason: reason, text: label(el).substring(0, 80), url: location.href };
        }

        var selectors = [
            'button#L2AGLb',
            '#L2AGLb',
            'button[jsname="higCR"]',
            'input[type="submit"][value*="Accept"]',
            'input[type="submit"][value*="agree"]'
        ];
        for (var i = 0; i < selectors.length; i++) {
            var direct = document.querySelector(selectors[i]);
            var directResult = click(direct, 'selector:' + selectors[i]);
            if (directResult) return JSON.stringify(directResult);
        }

        var controls = Array.prototype.slice.call(
            document.querySelectorAll('button, input[type="submit"], input[type="button"], [role="button"], a')
        );
        for (var j = 0; j < controls.length; j++) {
            var t = label(controls[j]);
            if (!t || isBadChoice(t)) continue;
            if (isAcceptChoice(t)) {
                var textResult = click(controls[j], 'text');
                if (textResult) return JSON.stringify(textResult);
            }
        }
        for (var k = 0; k < controls.length; k++) {
            var mt = label(controls[k]);
            if (mt.indexOf('อ่านเพิ่มเติม') >= 0 || mt.indexOf('read more') >= 0 || mt.indexOf('more') >= 0) {
                var moreResult = click(controls[k], 'expand-more');
                if (moreResult) {
                    moreResult.clicked = false;
                    return JSON.stringify(moreResult);
                }
            }
        }
        return JSON.stringify({ detected: true, clicked: false, reason: 'accept-not-found', url: location.href });
    } catch(e) {
        return JSON.stringify({ detected: false, clicked: false, reason: 'error:' + e.message, url: location.href });
    }
})()
""".trimIndent()

    /** Dump trang để biết Google đang render cái gì */
    val DEBUG_JS = """
(function() {
    // Tìm tất cả <a> dẫn ra ngoài Google — đây là link kết quả
    var links = document.querySelectorAll('a[href^="http"]');
    var extLinks = [];
    for (var i = 0; i < links.length && extLinks.length < 5; i++) {
        var a = links[i];
        if (a.href.indexOf('google.com') >= 0) continue;
        if (a.href.indexOf('googleapis.com') >= 0) continue;
        var txt = (a.innerText || a.textContent || '').trim().substring(0, 60);
        if (!txt || txt.length < 3) continue;
        // Lấy class của a và parent gần nhất
        var p = a.parentElement;
        extLinks.push({
            href:     a.href.substring(0, 60),
            text:     txt,
            aClass:   a.className.substring(0, 40),
            pTag:     p ? p.tagName : '',
            pClass:   p ? p.className.substring(0, 40) : ''
        });
    }
    // Tìm thẻ có role="heading" (Google dùng thay h3)
    var headings = document.querySelectorAll('[role="heading"]');
    var hdump = [];
    for (var j = 0; j < Math.min(headings.length, 3); j++) {
        hdump.push({
            tag:   headings[j].tagName,
            cls:   headings[j].className.substring(0, 40),
            text:  (headings[j].innerText||'').substring(0, 50)
        });
    }
    return JSON.stringify({
        bodyLen:   document.body ? document.body.innerHTML.length : 0,
        h3:        document.querySelectorAll('h3').length,
        roleHead:  headings.length,
        cite:      document.querySelectorAll('cite').length,
        extLinks:  extLinks,
        headings:  hdump
    });
})()
""".trimIndent()

    /** JS poll — chờ đến khi Google render xong ít nhất 1 kết quả */
    val WAIT_READY_JS = """
(function() {
    var h3 = document.querySelectorAll('h3');
    var heading = document.querySelectorAll('[role="heading"], .LC20lb');
    var links = document.querySelectorAll('#search a[href], #rso a[href]');
    return (h3.length + heading.length + links.length);
})()
""".trimIndent()

    /**
     * Trích xuất KẾT QUẢ ORGANIC từ Google Search — không tính ads, PAA, Knowledge Panel.
     *
     * Phương pháp chính: tìm h3 trong #rso, bỏ qua các section không phải organic.
     * PAA / KP / Ads đều bị loại qua exclusion list.
     * h3 trong PAA thường không có external link → tự bị lọc ra.
     *
     * Fallback: a.UBFage nếu h3 tìm được < 5 kết quả.
     */
    val EXTRACT_JS = """
(function() {
    try {
        var out  = [];
        var seen = {};

        var EXCLUDE = [
            '#tads', '#tadsb',
            '[data-text-ad]', '.uEierd', '.pla-unit',
            '[data-rw]',
            '.related-question-pair',
            '.kp-wholepage', '.osrp-blk', '.I6TXqe',
            '[aria-label="Ads"]',
            '[aria-label="Quảng cáo"]',
            '[aria-label="Mọi người cũng hỏi"]',
            '[aria-label="Kết quả được tài trợ"]',
            '.mnr-c',
            '.commercial-unit-desktop-top',
            '.cu-container',
            '.EyBRub', '[data-kpid]',
            'g-scrolling-carousel',
            '[data-maindata*="LOCAL_NAV"]',
            '.P6Deab',
            '[data-phone-number]',
            '[data-url*="maps.google"]'
        ];

        function isExcluded(el) {
            if (!el || !el.closest) return false;
            for (var i = 0; i < EXCLUDE.length; i++) {
                if (el.closest(EXCLUDE[i])) return true;
            }
            return false;
        }

        function norm(text) {
            text = (text || '').toLowerCase();
            try { text = text.normalize('NFD').replace(/[\u0300-\u036f]/g, ''); } catch(e) {}
            return text.replace(/[^a-z0-9]+/g, ' ').trim();
        }

        var AD_KW = ['ket qua duoc tai tro', 'nha tai tro', 'duoc tai tro',
                     'quang cao', 'sponsored'];
        function matchAd(t) {
            for (var i = 0; i < AD_KW.length; i++) if (t.indexOf(AD_KW[i]) >= 0) return true;
            return false;
        }
        function hasAdLabel(el) {
            if (!el) return false;
            var t = norm(el.innerText || el.textContent || '');
            if (t.length > 0 && t.length < 80 && matchAd(t)) return true;
            if (!el.children || el.children.length === 0) return false;
            var ft = norm(el.children[0].innerText || el.children[0].textContent || '');
            if (ft.length > 0 && ft.length < 80 && matchAd(ft)) return true;
            if (el.children.length > 1) {
                var lt = norm(el.children[el.children.length-1].innerText || el.children[el.children.length-1].textContent || '');
                if (lt.length > 0 && lt.length < 80 && matchAd(lt)) return true;
            }
            return false;
        }
        function isAd(el) {
            if (!el) return false;
            if (isExcluded(el)) return true;
            var cur = el;
            for (var i = 0; i < 15 && cur && cur !== document.body; i++) {
                if (cur.id === 'rso' || cur.id === 'search' || cur.id === 'main') break;
                if (isExcluded(cur)) return true;
                if (hasAdLabel(cur)) return true;
                cur = cur.parentElement;
            }
            return false;
        }
        function isAdUrl(h) {
            return h.indexOf('/aclk?') >= 0 || h.indexOf('googleadservices') >= 0;
        }

        function getDomain(href) {
            try { return new URL(href).hostname.replace(/^www\./, ''); } catch(e) { return ''; }
        }

        function resolveHref(href) {
            try {
                var url = new URL(href);
                if (url.hostname.indexOf('google.') >= 0) {
                    var q = url.searchParams.get('q') || url.searchParams.get('url');
                    if (q && q.indexOf('http') === 0) return q;
                }
                return href;
            } catch(e) { return href; }
        }

        function tryAdd(title, aTag) {
            if (!title || title.length < 3 || title.length > 200) return false;
            if (!aTag) return false;
            var href = resolveHref(aTag.href || '');
            if (!href || href.indexOf('http') !== 0) return false;
            if (isAdUrl(href)) return false;
            if (isAd(aTag)) return false;
            if (seen[href]) return false;
            var d = getDomain(href);
            if (!d || d.indexOf('google.') >= 0) return false;
            seen[href] = true;
            out.push({ t: title, d: d, u: href, ad: false });
            return true;
        }

        var searchRoot = document.querySelector('#rso, #search') || document.body;

        // ── Strategy 1: h3 trong search results, loại exclusion section ──────
        var h3s = searchRoot.querySelectorAll('h3');
        for (var i = 0; i < h3s.length && out.length < 20; i++) {
            var h3 = h3s[i];
            if (isAd(h3)) continue;

            var title = (h3.innerText || h3.textContent || '').trim();
            if (!title || title.length < 3 || title.length > 200) continue;

            // Tìm link: trong h3, ancestor <a>, hoặc leo lên tối đa 6 cấp tìm con cháu
            var aTag = h3.querySelector('a[href]') || h3.closest('a[href]');
            if (!aTag) {
                var par = h3.parentElement;
                for (var p = 0; p < 6 && par; p++) {
                    var c = par.querySelector('a[href^="http"]');
                    if (c && !isAd(c)) { aTag = c; break; }
                    par = par.parentElement;
                }
            }
            tryAdd(title, aTag);
        }

        // ── Strategy 2: a.UBFage (fallback nếu h3 tìm được < 5) ─────────────
        if (out.length < 5) {
            var links = document.querySelectorAll('a.UBFage');
            for (var j = 0; j < links.length && out.length < 20; j++) {
                var a = links[j];
                if (isAd(a)) continue;
                var text = (a.innerText || a.textContent || '').trim();
                if (!text) continue;
                var lines = text.split('\n')
                                .map(function(l) { return l.trim(); })
                                .filter(function(l) { return l.length > 0; });
                if (lines.length < 2) continue;
                var t2 = lines.length >= 3
                    ? lines.slice(2).join(' ').trim()
                    : lines[lines.length - 1].trim();
                tryAdd(t2, a);
            }
        }

        return JSON.stringify(out);
    } catch(e) {
        return JSON.stringify([{ t: 'ERROR:' + e.message, d: '', u: '', ad: false }]);
    }
})()
""".trimIndent()

    /**
     * Extract result theo visual order của DOM sau khi page đã scroll/render.
     * Chỉ loại card quảng cáo có label "Nhà tài trợ" hoặc link ads; các organic/card còn lại giữ đủ.
     */
    val EXTRACT_VISUAL_RESULTS_JS = """
(function() {
    try {
        var out = [];
        var seenHref = {};
        var seenCards = [];

        var EXCLUDE = [
            '#tads', '#tadsb',
            '[data-text-ad]', '.uEierd', '.pla-unit',
            '[data-rw]',
            '.related-question-pair',
            '.kp-wholepage', '.osrp-blk', '.I6TXqe',
            '[aria-label="Ads"]',
            '[aria-label="Quảng cáo"]',
            '[aria-label="Mọi người cũng hỏi"]',
            '[aria-label="Kết quả được tài trợ"]',
            '.mnr-c',
            '.commercial-unit-desktop-top',
            '.cu-container',
            '.EyBRub', '[data-kpid]',
            'g-scrolling-carousel',
            '[data-maindata*="LOCAL_NAV"]',
            '.P6Deab',
            '[data-phone-number]',
            '[data-url*="maps.google"]'
        ];

        function isExcluded(el) {
            if (!el || !el.closest) return false;
            for (var i = 0; i < EXCLUDE.length; i++) {
                if (el.closest(EXCLUDE[i])) return true;
            }
            return false;
        }

        function norm(text) {
            text = (text || '').toLowerCase();
            try { text = text.normalize('NFD').replace(/[\u0300-\u036f]/g, ''); } catch(e) {}
            return text.replace(/[^a-z0-9]+/g, ' ').trim();
        }

        var AD_KW = ['ket qua duoc tai tro', 'nha tai tro', 'duoc tai tro',
                     'quang cao', 'sponsored'];
        function matchAd(t) {
            for (var i = 0; i < AD_KW.length; i++) if (t.indexOf(AD_KW[i]) >= 0) return true;
            return false;
        }
        function hasAdLabel(el) {
            if (!el) return false;
            var t = norm(el.innerText || el.textContent || '');
            if (t.length > 0 && t.length < 80 && matchAd(t)) return true;
            if (!el.children || el.children.length === 0) return false;
            var ft = norm(el.children[0].innerText || el.children[0].textContent || '');
            if (ft.length > 0 && ft.length < 80 && matchAd(ft)) return true;
            if (el.children.length > 1) {
                var lt = norm(el.children[el.children.length-1].innerText || el.children[el.children.length-1].textContent || '');
                if (lt.length > 0 && lt.length < 80 && matchAd(lt)) return true;
            }
            return false;
        }
        function isAd(el) {
            if (!el) return false;
            if (isExcluded(el)) return true;
            var cur = el;
            for (var i = 0; i < 15 && cur && cur !== document.body; i++) {
                if (cur.id === 'rso' || cur.id === 'search' || cur.id === 'main') break;
                if (isExcluded(cur)) return true;
                if (hasAdLabel(cur)) return true;
                cur = cur.parentElement;
            }
            return false;
        }
        function isAdUrl(h) {
            return h.indexOf('/aclk?') >= 0 || h.indexOf('googleadservices') >= 0;
        }

        function getDomain(href) {
            try { return new URL(href).hostname.replace(/^www\./, ''); } catch(e) { return ''; }
        }

        function resolveHref(href) {
            try {
                var url = new URL(href);
                if (url.hostname.indexOf('google.') >= 0) {
                    var q = url.searchParams.get('q') || url.searchParams.get('url');
                    if (q && q.indexOf('http') === 0) return q;
                }
                return href;
            } catch(e) {
                return href;
            }
        }

        function findCard(el) {
            var cur = el;
            for (var i = 0; i < 9 && cur && cur !== document.body; i++) {
                if (cur.matches && cur.matches('div.g, .MjjYud, .N54PNb, [data-hveid]')) return cur;
                cur = cur.parentElement;
            }
            return el.parentElement || el;
        }

        function cleanTitle(text) {
            return (text || '')
                .replace(/\s+/g, ' ')
                .replace(/^https?:\/\/\S+\s*/i, '')
                .trim();
        }

        function titleFromCard(card, aTag) {
            var titleEl = titleElementForLink(aTag, card);
            var title = cleanTitle(titleEl ? (titleEl.innerText || titleEl.textContent || '') : '');
            if (title.length >= 3) return title;

            var lines = (aTag.innerText || aTag.textContent || '').split('\n')
                .map(function(l) { return cleanTitle(l); })
                .filter(function(l) {
                    var n = norm(l);
                    return l.length >= 3 && !matchAd(n) && n.indexOf('http') !== 0;
                });
            lines.sort(function(a, b) { return b.length - a.length; });
            return lines[0] || '';
        }

        function titleElementForLink(aTag, card) {
            var selector = 'h3, [role="heading"], .LC20lb';
            if (aTag) {
                if (aTag.matches && aTag.matches(selector)) return aTag;
                var insideLink = aTag.querySelector ? aTag.querySelector(selector) : null;
                if (insideLink) return insideLink;
            }
            if (card && card.querySelector) {
                var insideCard = card.querySelector(selector);
                if (insideCard) return insideCard;
            }
            return null;
        }

        function visualY(el) {
            try {
                var r = el.getBoundingClientRect();
                return window.pageYOffset + r.top;
            } catch(e) {
                return 99999999;
            }
        }

        function addCandidate(candidate) {
            var aTag = candidate.a;
            var card = candidate.card;
            if (!aTag || !card) return false;
            if (isAd(aTag) || isAd(card)) return false;
            if (seenCards.indexOf(card) >= 0) return false;

            var href = resolveHref(aTag.href || '');
            if (!href || href.indexOf('http') !== 0) return false;
            if (isAdUrl(href)) return false;
            if (seenHref[href]) return false;

            var domain = getDomain(href);
            if (!domain) return false;
            if (domain === 'gstatic.com' || domain === 'googleusercontent.com') return false;

            var title = candidate.title || titleFromCard(card, aTag);
            if (!title || title.length < 3 || title.length > 200) return false;

            seenHref[href] = true;
            seenCards.push(card);
            out.push({ t: title, d: domain, u: href, ad: false });
            return true;
        }

        var searchRoot = document.querySelector('#rso, #search') || document.body;
        var candidates = [];
        var links = searchRoot.querySelectorAll('a[href]');
        for (var i = 0; i < links.length; i++) {
            var a = links[i];
            if (isAd(a)) continue;
            var href = resolveHref(a.href || '');
            if (!href || href.indexOf('http') !== 0) continue;
            var domain = getDomain(href);
            if (!domain) continue;
            if (domain === 'gstatic.com' || domain === 'googleusercontent.com') continue;
            var card = findCard(a);
            candidates.push({ a: a, card: card, y: visualY(card || a) });
        }

        candidates.sort(function(a, b) { return a.y - b.y; });
        for (var j = 0; j < candidates.length && out.length < 20; j++) {
            addCandidate(candidates[j]);
        }

        return JSON.stringify(out);
    } catch(e) {
        return JSON.stringify([{ t: 'ERROR:' + e.message, d: '', u: '', ad: false }]);
    }
})()
""".trimIndent()

    /**
     * Debug JS: chạy toàn bộ filter logic trên mọi a.zReHs / a[jsname="UWckNb"] và
     * mọi link đến domain bất kỳ chứa "pitt.edu".
     * Trả về JSON string để log vào Logcat — gọi ngay sau EXTRACT_HEADINGS_IN_IMAGE_ORDER_JS.
     * Dùng để debug runtime, không gọi trong production. Logic được lưu ở README.md §9.
     */
    val DUMP_ZREHS_DEBUG_JS = """
(function() {
    try {
        var out = [];

        function anc(link) {
            var chain = []; var cur = link.parentElement; var d = 0;
            while (cur && d < 10) {
                var t = (cur.tagName || cur.nodeName || '').toLowerCase();
                var c = (cur.className || '').split(' ')[0].substring(0, 30);
                var jsc = cur.getAttribute('jscontroller') || '';
                chain.push(t + (c ? '.' + c : '') + (jsc ? '[jsc=' + jsc.substring(0,8) + ']' : ''));
                cur = cur.parentElement; d++;
            }
            return chain.join(' > ');
        }

        function diagnose(link) {
            var href  = link.getAttribute('href') || '';
            var hasH3 = !!(link.querySelector && link.querySelector('h3'));
            var cls   = (link.className || '').substring(0, 40);
            var jsn   = link.getAttribute('jsname') || '';

            var inULS  = !!(link.closest && link.closest('.ULSxyf'));
            var inIur  = !!(link.closest && link.closest('#iur, [data-iu], [data-viewer-group]'));
            var inGSec = !!(link.closest && link.closest('g-section-with-header'));
            var inLhd  = !!(link.closest && link.closest('[jscontroller="LhdR0e"], .vtSz8d'));
            var inEyB  = !!(link.closest && link.closest('.EyBRub, [data-kpid]'));
            var inScr  = !!(link.closest && link.closest('g-scrolling-carousel'));

            var imagePack = inULS || inIur;
            var kp = false;
            if (inEyB || inScr) kp = true;
            if (!kp && inGSec && !hasH3) kp = true;
            if (!kp && inLhd  && !hasH3) kp = true;

            var reason = imagePack ? ('IMG_PACK(' + (inULS?'ULSxyf':'') + (inIur?'+data-iu':'') + ')') :
                         kp        ? ('KP(' + (inEyB?'EyBRub':inScr?'carousel':inGSec?'g-section':inLhd?'LhdR0e':'?') + ')') :
                                     'PASS';

            return 'href=' + href.substring(0, 100) + '\n' +
                   '  cls=' + cls + ' jsname=' + jsn + '\n' +
                   '  hasH3=' + hasH3 + ' ULS=' + inULS + ' gSec=' + inGSec +
                   ' LhdR0e=' + inLhd + ' EyBRub=' + inEyB + '\n' +
                   '  RESULT=' + reason + '\n' +
                   '  ancestors: ' + anc(link);
        }

        // 1. Tất cả a.zReHs / a[jsname="UWckNb"]
        var organics = document.querySelectorAll('a.zReHs[href], a[jsname="UWckNb"][href]');
        out.push('=== organic direct links (a.zReHs / UWckNb): ' + organics.length + ' ===');
        for (var i = 0; i < organics.length; i++) {
            out.push('[' + i + '] ' + diagnose(organics[i]));
        }

        // 2. Tất cả link có href chứa "pitt.edu"
        var pittAll = document.querySelectorAll('a[href*="pitt.edu"]');
        out.push('=== pitt.edu links total: ' + pittAll.length + ' ===');
        for (var p = 0; p < pittAll.length; p++) {
            out.push('[pitt' + p + '] ' + diagnose(pittAll[p]));
        }

        return JSON.stringify(out.join('\n'));
    } catch(e) {
        return JSON.stringify('ZREHS_DEBUG ERROR: ' + e.message);
    }
})()
""".trimIndent()

    /** JS lấy vị trí Google đang phục vụ kết quả — trả raw text, không filter cứng */
    // Extract top theo DOM đã render: ưu tiên heading, fallback link-card, sort theo vị trí Y trên ảnh.
    val EXTRACT_HEADINGS_IN_IMAGE_ORDER_JS = """
(function() {
    try {
        var results = [];
        var seen = {};

        function norm(text) {
            text = (text || '').toLowerCase();
            try { text = text.normalize('NFD').replace(/[\u0300-\u036f]/g, ''); } catch(e) {}
            return text.replace(/[^a-z0-9]+/g, ' ').trim();
        }

        function isAdBlock(el) {
            if (!el || !el.closest) return false;
            if (el.closest('#tads')) return true;
            if (el.closest('#tadsb')) return true;
            if (el.closest('[data-text-ad]')) return true;
            if (el.closest('[aria-label="Ads"]')) return true;
            if (el.closest('[aria-label="Quảng cáo"]')) return true;
            if (el.closest('[aria-label="Kết quả được tài trợ"]')) return true;
            var card = el.closest('.uEierd, .pla-unit, .commercial-unit-desktop-top, .cu-container, [data-snc], [data-hveid]') || el;
            var nodes = card.querySelectorAll ? card.querySelectorAll('span, div') : [];
            var limit = Math.min(nodes.length, 120);
            for (var i = 0; i < limit; i++) {
                var t = norm(nodes[i].innerText || nodes[i].textContent || '');
                if (!t || t.length > 70) continue;
                if (t.indexOf('nha tai tro') >= 0) return true;
                if (t.indexOf('ket qua duoc tai tro') >= 0) return true;
                if (t === 'sponsored' || t === 'quang cao') return true;
            }
            return false;
        }

        function resolveUrl(link) {
            var rawHref = link.getAttribute('href') || link.href || '';
            if (!rawHref) return '';
            try {
                var url = new URL(rawHref, 'https://www.google.com');
                if (url.pathname === '/url' || url.href.indexOf('/url?') >= 0) {
                    return url.searchParams.get('q') || url.searchParams.get('url') || '';
                }
                return url.href;
            } catch(e) {
                return '';
            }
        }

        function isAllowedGoogleProductUrl(realUrl) {
            try {
                var u = new URL(realUrl);
                var host = u.hostname.toLowerCase();
                var path = u.pathname.toLowerCase();

                if (host.indexOf('play.google.') === 0) return true;
                if (host.indexOf('docs.google.') === 0) return true;
                if ((host === 'www.google.com' || host === 'google.com') && path.indexOf('/docs/about') >= 0) return true;
                if (host === 'workspace.google.com' && path.indexOf('/products/docs') >= 0) return true;
                if (host === 'support.google.com' && path.indexOf('/docs/') === 0) return true;

                return false;
            } catch(e) {
                return false;
            }
        }

        function allowedUrl(realUrl) {
            if (!realUrl || realUrl.indexOf('http') !== 0) return false;
            if (realUrl.indexOf('/aclk?') >= 0) return false;
            if (realUrl.indexOf('googleadservices') >= 0) return false;
            try {
                var host = new URL(realUrl).hostname.toLowerCase();
                if (host.indexOf('google.') >= 0 && !isAllowedGoogleProductUrl(realUrl)) return false;
                if (host.indexOf('gstatic.') >= 0) return false;
                if (host.indexOf('googleusercontent.') >= 0) return false;
                return true;
            } catch(e) {
                return false;
            }
        }

        function isAdUrl(href) {
            href = href || '';
            return href.indexOf('/aclk?') >= 0 || href.indexOf('googleadservices') >= 0;
        }

        function isPlayGoogleLink(link) {
            try {
                var realUrl = resolveUrl(link);
                return new URL(realUrl).hostname.indexOf('play.google.') === 0;
            } catch(e) {
                return false;
            }
        }

        function isAllowedGoogleResultLink(link) {
            try {
                var realUrl = resolveUrl(link);
                return isAllowedGoogleProductUrl(realUrl);
            } catch(e) {
                return false;
            }
        }

        function isNimoLikeLink(link) {
            try {
                var realUrl = resolveUrl(link);
                return new URL(realUrl).hostname.toLowerCase().indexOf('nimo') >= 0;
            } catch(e) {
                return false;
            }
        }

        function isYouTubeLikeUrl(realUrl) {
            try {
                var host = new URL(realUrl).hostname.toLowerCase();
                return host === 'youtu.be' || host === 'youtube.com' || host.endsWith('.youtube.com');
            } catch(e) {
                return false;
            }
        }

        function isYouTubeLikeLink(link) {
            try {
                return isYouTubeLikeUrl(resolveUrl(link));
            } catch(e) {
                return false;
            }
        }

        function isFacebookVideoUrl(realUrl) {
            try {
                var u = new URL(realUrl);
                var host = u.hostname.toLowerCase();
                var path = u.pathname.toLowerCase();
                var isFacebookHost = host === 'facebook.com' || host.endsWith('.facebook.com');
                return isFacebookHost && (path.indexOf('/videos/') >= 0 || path.indexOf('/watch') === 0);
            } catch(e) {
                return false;
            }
        }

        function isFacebookVideoLink(link) {
            try {
                return isFacebookVideoUrl(resolveUrl(link));
            } catch(e) {
                return false;
            }
        }

        function isTikTokLink(link) {
            try {
                var host = new URL(resolveUrl(link)).hostname.toLowerCase();
                return host === 'tiktok.com' || host.endsWith('.tiktok.com');
            } catch(e) {
                return false;
            }
        }

        function isImagePackResult(link) {
            if (!link || !link.closest) return false;
            return !!link.closest('.ULSxyf, #iur, [data-iu], [data-viewer-group]');
        }

        function isKnowledgePanelResult(link) {
            if (!link || !link.closest) return false;
            // Standard Knowledge Panel / non-organic selectors — always exclude.
            if (link.closest('.EyBRub, [data-kpid], [data-maindata*="LOCAL_NAV"], g-scrolling-carousel') ||
                isLocalPanelResult(link)) return true;
            // Top Stories / Tin bài hàng đầu block — jsname="Yccn4d" là ID nội bộ của Google cho section này
            if (link.closest('[jsname="Yccn4d"]')) return true;
            // App Install widget (Google gợi ý cài app) — jsname="tJHJj" container, .qs-ic card
            if (link.closest('[jsname="tJHJj"], .qs-ic')) return true;
            if (link.id && link.id.startsWith('aig-ni-')) return true;
            // g-section-with-header wraps news sections (links have NO <h3>) AND
            // sometimes wraps organic video result sections (links ALWAYS have <h3>).
            // Skip the exclusion when the link itself contains an <h3> (organic video card).
            if (link.closest('g-section-with-header')) {
                if (!link.querySelector || !link.querySelector('h3')) return true;
            }
            // [jscontroller="LhdR0e"] / .vtSz8d = Google video carousel section.
            // Exception: organic direct result cards always have <h3> inside the link;
            // carousel items only use [role="heading"] span — never <h3>.
            // Skip the carousel ancestor check when the link itself contains an h3.
            if (!link.querySelector || !link.querySelector('h3')) {
                if (link.closest('[jscontroller="LhdR0e"], .vtSz8d')) return true;
            }
            return false;
        }

        function isLocalPanelResult(el) {
        if (!el || !el.closest) return false;
        if (el.closest('.EyBRub, [data-kpid], [data-maindata*="LOCAL_NAV"]')) return true;

        // Local/business panel actions (Trang web, Goi dien, Duong di...) are real links
        // but not organic top results, so skip them without touching normal result cards.
        var localAction = el.closest('.P6Deab, [data-phone-number], [data-url*="maps.google"], a[href*="/maps/"], a[href*="maps.google."]');
        if (!localAction) return false;

        var label = norm(localAction.innerText || localAction.textContent || localAction.getAttribute('aria-label') || '');
        return label === 'trang web' ||
            label === 'goi dien' ||
            label === 'duong di' ||
            label === 'chia se' ||
            label === 'luu' ||
            !!localAction.closest('[role="dialog"], c-wiz, .MjjYud');
       }

        function isHiddenResult(el) {
            if (!el || !el.closest) return false;
            var hidden = el.closest('[hidden], [aria-hidden="true"], [style*="display:none"], [style*="display: none"], [style*="opacity:0"], [style*="opacity: 0"]');
            return !!hidden;
        }

        function cleanTitle(text) {
            return (text || '').replace(/\s+/g, ' ').trim();
        }

        function titleFromMobileLink(link, domain) {
            var titleEl = link.querySelector('h3, [role="heading"], .LC20lb, .MBeuO, .F0FGWb');
            var title = cleanTitle(titleEl ? (titleEl.innerText || titleEl.textContent || '') : '');
            if (title.length >= 3) return title;

            var lines = (link.innerText || link.textContent || '').split('\n')
                .map(function(l) { return cleanTitle(l); })
                .filter(function(l) {
                    if (!l || l.length < 3 || l.length > 200) return false;
                    var n = norm(l);
                    if (n.indexOf('http') === 0) return false;
                    if (domain && n === norm(domain)) return false;
                    if (n.indexOf('nha tai tro') >= 0) return false;
                    if (n.indexOf('ket qua duoc tai tro') >= 0) return false;
                    if (n === 'sponsored' || n === 'quang cao') return false;
                    return true;
                });
            return lines.length >= 3 ? lines.slice(2).join(' ') : (lines[1] || lines[0] || '');
        }

        function titleFromPlayGoogleLink(link, domain) {
            var card = link.closest('[data-snc], .N54PNb, .MjjYud, [data-hveid], .uIV6Ge') || link;
            var titleEl = link.querySelector('h3, [role="heading"], .LC20lb, .MBeuO, .F0FGWb') ||
                          (card.querySelector ? card.querySelector('h3, [role="heading"], .LC20lb, .MBeuO, .F0FGWb') : null);
            var title = cleanTitle(titleEl ? (titleEl.innerText || titleEl.textContent || '') : '');
            if (title.length >= 3 && norm(title) !== 'google play') return title;

            var lines = (card.innerText || card.textContent || '').split('\n')
                .map(function(l) { return cleanTitle(l); })
                .filter(function(l) {
                    if (!l || l.length < 3 || l.length > 200) return false;
                    var n = norm(l);
                    if (n.indexOf('http') === 0) return false;
                    if (domain && n === norm(domain)) return false;
                    if (n === 'google play') return false;
                    if (n.indexOf('nha tai tro') >= 0) return false;
                    if (n.indexOf('ket qua duoc tai tro') >= 0) return false;
                    if (n === 'sponsored' || n === 'quang cao') return false;
                    return true;
                });
            return lines.length >= 3 ? lines.slice(2).join(' ') : (lines[1] || lines[0] || '');
        }

        function isOrganicDirectLink(link) {
            // a.zReHs / a[jsname="UWckNb"] — classic mobile organic result link.
            // a.OcpZAb — observed 2026-06: Google replaced zReHs with OcpZAb on video result cards
            //            (e.g. chs.pitt.edu). No jsname attribute; class is the only stable signal.
            return !!(link && link.matches && link.matches(
                'a.zReHs[href], a[jsname="UWckNb"][href], a.OcpZAb[href]'
            ));
        }

        function titleFromOrganicDirectLink(link, domain) {
            var card = link.closest('[data-rpos], .MjjYud, [data-snc], .N54PNb, [data-hveid]') || link;
            var titleEl = link.querySelector('h3, [role="heading"], .LC20lb, .MBeuO, .F0FGWb') ||
                          (card.querySelector ? card.querySelector('h3, [role="heading"], .LC20lb, .MBeuO, .F0FGWb') : null);
            var title = cleanTitle(titleEl ? (titleEl.innerText || titleEl.textContent || '') : '');
            if (title.length >= 3) return title;

            var lines = (card.innerText || card.textContent || '').split('\n')
                .map(function(l) { return cleanTitle(l); })
                .filter(function(l) {
                    if (!l || l.length < 3 || l.length > 200) return false;
                    var n = norm(l);
                    if (n.indexOf('http') === 0) return false;
                    if (domain && n === norm(domain)) return false;
                    if (n.indexOf('nha tai tro') >= 0) return false;
                    if (n.indexOf('ket qua duoc tai tro') >= 0) return false;
                    if (n === 'sponsored' || n === 'quang cao') return false;
                    return true;
                });
            return lines.length >= 3 ? lines.slice(2).join(' ') : (lines[1] || lines[0] || '');
        }

        function resultOrderKey(el) {
            try {
                var rposEl = el.closest && el.closest('[data-rpos]');
                if (rposEl) {
                    var rpos = parseInt(rposEl.getAttribute('data-rpos') || '', 10);
                    if (!isNaN(rpos)) return rpos;
                }

                var card = (el.closest && el.closest('.MjjYud, [data-snc], .N54PNb, [data-hveid]')) || el;
                var rect = card.getBoundingClientRect ? card.getBoundingClientRect() : null;
                if (rect) return 10000 + Math.max(0, rect.top + (window.scrollY || window.pageYOffset || 0));
            } catch(e) {}
            return 999999999;
        }

        function getBlkTag(el) {
            try {
                if (!el) return '';
                var parts = [];
                var cur = el;
                var limit = 10;
                while (cur && limit-- > 0) {
                    var tag = (cur.tagName || '').toLowerCase();
                    var id  = cur.id ? '#' + cur.id : '';
                    var cls = typeof cur.className === 'string'
                        ? cur.className.trim().split(/\s+/).slice(0, 3).map(function(c){ return '.' + c; }).join('')
                        : '';
                    var extra = '';
                    if (cur.getAttribute) {
                        var al = cur.getAttribute('aria-label');
                        if (al) extra += '[al=' + al.replace(/"/g,'').substring(0, 25) + ']';
                        if (cur.hasAttribute('data-news-doc-id')) extra += '[news-doc]';
                        if (cur.hasAttribute('data-rpos')) extra += '[rpos=' + cur.getAttribute('data-rpos') + ']';
                        if (cur.hasAttribute('data-snc')) extra += '[snc]';
                        if (cur.hasAttribute('data-hveid')) extra += '[hveid]';
                        if (cur.hasAttribute('jsname')) extra += '[jn=' + cur.getAttribute('jsname') + ']';
                        if (cur.hasAttribute('jscontroller')) extra += '[jc=' + cur.getAttribute('jscontroller').substring(0,8) + ']';
                    }
                    var entry = tag + id + cls + extra;
                    if (entry && entry !== 'html' && entry !== 'body') parts.push(entry);
                    if (cur.id === 'rso' || cur.id === 'search' || cur.id === 'main') break;
                    cur = cur.parentElement;
                }
                return parts.join(' > ');
            } catch(e) { return 'err:' + e.message; }
        }

        function outputResults() {
            results.sort(function(a, b) {
                var ay = typeof a.y === 'number' ? a.y : 999999999;
                var by = typeof b.y === 'number' ? b.y : 999999999;
                return ay - by;
            });
            return results.map(function(r) {
                return { t: r.t, d: r.d, u: r.u, ad: r.ad, _blk: r._blk || '' };
            });
        }

        function addResult(link, requireH3) {
            if (requireH3 && !link.querySelector('h3')) return;
            if (isHiddenResult(link)) return;
            if (isImagePackResult(link)) return;

            var realUrl = resolveUrl(link);
            if (isKnowledgePanelResult(link)) return;
            if (isAdBlock(link)) return;

            if (!allowedUrl(realUrl)) return;
            if (seen[realUrl]) return;

            try {
                var u = new URL(realUrl);
                var title = requireH3
                    ? cleanTitle(link.querySelector('h3').innerText || link.querySelector('h3').textContent || '')
                    : (isPlayGoogleLink(link)
                        ? titleFromPlayGoogleLink(link, u.hostname)
                        : ((isOrganicDirectLink(link) || isNimoLikeLink(link) || isYouTubeLikeLink(link) || isFacebookVideoLink(link)) ? titleFromOrganicDirectLink(link, u.hostname) : titleFromMobileLink(link, u.hostname)));
                if (!title || title.length < 3 || title.length > 200) return;

                seen[realUrl] = true;
                results.push({ t: title, d: u.hostname, u: realUrl, y: resultOrderKey(link), ad: false, _blk: getBlkTag(link) });
            } catch(e) {}
        }

        function addYouTubeVideoBlock(block) {
            if (!block || isImagePackResult(block)) return;
            if (isHiddenResult(block)) return;
            if (isKnowledgePanelResult(block)) return;
            if (isAdBlock(block)) return;

            var realUrl = block.getAttribute('data-curl') || block.getAttribute('data-surl') || '';
            if (!isYouTubeLikeUrl(realUrl)) return;
            if (!allowedUrl(realUrl)) return;
            if (seen[realUrl]) return;

            try {
                var u = new URL(realUrl);
                // YouTube organic result card luôn có h3; carousel item chỉ có [role="heading"] → bỏ qua
                var titleEl = block.querySelector('h3, .LC20lb, .MBeuO, .F0FGWb');
                var title = cleanTitle(titleEl ? (titleEl.innerText || titleEl.textContent || '') : '');
                if (!title || title.length < 3 || title.length > 200) return;

                seen[realUrl] = true;
                results.push({ t: title, d: u.hostname, u: realUrl, y: resultOrderKey(block), ad: false, _blk: getBlkTag(block) });
            } catch(e) {}
        }

        function findAllowedLinkNearHeading(heading) {
            if (!heading) return null;

            var direct = heading.closest && heading.closest('a[href]');
            if (direct && !isAdUrl(direct.getAttribute('href') || direct.href || '') && allowedUrl(resolveUrl(direct))) return direct;

            var card = (heading.closest && heading.closest('[data-rpos], .MjjYud, [data-snc], .N54PNb, [data-hveid], .uIV6Ge')) || heading.parentElement;
            var links = card && card.querySelectorAll ? card.querySelectorAll('a[href]') : [];
            for (var i = 0; i < links.length; i++) {
                if (isAdUrl(links[i].getAttribute('href') || links[i].href || '')) continue;
                if (isHiddenResult(links[i])) continue;
                if (isImagePackResult(links[i])) continue;
                if (isKnowledgePanelResult(links[i])) continue;
                if (isAdBlock(links[i])) continue;
                if (allowedUrl(resolveUrl(links[i]))) return links[i];
            }
            return null;
        }

        function addHeadingResult(heading) {
            if (!heading) return;
            if (isHiddenResult(heading)) return;
            if (isImagePackResult(heading)) return;
            if (isKnowledgePanelResult(heading)) return;
            if (isAdBlock(heading)) return;

            var title = cleanTitle(heading.innerText || heading.textContent || '');
            if (!title || title.length < 3 || title.length > 200) return;

            var link = findAllowedLinkNearHeading(heading);
            if (!link) return;

            var realUrl = resolveUrl(link);
            if (!allowedUrl(realUrl)) return;
            if (seen[realUrl]) return;

            try {
                var u = new URL(realUrl);
                seen[realUrl] = true;
                results.push({ t: title, d: u.hostname, u: realUrl, y: resultOrderKey(heading), ad: false, _blk: getBlkTag(heading) });
            } catch(e) {}
        }


        // Primary: same idea as backend CheckRankListResult.
        var redirectLinks = document.querySelectorAll(
            'a[href^="/url?q="], a[href*="/url?q="], a[href*="google.com/url?"]'
        );
        for (var i = 0; i < redirectLinks.length && results.length < 20; i++) {
            addResult(redirectLinks[i], true);
        }
        var primaryCount = results.length;

        // Modern/mobile organic cards can use direct zReHs/UWckNb links instead of /url?q=.
        // Nimo-related domains may appear as direct media/result links, so keep them in this pass too.
        var organicDirectLinks = document.querySelectorAll('a.zReHs[href], a[jsname="UWckNb"][href], a.OcpZAb[href], a[href*="nimo"], a[href*="facebook.com/"], a[href*="tiktok.com/"]');
        for (var o = 0; o < organicDirectLinks.length && results.length < 20; o++) {
            if (!isOrganicDirectLink(organicDirectLinks[o]) &&
                !isNimoLikeLink(organicDirectLinks[o]) &&
                !isYouTubeLikeLink(organicDirectLinks[o]) &&
                !isFacebookVideoLink(organicDirectLinks[o]) &&
                !isTikTokLink(organicDirectLinks[o])) continue;
            addResult(organicDirectLinks[o], false);
        }

        // YouTube video cards can expose the canonical URL on the video block instead of a standard result anchor.
        var youtubeVideoBlocks = document.querySelectorAll('[data-curl*="youtube.com/watch"], [data-surl*="youtube.com/watch"], [data-curl*="youtu.be/"], [data-surl*="youtu.be/"]');
        for (var y = 0; y < youtubeVideoBlocks.length && results.length < 20; y++) {
            addYouTubeVideoBlock(youtubeVideoBlocks[y]);
        }

        // Some Google mobile layouts render titles as DIV[role=heading]/.F0FGWb and put the URL
        // on a nearby anchor with changing classes, so recover from the heading's own card.
        if (results.length === 0) {
            var headingCards = document.querySelectorAll('[role="heading"], .F0FGWb, .LC20lb, .MBeuO');
            for (var h = 0; h < headingCards.length && results.length < 20; h++) {
                addHeadingResult(headingCards[h]);
            }
        }

        // Direct organic cards: mobile Google may render real results as direct hrefs.
        if (primaryCount > 0) {
            var directLinks = document.querySelectorAll('a.UBFage[href], a[href].UBFage, a[href^="https://play.google."], a[href^="http://play.google."], a[href^="https://docs.google."], a[href^="http://docs.google."]');
            for (var d = 0; d < directLinks.length && results.length < 20; d++) {
                addResult(directLinks[d], false);
            }
        }

        // Mobile fallback: Google WebView often renders direct result cards as a.UBFage and h3=0.
        if (primaryCount === 0) {
            var mobileLinks = document.querySelectorAll('a.UBFage[href], a[href].UBFage, a[href^="https://play.google."], a[href^="http://play.google."], a[href^="https://docs.google."], a[href^="http://docs.google."], a[href*="nimo"]');
            for (var j = 0; j < mobileLinks.length && results.length < 20; j++) {
                addResult(mobileLinks[j], false);
            }
        }

        return JSON.stringify(outputResults());
    } catch(e) {
        return JSON.stringify([{ t: 'ERROR:' + e.message, d: '', u: '', ad: false }]);
    }
})()
""".trimIndent()

    /** Đọc thành phố Google đang phục vụ: ưu tiên location-chip → footer → local-pack header */
    val LOCATION_JS = """
(function() {
    try {
        function clean(text) {
            // Lấy phần trước dấu · hoặc - để bỏ "· Cập nhật vị trí"
            return (text || '').split(/[·\-–|]/)[0].trim();
        }

        // 1. Location chip Google hiển thị đầu trang (ưu tiên nhất — chính xác nhất)
        var chip = document.querySelector('g-location-chip, .tpmBl, .kPDuDb');
        if (chip) {
            var t1 = clean(chip.innerText || chip.textContent || '');
            if (t1.length > 1) return t1;
        }

        // 2. Footer — thường chứa "Hà Nội · Cập nhật vị trí"
        var foot = document.querySelector('#foot, #fbar, [id="foot"]');
        if (foot) {
            var t2 = clean(foot.innerText || foot.textContent || '');
            if (t2.length > 1 && t2.length < 60) return t2;
        }

        // 3. Local pack header "Kết quả tìm kiếm gần ..."
        var localHeader = document.querySelector('.yp1CPe, [data-attrid="location"]');
        if (localHeader) {
            var t3 = clean(localHeader.innerText || '');
            if (t3.length > 1) return t3;
        }

        return '';
    } catch(e) { return ''; }
})()
""".trimIndent()

    /** Extract kết quả organic trong vùng CSS Y [minCssY, maxCssY] — lọc ads, dedup href */
    fun buildExtractVisibleResultsJs(minCssY: Int, maxCssY: Int): String = """
(function() {
    try {
        var out = [];
        var seenHref = {};
        var seenCards = [];
        var minY = $minCssY;
        var maxY = $maxCssY;

        var EXCLUDE = [
            '#tads', '#tadsb',
            '[data-text-ad]', '.uEierd', '.pla-unit',
            '[data-rw]',
            '.related-question-pair',
            '.kp-wholepage', '.osrp-blk', '.I6TXqe',
            '[aria-label="Ads"]',
            '[aria-label="Quảng cáo"]',
            '[aria-label="Mọi người cũng hỏi"]',
            '[aria-label="Kết quả được tài trợ"]',
            '.mnr-c',
            '.commercial-unit-desktop-top',
            '.cu-container',
            '.EyBRub', '[data-kpid]',
            'g-scrolling-carousel',
            '[data-maindata*="LOCAL_NAV"]',
            '.P6Deab',
            '[data-phone-number]',
            '[data-url*="maps.google"]'
        ];

        function isExcluded(el) {
            if (!el || !el.closest) return false;
            for (var i = 0; i < EXCLUDE.length; i++) {
                if (el.closest(EXCLUDE[i])) return true;
            }
            return false;
        }

        function norm(text) {
            text = (text || '').toLowerCase();
            try { text = text.normalize('NFD').replace(/[\u0300-\u036f]/g, ''); } catch(e) {}
            return text.replace(/[^a-z0-9]+/g, ' ').trim();
        }

        var AD_KW = ['ket qua duoc tai tro', 'nha tai tro', 'duoc tai tro',
                     'quang cao', 'sponsored'];
        function matchAd(t) {
            for (var i = 0; i < AD_KW.length; i++) if (t.indexOf(AD_KW[i]) >= 0) return true;
            return false;
        }
        function hasAdLabel(el) {
            if (!el) return false;
            var t = norm(el.innerText || el.textContent || '');
            if (t.length > 0 && t.length < 80 && matchAd(t)) return true;
            if (!el.children || el.children.length === 0) return false;
            var ft = norm(el.children[0].innerText || el.children[0].textContent || '');
            if (ft.length > 0 && ft.length < 80 && matchAd(ft)) return true;
            if (el.children.length > 1) {
                var lt = norm(el.children[el.children.length-1].innerText || el.children[el.children.length-1].textContent || '');
                if (lt.length > 0 && lt.length < 80 && matchAd(lt)) return true;
            }
            return false;
        }
        function isAd(el) {
            if (!el) return false;
            if (isExcluded(el)) return true;
            var cur = el;
            for (var i = 0; i < 15 && cur && cur !== document.body; i++) {
                if (cur.id === 'rso' || cur.id === 'search' || cur.id === 'main') break;
                if (isExcluded(cur)) return true;
                if (hasAdLabel(cur)) return true;
                cur = cur.parentElement;
            }
            return false;
        }
        function isAdUrl(h) {
            return h.indexOf('/aclk?') >= 0 || h.indexOf('googleadservices') >= 0;
        }

        function getDomain(href) {
            try { return new URL(href).hostname.replace(/^www\./, ''); } catch(e) { return ''; }
        }

        function resolveHref(href) {
            try {
                var url = new URL(href);
                if (url.hostname.indexOf('google.') >= 0) {
                    var q = url.searchParams.get('q') || url.searchParams.get('url');
                    if (q && q.indexOf('http') === 0) return q;
                }
                return href;
            } catch(e) {
                return href;
            }
        }

        function findCard(el) {
            var cur = el;
            for (var i = 0; i < 9 && cur && cur !== document.body; i++) {
                if (cur.matches && cur.matches('div.g, .MjjYud, .N54PNb, [data-hveid]')) return cur;
                cur = cur.parentElement;
            }
            return el.parentElement || el;
        }

        function cleanTitle(text) {
            return (text || '')
                .replace(/\s+/g, ' ')
                .replace(/^https?:\/\/\S+\s*/i, '')
                .trim();
        }

        function titleFromCard(card, aTag) {
            var titleEl = titleElementForLink(aTag, card);
            var title = cleanTitle(titleEl ? (titleEl.innerText || titleEl.textContent || '') : '');
            if (title.length >= 3) return title;

            var lines = (aTag.innerText || aTag.textContent || '').split('\n')
                .map(function(l) { return cleanTitle(l); })
                .filter(function(l) {
                    var n = norm(l);
                    return l.length >= 3 && !matchAd(n) && n.indexOf('http') !== 0;
                });
            lines.sort(function(a, b) { return b.length - a.length; });
            return lines[0] || '';
        }

        function titleElementForLink(aTag, card) {
            var selector = 'h3, [role="heading"], .LC20lb';
            if (aTag) {
                if (aTag.matches && aTag.matches(selector)) return aTag;
                var insideLink = aTag.querySelector ? aTag.querySelector(selector) : null;
                if (insideLink) return insideLink;
            }
            if (card && card.querySelector) {
                var insideCard = card.querySelector(selector);
                if (insideCard) return insideCard;
            }
            return null;
        }

        function absY(el) {
            try {
                var r = el.getBoundingClientRect();
                return window.pageYOffset + r.top;
            } catch(e) {
                return 99999999;
            }
        }

        function addCandidate(candidate) {
            var aTag = candidate.a;
            var card = candidate.card;
            if (!aTag || !card) return false;
            if (isAd(aTag) || isAd(card)) return false;
            if (seenCards.indexOf(card) >= 0) return false;

            var href = resolveHref(aTag.href || '');
            if (!href || href.indexOf('http') !== 0) return false;
            if (isAdUrl(href)) return false;
            if (seenHref[href]) return false;

            var domain = getDomain(href);
            if (!domain) return false;
            if (domain === 'gstatic.com' || domain === 'googleusercontent.com') return false;

            var title = candidate.title || titleFromCard(card, aTag);
            if (!title || title.length < 3 || title.length > 200) return false;

            seenHref[href] = true;
            seenCards.push(card);
            out.push({ t: title, d: domain, u: href, y: candidate.y, ad: false });
            return true;
        }

        var searchRoot = document.querySelector('#rso, #search') || document.body;
        var candidates = [];
        var links = searchRoot.querySelectorAll('a[href]');
        for (var i = 0; i < links.length; i++) {
            var a = links[i];
            if (isAd(a)) continue;
            var href = resolveHref(a.href || '');
            if (!href || href.indexOf('http') !== 0) continue;
            var domain = getDomain(href);
            if (!domain) continue;
            if (domain === 'gstatic.com' || domain === 'googleusercontent.com') continue;
            var card = findCard(a);
            var titleEl = titleElementForLink(a, card);
            var title = cleanTitle(titleEl ? (titleEl.innerText || titleEl.textContent || '') : '');
            var y = absY(titleEl || a);
            if (y < minY || y >= maxY) continue;
            candidates.push({ a: a, card: card, y: y, title: title });
        }

        candidates.sort(function(a, b) { return a.y - b.y; });
        for (var j = 0; j < candidates.length && out.length < 20; j++) {
            addCandidate(candidates[j]);
        }

        return JSON.stringify(out);
    } catch(e) {
        return JSON.stringify([{ t: 'ERROR:' + e.message, d: '', u: '', ad: false }]);
    }
})()
""".trimIndent()

    /** Điền keyword vào ô tìm kiếm và submit form */
    fun buildSearchJs(keyword: String): String {
        val escaped = keyword.replace("\\", "\\\\").replace("'", "\\'")
        return """
    (function() {
        var q = document.querySelector('textarea[name="q"]') || document.querySelector('input[name="q"]');
        if (!q) return false;
        q.value = '$escaped';
        var form = q.form || q.closest('form');
        if (form) { form.submit(); return true; }
        return false;
    })()
    """.trimIndent()
    }

    /** Submit reCAPTCHA token: điền textarea → gọi data-callback → walk grecaptcha_cfg → submit form */
    fun buildCaptchaSubmitJs(token: String): String {
        val escaped = token.replace("'", "\\'")
        return """
    (function(token) {
        // 1. Điền vào textarea response
        document.querySelectorAll('textarea[name="g-recaptcha-response"]').forEach(function(el) {
            el.value = token;
        });
        var r = document.getElementById('g-recaptcha-response');
        if (r) r.value = token;

        // 2. Gọi data-callback trực tiếp (Google sorry dùng "cr")
        var widget = document.querySelector('[data-callback]');
        if (widget) {
            var fnName = widget.getAttribute('data-callback');
            if (fnName && typeof window[fnName] === 'function') {
                window[fnName](token);
                return 'called:' + fnName;
            }
        }

        // 3. Fallback: walk ___grecaptcha_cfg
        try {
            var cfg = window.___grecaptcha_cfg;
            if (cfg && cfg.clients) {
                (function walk(obj, depth) {
                    if (!obj || typeof obj !== 'object' || depth > 6) return;
                    if (typeof obj.callback === 'function') { obj.callback(token); return; }
                    Object.keys(obj).forEach(function(k) { walk(obj[k], depth + 1); });
                })(cfg.clients, 0);
            }
        } catch(e) {}

        // 4. Submit form
        var form = document.querySelector('form#captcha-form') || document.querySelector('form');
        if (form) {
            setTimeout(function() { form.submit(); }, 300);
            return 'submitted';
        }
        return 'no-form';
    })('$escaped')
    """.trimIndent()
    }

    /** Override navigator.geolocation bằng vị trí giả (lat, lng) — inject cả onPageStarted lẫn onPageFinished */
    fun buildSpoofLocationJs(lat: Double, lng: Double) = """
(function() {
    var _lat = $lat, _lng = $lng;
    var fakePos = {
        coords: {
            latitude:         _lat,
            longitude:        _lng,
            accuracy:         10,
            altitude:         null,
            altitudeAccuracy: null,
            heading:          null,
            speed:            null
        },
        timestamp: Date.now()
    };
    navigator.geolocation.getCurrentPosition = function(success, error, opts) {
        setTimeout(function() { success(fakePos); }, 0);
    };
    navigator.geolocation.watchPosition = function(success, error, opts) {
        setTimeout(function() { success(fakePos); }, 0);
        return 1;
    };
    navigator.geolocation.clearWatch = function() {};
})();
""".trimIndent()
}
