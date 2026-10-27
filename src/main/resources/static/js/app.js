/* ==========================================================================
   通用交互：API 调用（JWT）、Toast、弹窗、动作按钮、移动端导航、打赏
   纯原生 JS，不依赖任何框架，全部本地资源。
   后端统一返回 Result：{ code:200, message, data }
   ========================================================================== */
(function () {
  'use strict';

  var TOKEN_KEY = 'jargus_token';

  /* 布局里把解析好的 i18n 文案挂在这里 */
  var messages = window.JargusMessages || {};

  function t(key) {
    if (!key) return '';
    return messages[key] || key;
  }

  function getToken() {
    try { return localStorage.getItem(TOKEN_KEY) || ''; } catch (e) { return ''; }
  }
  function clearToken() {
    try { localStorage.removeItem(TOKEN_KEY); } catch (e) { /* ignore */ }
  }

  /* ─── 小工具 ────────────────────────────────────────────── */

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function isLoginPage() {
    return location.pathname === '/login';
  }

  /** 401：会话失效，回登录页并带上回跳地址（登录页本身不再跳，避免循环） */
  function redirectToLogin() {
    if (isLoginPage()) return;
    var here = location.pathname + location.search;
    location.href = '/login?redirect=' + encodeURIComponent(here);
  }

  /* ─── Toast ─────────────────────────────────────────────── */

  var ICONS = {
    ok: 'check-circle-fill',
    success: 'check-circle-fill',
    danger: 'x-circle-fill',
    error: 'x-circle-fill',
    warn: 'exclamation-triangle-fill',
    warning: 'exclamation-triangle-fill',
    info: 'info-circle-fill'
  };

  /** 构造引用内联 SVG 精灵表的图标（精灵表随页面内联，<use> 不产生额外请求） */
  function icon(name, cls) {
    var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('class', 'ico' + (cls ? ' ' + cls : ''));
    var use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
    use.setAttribute('href', '#' + name);
    svg.appendChild(use);
    return svg;
  }

  function iconHtml(name, cls) {
    return '<svg class="ico' + (cls ? ' ' + cls : '') + '"><use href="#' + name + '"/></svg>';
  }

  function toast(message, kind) {
    var box = document.getElementById('toasts');
    if (!box) return;

    kind = kind || 'info';
    var el = document.createElement('div');
    el.className = 'toast toast-' + kind;

    el.appendChild(icon(ICONS[kind] || ICONS.info));
    var body = document.createElement('div');
    body.className = 'toast-msg';
    body.textContent = message;           // textContent：不把服务端消息当 HTML 执行
    var close = document.createElement('button');
    close.type = 'button';
    close.className = 'alert-close';
    close.innerHTML = iconHtml('x-lg');

    el.appendChild(body);
    el.appendChild(close);
    box.appendChild(el);

    var timer = setTimeout(dismiss, kind === 'danger' ? 8000 : 4000);
    close.addEventListener('click', function () {
      clearTimeout(timer);
      dismiss();
    });

    function dismiss() {
      el.classList.add('out');
      setTimeout(function () { el.remove(); }, 220); // 等退场动画结束再移除
    }
  }

  /* ─── API 调用 ──────────────────────────────────────────── */

  /**
   * 统一 fetch：
   * - 自动带 JWT（localStorage 里的 Bearer；同源时 httpOnly cookie 也会带上）
   * - 解析 Result 信封：code===200  resolve(data)，否则 reject(Error(message))
   * - 401 回登录页
   * body 为对象时按 JSON 发送；FormData 直接透传（multipart）
   */
  function request(method, url, body) {
    var headers = { 'Accept': 'application/json' };
    var token = getToken();
    if (token) headers['Authorization'] = 'Bearer ' + token;

    var payload = null;
    if (body !== undefined && body !== null) {
      if (body instanceof FormData) {
        payload = body; // 浏览器自己加 Content-Type（含 boundary）
      } else {
        headers['Content-Type'] = 'application/json';
        payload = JSON.stringify(body);
      }
    }

    return fetch(url, {
      method: method,
      headers: headers,
      body: payload,
      credentials: 'same-origin'
    }).then(function (response) {
      if (response.status === 401) {
        redirectToLogin();
        throw new Error(t('error.sessionExpired'));
      }
      return response.json().catch(function () {
        throw new Error('HTTP ' + response.status);
      }).then(function (json) {
        if (json && (json.code === 200 || json.code === 0 || json.success === true)) {
          return json.data !== undefined ? json.data : json;
        }
        var msg = (json && json.message) ? json.message : ('HTTP ' + response.status);
        var err = new Error(msg);
        err.payload = json;
        throw err;
      });
    });
  }

  var api = {
    get: function (url) { return request('GET', url); },
    post: function (url, body) { return request('POST', url, body || {}); },
    put: function (url, body) { return request('PUT', url, body || {}); },
    del: function (url) { return request('DELETE', url); },
    upload: function (url, formData) { return request('POST', url, formData); }
  };

  /**
   * 通用动作按钮：data-api=URL  data-method=POST|PUT|DELETE  data-confirm=...
   * data-body='JSON 字符串'（可省略）  data-reload="true"  data-busy-text=...
   * data-toggle-class / data-toggle-text 用于「启用/停用」类按钮即时换态
   */
  function bindActionButton(btn) {
    btn.addEventListener('click', function (e) {
      // 开关（checkbox）依赖浏览器原生翻转做即时反馈：不阻止默认行为，
      // 请求失败时再翻回去，避免"提示成功但状态不刷新就不变"的错觉
      var isSwitch = btn.tagName === 'INPUT' && btn.type === 'checkbox';
      if (!isSwitch) e.preventDefault();

      var runAction = function () {
        var original = btn.innerHTML;
        btn.disabled = true;
        if (!isSwitch) {
          btn.innerHTML = '<span class="spin"></span>' +
            (btn.dataset.busyText ? ' ' + btn.dataset.busyText : '');
        }

        var body;
        if (btn.dataset.body) {
          try { body = JSON.parse(btn.dataset.body); } catch (err) { body = {}; }
        }
        var promise = request(btn.dataset.method || 'POST', btn.dataset.api, body);

        promise.then(function () {
          toast(btn.dataset.okText || t('common.done'), 'ok');
          if (btn.dataset.reload === 'true') {
            setTimeout(function () { window.location.reload(); }, 700);
            return;
          }
          if (btn.dataset.redirect) {
            setTimeout(function () { window.location.href = btn.dataset.redirect; }, 700);
            return;
          }
          btn.disabled = false;
          if (!isSwitch) btn.innerHTML = original;
        }).catch(function (err) {
          // 开关翻回原状态，让界面与服务器保持一致
          if (isSwitch) btn.checked = !btn.checked;
          toast(String(err && err.message || err), 'danger');
          btn.disabled = false;
          if (!isSwitch) btn.innerHTML = original;
        });
      };

      // 统一走美化确认弹窗；data-confirm 现均为删除/重置类破坏性操作，确认按钮用危险色
      if (btn.dataset.confirm) {
        confirmDialog(btn.dataset.confirm, { danger: true }).then(function (ok) {
          if (!ok) {
            if (isSwitch) btn.checked = !btn.checked;
            return;
          }
          runAction();
        });
        return;
      }
      runAction();
    });
  }

  /* ─── 通用弹窗（模态） ──────────────────────────────────── */

  function openModal(id) {
    var modal = document.getElementById(id);
    if (!modal) return;
    modal.classList.add('open');
    modal.setAttribute('aria-hidden', 'false');
    document.body.style.overflow = 'hidden';
    var autofocus = modal.querySelector('[autofocus], input:not([type=hidden]), select, textarea');
    if (autofocus) setTimeout(function () { autofocus.focus(); }, 50);
  }

  function closeModal(id) {
    var modal;
    if (id) {
      modal = document.getElementById(id);
    } else {
      // 未指定时关闭最上层（DOM 中最后一个）打开的弹窗，支持弹窗叠弹窗
      var opens = document.querySelectorAll('.modal.open');
      modal = opens.length ? opens[opens.length - 1] : null;
    }
    if (!modal) return;
    modal.classList.remove('open');
    modal.setAttribute('aria-hidden', 'true');
    document.body.style.overflow = document.querySelector('.modal.open') ? 'hidden' : '';
  }

  function bindModals() {
    document.querySelectorAll('[data-open-modal]').forEach(function (btn) {
      btn.addEventListener('click', function (e) {
        e.preventDefault();
        openModal(btn.dataset.openModal);
      });
    });
    document.querySelectorAll('[data-close-modal]').forEach(function (el) {
      el.addEventListener('click', function (e) {
        e.preventDefault();
        closeModal(el.dataset.closeModal || undefined);
      });
    });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') closeModal();
    });
    // 点遮罩空白处关闭
    document.querySelectorAll('.modal').forEach(function (modal) {
      modal.addEventListener('click', function (e) {
        if (e.target === modal) closeModal(modal.id);
      });
    });
  }

  /* ─── JSON 表单：收集后发请求，关闭弹窗/刷新/跳转 ─────────── */

  /**
   * form 上声明：data-api（可含 {id} 占位） data-method
   * 成功后：data-reload / data-close / data-redirect
   */
  function bindJsonForm(form) {
    form.addEventListener('submit', function (e) {
      e.preventDefault();

      var runSubmit = function () {
        var submitBtn = form.querySelector('[type=submit]');
        var original = submitBtn ? submitBtn.innerHTML : '';
        if (submitBtn) {
          submitBtn.disabled = true;
          submitBtn.innerHTML = '<span class="spin"></span> ' + (form.dataset.busyText || '');
        }

        var isMultipart = form.enctype === 'multipart/form-data';
        var body;
        if (isMultipart) {
          body = new FormData(form);
        } else {
          body = {};
          Array.prototype.forEach.call(form.elements, function (el) {
            if (!el.name || el.disabled) return;
            if (el.type === 'checkbox') {
              body[el.name] = el.checked;
            } else if (el.type === 'number') {
              body[el.name] = el.value === '' ? null : Number(el.value);
            } else {
              body[el.name] = el.value;
            }
          });
        }

        var url = form.dataset.api;
        request(form.dataset.method || 'POST', url, body)
          .then(function (data) {
            toast(form.dataset.okText || t('common.saved'), 'ok');
            if (form.dataset.redirect) {
              var target = form.dataset.redirect;
              if (data && typeof data === 'object' && data.id != null) {
                target = target.replace('{id}', encodeURIComponent(data.id));
              }
              setTimeout(function () { window.location.href = target; }, 700);
              return;
            }
            if (form.dataset.close) closeModal(form.dataset.close);
            if (form.dataset.reload === 'true') {
              setTimeout(function () { window.location.reload(); }, 700);
              return;
            }
            if (!form.dataset.keepOpen) form.reset();
            if (submitBtn) { submitBtn.disabled = false; submitBtn.innerHTML = original; }
          })
          .catch(function (err) {
            toast(String(err && err.message || err), 'danger');
            if (submitBtn) { submitBtn.disabled = false; submitBtn.innerHTML = original; }
          });
      };

      if (form.dataset.confirm) {
        confirmDialog(form.dataset.confirm, { danger: true })
          .then(function (ok) { if (ok) runSubmit(); });
        return;
      }
      runSubmit();
    });
  }

  /* ─── 移动端导航抽屉 ────────────────────────────────────── */

  function bindNavToggle(btn) {
    var links = document.getElementById('nav-links');
    if (!links) return;
    btn.addEventListener('click', function (e) {
      e.stopPropagation();
      var open = links.classList.toggle('open');
      btn.innerHTML = open ? iconHtml('x-lg') : iconHtml('list');
    });
    document.addEventListener('click', function (e) {
      if (!links.classList.contains('open')) return;
      if (!links.contains(e.target) && !btn.contains(e.target)) {
        links.classList.remove('open');
        btn.innerHTML = iconHtml('list');
      }
    });
  }

  /* ─── 顶部导航下拉分组（桌面悬停 + 点击；移动端手风琴） ─── */

  function closeNavGroups(except) {
    document.querySelectorAll('.nav-group.open').forEach(function (g) {
      if (g !== except) {
        g.classList.remove('open');
        var t = g.querySelector('.nav-trigger');
        if (t) t.setAttribute('aria-expanded', 'false');
      }
    });
  }

  function bindNavGroups() {
    var groups = document.querySelectorAll('.nav-group');
    if (!groups.length) return;
    groups.forEach(function (group) {
      var trigger = group.querySelector('.nav-trigger');
      if (!trigger) return;
      trigger.addEventListener('click', function (e) {
        e.stopPropagation();
        var willOpen = !group.classList.contains('open');
        closeNavGroups(group);
        group.classList.toggle('open', willOpen);
        trigger.setAttribute('aria-expanded', String(willOpen));
      });
      // 桌面鼠标移出后清掉点击留下的展开态，避免再次悬停状态错乱
      group.addEventListener('mouseleave', function () {
        group.classList.remove('open');
        trigger.setAttribute('aria-expanded', 'false');
      });
      // 选中菜单项（打开弹窗/跳转）后收起下拉
      group.querySelectorAll('.menu-item').forEach(function (item) {
        item.addEventListener('click', function () {
          closeNavGroups();
        });
      });
    });
    // 点击菜单外 / Esc 收起
    document.addEventListener('click', function (e) {
      if (!e.target.closest || !e.target.closest('.nav-group')) closeNavGroups();
    });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') closeNavGroups();
    });
  }

  /* ─── 密码框显示/隐藏 ───────────────────────────────────── */

  /** 只切 type，不把明文写进任何变量或 DOM 文本节点 */
  function bindPasswordToggle(btn) {
    var input = document.getElementById(btn.dataset.target);
    if (!input) return;
    btn.addEventListener('click', function () {
      var shown = input.type === 'text';
      input.type = shown ? 'password' : 'text';
      btn.classList.toggle('active', !shown);
      input.focus();
    });
  }

  /* ─── 默认口令横幅 ──────────────────────────────────────── */

  /**
   * 关掉「仍在用默认口令」的横幅。记 sessionStorage：本次会话有效，
   * 浏览器关了下次继续提示；口令真的改了之后服务端不再渲染这条横幅。
   */
  function bindDefaultPwDismiss(btn) {
    btn.addEventListener('click', function () {
      var banner = btn.closest('.default-pw-banner');
      if (banner) banner.remove();
      try {
        sessionStorage.setItem('jargus-pw-banner-dismissed', '1');
      } catch (e) { /* 隐私模式：本次点击仍生效 */ }
    });
  }

  /* ─── 打赏弹窗 ──────────────────────────────────────────── */

  /** 图片文件名与 data-pay 同名（wechat/alipay/qq），文案在 data-hint 上 */
  function bindDonate() {
    var modal = document.getElementById('donate-modal');
    if (!modal) return;
    var assets = modal.dataset.assets || '/assets/';

    function open() {
      modal.classList.add('open');
      modal.setAttribute('aria-hidden', 'false');
      document.body.style.overflow = 'hidden';
    }
    function close() {
      modal.classList.remove('open');
      modal.setAttribute('aria-hidden', 'true');
      document.body.style.overflow = '';
    }

    document.querySelectorAll('[data-role="open-donate"]').forEach(function (btn) {
      btn.addEventListener('click', open);
    });
    document.querySelectorAll('[data-role="close-donate"]').forEach(function (el) {
      el.addEventListener('click', close);
    });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && modal.classList.contains('open')) close();
    });

    var tabs = modal.querySelector('[data-role="pay-tabs"]');
    if (!tabs) return;
    var qr = document.getElementById('donate-qr');
    var tip = document.getElementById('donate-tip');

    tabs.addEventListener('click', function (e) {
      var btn = e.target.closest('.pay-tab');
      if (!btn) return;
      if (qr) {
        qr.src = assets + btn.dataset.pay + '.webp';
        qr.alt = btn.textContent.trim();
      }
      if (tip && btn.dataset.hint) tip.textContent = btn.dataset.hint;
      tabs.querySelectorAll('.pay-tab').forEach(function (b) {
        b.classList.toggle('active', b === btn);
      });
    });
  }

  /* ─── 检查更新：导航圆点 + 安装包重发提示 ─────────────── */

  var NAV_UPDATE_CACHE_KEY = 'jargus-nav-update-check';

  function readNavUpdateCache() {
    try {
      var raw = sessionStorage.getItem(NAV_UPDATE_CACHE_KEY);
      return raw ? JSON.parse(raw) : null;
    } catch (e) {
      return null;
    }
  }

  function writeNavUpdateCache(payload) {
    try {
      sessionStorage.setItem(NAV_UPDATE_CACHE_KEY, JSON.stringify({
        updateAvailable: !!payload.updateAvailable,
        artifactUpdated: !!payload.artifactUpdated,
        latestVersion: payload.latestVersion || ''
      }));
    } catch (e) { /* 隐私模式：本次仍渲染 */ }
  }

  /** 圆点只放「!」，完整说明放在 title/aria-label，避免撑破 36px 齿轮按钮 */
  function showNavUpdateBadge(badge, payload) {
    if (!badge || !payload) return;
    if (!payload.updateAvailable && !payload.artifactUpdated) {
      badge.hidden = true;
      return;
    }
    var label = payload.artifactUpdated
      ? (badge.dataset.labelArtifact || badge.dataset.label)
      : badge.dataset.label;
    if (!label) label = '!';
    if (payload.latestVersion) label += ' v' + payload.latestVersion;
    badge.textContent = '!';
    badge.title = label;
    badge.setAttribute('aria-label', label);
    badge.hidden = false;

    var link = badge.closest('a');
    if (link && link.getAttribute('href')) {
      link.setAttribute('href', link.getAttribute('href').split('#')[0] + '#version-info');
    }
  }

  /** 会话内复用缓存；设置页（data-refresh=true）每次强制刷新一次 */
  function bindNavUpdateBadge() {
    var badge = document.getElementById('nav-update-badge');
    if (!badge) return;
    var cached = readNavUpdateCache();
    if (cached) showNavUpdateBadge(badge, cached);
    if (cached && badge.dataset.refresh !== 'true') return;

    api.get(badge.dataset.url).then(function (payload) {
      if (!payload) return;
      writeNavUpdateCache(payload);
      showNavUpdateBadge(badge, payload);
    }).catch(function () { /* 离线/限流：保留缓存状态，不打扰用户 */ });
  }

  function bindArtifactDismiss() {
    var btn = document.getElementById('artifact-dismiss-btn');
    if (!btn) return;
    btn.addEventListener('click', function () {
      if (btn.disabled) return;
      btn.disabled = true;
      api.post(btn.dataset.url).then(function (res) {
        if (res && res.dismissed) {
          try { sessionStorage.removeItem(NAV_UPDATE_CACHE_KEY); } catch (e) { /* ignore */ }
          location.reload();
          return;
        }
        btn.disabled = false;
      }).catch(function () {
        btn.disabled = false;
        toast(t('settings.unknown'), 'error');
      });
    });
  }

  /* ─── 初始化 ────────────────────────────────────────────── */

  document.addEventListener('DOMContentLoaded', function () {
    document.querySelectorAll('[data-api-role="action"]').forEach(bindActionButton);
    document.querySelectorAll('form[data-api]').forEach(bindJsonForm);
    document.querySelectorAll('[data-role="nav-toggle"]').forEach(bindNavToggle);
    bindNavGroups();
    document.querySelectorAll('[data-role="toggle-password"]').forEach(bindPasswordToggle);
    document.querySelectorAll('[data-role="dismiss-default-pw"]').forEach(bindDefaultPwDismiss);
    bindModals();
    bindDonate();
    bindNavUpdateBadge();
    bindArtifactDismiss();

    // 关闭提示条
    document.querySelectorAll('[data-dismiss]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var alert = btn.closest('.alert');
        if (alert) alert.remove();
      });
    });
    // 成功提示自动消失；错误提示留着让用户看清
    document.querySelectorAll('.alert-ok[data-auto-dismiss]').forEach(function (alert) {
      setTimeout(function () { alert.remove(); }, 5000);
    });
  });

  /* ─── 带搜索的下拉多选（收件人选择） ────────────────────────
     mount 为挂载容器；opts.placeholder 覆盖占位文案，opts.title 覆盖弹窗标题（缺省同占位）；
     opts.onChange(csv) 在确认 / chip 删除 / set 后回调，供调用方做归一化（如「全选=默认」收起）。
     数据项 {id, name, email}；契约：setOptions 先于 set 调用。
     返回 { get(): 'id,id,...', set(csv), setOptions(list) }。
     chip 最多显示 2 个，超出用「+N」省略（title 列出其余），盒高固定单行不撑大。 */
  var msSeq = 0;

  /* 可搜索多选：点击输入框弹出弹窗搜索勾选，确认后回填 chip 栏。
     弹窗挂到 body 下，不受表单/模态等祖先容器 overflow 与堆叠上下文裁切。 */
  function multiSelect(mount, opts) {
    opts = opts || {};
    var items = [];        // 全量选项
    var selected = [];     // 已确认选中的 id（字符串）
    var tmp = [];          // 弹窗内暂存勾选（确认才回填，取消即丢弃）
    var placeholder = opts.placeholder || t('mail.ms.placeholder');
    var title = opts.title || placeholder;
    var MAX_CHIPS = 2;
    msSeq += 1;
    var modalId = 'ms-modal-' + msSeq;

    mount.classList.add('ms');
    mount.innerHTML =
      '<div class="ms-box" tabindex="0" role="button" aria-haspopup="dialog">' +
        '<span class="ms-chips"></span>' +
        '<span class="ms-caret">' + iconHtml('chevron-down') + '</span>' +
      '</div>';

    var modal = document.createElement('div');
    modal.className = 'modal';
    modal.id = modalId;
    modal.setAttribute('aria-hidden', 'true');
    modal.innerHTML =
      '<div class="modal-mask"></div>' +
      '<div class="modal-dialog">' +
        '<button type="button" class="modal-close">' + iconHtml('x-lg') + '</button>' +
        '<div class="modal-head">' +
          '<span class="sec-tag">' + esc(title) + '</span>' +
          '<h3>' + esc(title) + '</h3>' +
        '</div>' +
        '<div class="ms-modal-body">' +
          '<input type="text" class="input ms-search" autocomplete="off">' +
          '<label class="ms-row ms-all"><input type="checkbox"><span></span></label>' +
          '<div class="ms-list" role="listbox"></div>' +
        '</div>' +
        '<div class="form-actions">' +
          '<button type="button" class="btn btn-ghost ms-cancel">' + esc(t('common.cancel')) + '</button>' +
          '<button type="button" class="btn btn-primary ms-ok">' + esc(t('common.confirm')) + '</button>' +
        '</div>' +
      '</div>';
    document.body.appendChild(modal);

    var box = mount.querySelector('.ms-box');
    var chips = mount.querySelector('.ms-chips');
    var search = modal.querySelector('.ms-search');
    var allRow = modal.querySelector('.ms-all');
    var allBox = allRow.querySelector('input');
    var allText = allRow.querySelector('span');
    var list = modal.querySelector('.ms-list');
    search.placeholder = t('mail.ms.search');
    allText.textContent = t('mail.ms.selectAll');

    function byId(id) {
      for (var i = 0; i < items.length; i++) {
        if (String(items[i].id) === String(id)) return items[i];
      }
      return null;
    }

    function isPicked(id) { return tmp.indexOf(String(id)) >= 0; }

    function isOpen() { return modal.classList.contains('open'); }

    function renderChips() {
      var names = [];
      for (var i = 0; i < selected.length; i++) {
        var it = byId(selected[i]);
        if (it) names.push(it.name || it.email);
      }
      var html = '';
      if (!names.length) {
        html = '<span class="ms-ph">' + esc(placeholder) + '</span>';
      } else {
        var shown = names.slice(0, MAX_CHIPS);
        for (var j = 0; j < shown.length; j++) {
          html += '<span class="ms-chip" data-id="' + esc(selected[j]) + '">' + esc(shown[j]) +
                  '<span class="ms-x">&times;</span></span>';
        }
        if (names.length > MAX_CHIPS) {
          html += '<span class="ms-chip ms-chip-more" title="' + esc(names.slice(MAX_CHIPS).join(', ')) +
                  '">+' + (names.length - MAX_CHIPS) + '</span>';
        }
      }
      chips.innerHTML = html;
    }

    function filtered() {
      var kw = search.value.trim().toLowerCase();
      if (!kw) return items.slice();
      return items.filter(function (it) {
        return String(it.name || '').toLowerCase().indexOf(kw) >= 0 ||
               String(it.email || '').toLowerCase().indexOf(kw) >= 0;
      });
    }

    function renderList() {
      var rows = filtered();
      var html = '';
      for (var i = 0; i < rows.length; i++) {
        var it = rows[i];
        html += '<label class="ms-row" data-id="' + esc(it.id) + '">' +
                '<input type="checkbox"' + (isPicked(it.id) ? ' checked' : '') + '>' +
                '<span class="ms-name">' + esc(it.name || '') + '</span>' +
                '<span class="ms-mail">' + esc(it.email || '') + '</span>' +
                '</label>';
      }
      if (!rows.length) {
        html = '<div class="ms-empty">' + esc(t('mail.ms.empty')) + '</div>';
      }
      list.innerHTML = html;
      // 全选状态 = 过滤后集合是否已全部选中
      allBox.checked = rows.length > 0 && rows.every(function (it) { return isPicked(it.id); });
      allRow.hidden = rows.length === 0;
    }

    function open() {
      tmp = selected.slice();
      search.value = '';
      renderList();
      openModal(modalId); // 自动聚焦落到搜索框
    }

    function close() { closeModal(modalId); }

    box.addEventListener('click', function (e) {
      if (e.target.closest('.ms-x')) return; // chip 删除单独处理
      open();
    });
    box.addEventListener('keydown', function (e) {
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(); }
    });

    chips.addEventListener('click', function (e) {
      var x = e.target.closest('.ms-x');
      if (!x) return;
      e.stopPropagation();
      var id = String(x.parentElement.getAttribute('data-id'));
      selected = selected.filter(function (s) { return s !== id; });
      renderChips();
      if (opts.onChange) opts.onChange(selected.join(','));
    });

    modal.querySelector('.modal-mask').addEventListener('click', close);
    modal.querySelector('.modal-close').addEventListener('click', close);
    modal.querySelector('.ms-cancel').addEventListener('click', close);
    modal.querySelector('.ms-ok').addEventListener('click', function () {
      selected = tmp.slice();
      renderChips();
      close();
      if (opts.onChange) opts.onChange(selected.join(','));
    });

    search.addEventListener('input', renderList);

    list.addEventListener('change', function (e) {
      var row = e.target.closest('.ms-row');
      if (!row) return;
      var id = String(row.getAttribute('data-id'));
      if (e.target.checked) {
        if (!isPicked(id)) tmp.push(id);
      } else {
        tmp = tmp.filter(function (s) { return s !== id; });
      }
      allBox.checked = filtered().length > 0 &&
        filtered().every(function (it) { return isPicked(it.id); });
    });

    // 全选：作用于过滤后的集合（勾=补齐，取消=只移除过滤集内的）
    allBox.addEventListener('change', function () {
      var rows = filtered();
      if (allBox.checked) {
        rows.forEach(function (it) {
          if (!isPicked(it.id)) tmp.push(String(it.id));
        });
      } else {
        var ids = rows.map(function (it) { return String(it.id); });
        tmp = tmp.filter(function (s) { return ids.indexOf(s) < 0; });
      }
      renderList();
    });

    renderChips();

    return {
      get: function () { return selected.join(','); },
      set: function (csv) {
        selected = [];
        if (csv) {
          String(csv).split(',').forEach(function (s) {
            s = s.trim();
            if (s && selected.indexOf(s) < 0) selected.push(s);
          });
        }
        renderChips();
        if (isOpen()) renderList();
        if (opts.onChange) opts.onChange(selected.join(','));
      },
      setOptions: function (list_) {
        items = (list_ || []).map(function (it) {
          return { id: it.id, name: it.name || '', email: it.email || '' };
        });
        renderChips();
        if (isOpen()) renderList();
      }
    };
  }

  /* ─── 通用美化确认弹窗（替代原生 window.confirm） ───────────── */

  var CONFIRM_MODAL_ID = 'ui-confirm-modal';
  var confirmState = null; // { resolve, settled } 当前挂起的确认，单实例（与原生 confirm 语义一致）

  function buildConfirmModal() {
    var existing = document.getElementById(CONFIRM_MODAL_ID);
    if (existing) return existing;
    var modal = document.createElement('div');
    modal.className = 'modal modal-stack';
    modal.id = CONFIRM_MODAL_ID;
    modal.setAttribute('aria-hidden', 'true');
    modal.setAttribute('role', 'dialog');
    modal.setAttribute('aria-modal', 'true');
    modal.innerHTML =
      '<div class="modal-mask"></div>' +
      '<div class="modal-dialog modal-sm">' +
        '<button type="button" class="modal-close" data-role="close" aria-label="Close">' + iconHtml('x-lg') + '</button>' +
        '<div class="modal-head">' +
          '<h3 data-role="title"></h3>' +
          '<p data-role="message"></p>' +
        '</div>' +
        '<div class="form-actions">' +
          '<button type="button" class="btn btn-ghost" data-role="cancel"></button>' +
          '<button type="button" class="btn btn-primary" data-role="ok"></button>' +
        '</div>' +
      '</div>';
    document.body.appendChild(modal);

    function settle(val) {
      if (!confirmState || confirmState.settled) return;
      confirmState.settled = true;
      var resolve = confirmState.resolve;
      confirmState = null;
      closeModal(CONFIRM_MODAL_ID);
      resolve(val);
    }

    // 注入式弹窗不在 bindModals 初始化范围内，自行绑定遮罩/关闭/取消/确认
    modal.querySelector('.modal-mask').addEventListener('click', function () { settle(false); });
    modal.querySelector('[data-role=close]').addEventListener('click', function () { settle(false); });
    modal.querySelector('[data-role=cancel]').addEventListener('click', function () { settle(false); });
    modal.querySelector('[data-role=ok]').addEventListener('click', function () { settle(true); });

    // ESC：捕获阶段拦截，避免全局冒泡版 closeModal 只关弹窗不 resolve 本 Promise；
    // 仅当本弹窗在最上层时生效，按"取消"结算
    document.addEventListener('keydown', function (e) {
      if (e.key !== 'Escape' || !confirmState || confirmState.settled) return;
      var opens = document.querySelectorAll('.modal.open');
      if (opens.length && opens[opens.length - 1].id === CONFIRM_MODAL_ID) {
        e.stopPropagation();
        settle(false);
      }
    }, true);

    return modal;
  }

  /**
   * 美化版确认弹窗，返回 Promise<boolean>：true=确认，false=取消（关闭×/遮罩/ESC 同取消）
   * opts: { title, okText, cancelText, danger }  danger=确认按钮用危险色（删除/重置类操作）
   */
  function confirmDialog(message, opts) {
    opts = opts || {};
    var modal = buildConfirmModal();
    modal.querySelector('[data-role=title]').textContent = opts.title || t('common.confirmTitle');
    modal.querySelector('[data-role=message]').textContent = message == null ? '' : String(message);
    modal.querySelector('[data-role=cancel]').textContent = opts.cancelText || t('common.cancel');
    var okBtn = modal.querySelector('[data-role=ok]');
    okBtn.textContent = opts.okText || t('common.confirm');
    okBtn.className = 'btn ' + (opts.danger ? 'btn-danger' : 'btn-primary');

    return new Promise(function (resolve) {
      // 上一个未关闭的确认先按"取消"结算，保证单实例语义
      if (confirmState && !confirmState.settled) {
        confirmState.settled = true;
        confirmState.resolve(false);
      }
      confirmState = { resolve: resolve, settled: false };
      openModal(CONFIRM_MODAL_ID);
      setTimeout(function () { okBtn.focus(); }, 50);
    });
  }

  /* 对外工具（各页面内联脚本使用） */
  window.JargusUI = {
    t: t,
    esc: esc,
    toast: toast,
    api: api,
    openModal: openModal,
    closeModal: closeModal,
    multiSelect: multiSelect,
    confirm: confirmDialog,
    token: { get: getToken, clear: clearToken }
  };
})();
