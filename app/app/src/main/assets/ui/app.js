/* 永无止境 — 前端逻辑 */
(function () {
  'use strict';

  // ---------- 桥接（Android 注入 window.Native） ----------
  var N = window.Native || null;

  function call(fn) {
    if (!N || typeof N[fn] !== 'function') return Promise.reject(new Error('原生接口不可用: ' + fn));
    try {
      var r;
      var n = arguments.length;
      if (n <= 1) r = N[fn]();
      else if (n === 2) r = N[fn](arguments[1]);
      else if (n === 3) r = N[fn](arguments[1], arguments[2]);
      else if (n === 4) r = N[fn](arguments[1], arguments[2], arguments[3]);
      else r = N[fn](arguments[1], arguments[2], arguments[3], arguments[4]);
      return Promise.resolve(r);
    } catch (e) {
      return Promise.reject(e);
    }
  }

  // ---------- DOM ----------
  var $ = function (id) { return document.getElementById(id); };
  var bootPage = $('boot'), mainPage = $('main');
  var bootStatus = $('bootStatus'), bootBar = $('bootBar'), bootRetry = $('bootRetry');
  var chatEl = $('chat'), messagesEl = $('messages'), welcomeEl = $('welcome');
  var inputEl = $('input'), sendBtn = $('btnSend'), topTitle = $('topTitle');
  var drawer = $('drawer'), drawerMask = $('drawerMask');
  var subpage = $('subpage'), subTitle = $('subTitle'), subContent = $('subContent');
  var modelBadge = $('modelBadge');

  // ---------- 状态 ----------
  var state = {
    messages: [],      // {role, content}
    busy: false,
    modelReady: false,
    sessionId: Date.now().toString(36),
    streaming: ''
  };

  // ---------- 启动 ----------
  function boot() {
    setBoot('正在准备本地引擎…', 5);
    call('bootstrap').then(function () {
      pollStatus(0);
    }).catch(function (e) {
      bootFail(e && e.message ? e.message : String(e));
    });
  }

  var pollTimer = null;
  var pollStart = 0;

  function pollStatus(n) {
    if (!pollStart) pollStart = Date.now();
    if (Date.now() - pollStart > 300000) {
      bootFail('加载超时（5 分钟）');
      return;
    }

    call('status').then(function (s) {
      s = String(s || '');
      if (s === 'ready') {
        setBoot('就绪', 100);
        setTimeout(enterMain, 300);
        return;
      }
      if (s.indexOf('fail:') === 0) {
        bootFail(s.slice(5));
        return;
      }
      if (s.indexOf('loading:') === 0) {
        var msg = s.slice(8);
        var pct = 10 + Math.min(80, n * 2);
        setBoot(msg || '正在加载…', pct);
        pollTimer = setTimeout(function () { pollStatus(n + 1); }, 500);
        return;
      }
      // idle：重新触发
      call('loadModel').catch(function () {});
      pollTimer = setTimeout(function () { pollStatus(n + 1); }, 700);
    }).catch(function (e) {
      bootFail(e && e.message ? e.message : String(e));
    });
  }

  function bootFail(msg) {
    if (pollTimer) { clearTimeout(pollTimer); pollTimer = null; }
    pollStart = 0;
    setBoot('启动失败：' + msg, 0);
    bootBar.classList.remove('indeterminate');
    bootRetry.classList.remove('hidden');
  }

  function setBoot(text, pct) {
    bootStatus.textContent = text || '';
    if (pct == null || pct <= 0) {
      bootBar.style.width = '100%';
      bootBar.classList.add('indeterminate');
    } else {
      bootBar.classList.remove('indeterminate');
      bootBar.style.width = pct + '%';
    }
  }

  bootRetry.onclick = function () {
    bootRetry.classList.add('hidden');
    setBoot('正在重试…', 5);
    pollStart = 0;
    if (pollTimer) { clearTimeout(pollTimer); pollTimer = null; }
    call('loadModel').then(function () {
      pollStatus(0);
    }).catch(function (e) {
      bootFail(e && e.message ? e.message : String(e));
    });
  };

  function enterMain() {
    bootPage.classList.remove('active');
    mainPage.classList.add('active');
    state.modelReady = true;
    setBadge('本地模型已就绪', true);
  }

  // ---------- 模型 ----------
  function loadModel() {
    if (state.modelReady) return;
    setBadge('模型加载中…', false);
    call('loadModel').catch(function () {});
  }

  function setBadge(text, ready) {
    modelBadge.textContent = text;
    modelBadge.classList.toggle('ready', !!ready);
  }

  // ---------- 渲染 ----------
  function renderMsg(role, content, opts) {
    opts = opts || {};
    var wrap = document.createElement('div');
    wrap.className = 'msg ' + (role === 'user' ? 'user' : 'ai');
    if (opts.error) wrap.classList.add('msg-error');

    var av = document.createElement('div');
    av.className = 'avatar';
    av.textContent = role === 'user' ? '我' : '道';

    var bub = document.createElement('div');
    bub.className = 'bubble';
    if (opts.typing) bub.classList.add('typing');
    bub.textContent = content;

    wrap.appendChild(av);
    wrap.appendChild(bub);
    messagesEl.appendChild(wrap);
    scrollBottom();
    return bub;
  }

  function pushError(text) {
    renderMsg('ai', text, { error: true });
  }

  function scrollBottom() {
    requestAnimationFrame(function () {
      chatEl.scrollTop = chatEl.scrollHeight;
    });
  }

  // ---------- 发送 ----------
  function autoGrow() {
    inputEl.style.height = 'auto';
    inputEl.style.height = Math.min(inputEl.scrollHeight, 120) + 'px';
    sendBtn.disabled = !inputEl.value.trim() || state.busy;
  }

  inputEl.addEventListener('input', autoGrow);
  inputEl.addEventListener('keydown', function (e) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      send();
    }
  });
  sendBtn.onclick = send;

  function send() {
    var text = inputEl.value.trim();
    if (!text || state.busy) return;

    if (!state.modelReady) {
      pushError('模型还没准备好，请稍候…');
      return;
    }

    inputEl.value = '';
    autoGrow();
    welcomeEl.style.display = 'none';
    topTitle.textContent = text.slice(0, 18);

    renderMsg('user', text);
    state.messages.push({ role: 'user', content: text });

    state.busy = true;
    sendBtn.disabled = true;

    var bubble = renderMsg('ai', '', { typing: true });
    state.streaming = '';

    var payload = {
      messages: state.messages.slice(-12),
      onToken: function (piece) {
        state.streaming += piece;
        bubble.textContent = state.streaming;
        scrollBottom();
      }
    };

    call('complete', JSON.stringify(payload)).then(function (full) {
      bubble.classList.remove('typing');
      var answer = full || state.streaming || '';
      bubble.textContent = answer;
      state.messages.push({ role: 'assistant', content: answer });
      saveSession();
    }).catch(function (e) {
      bubble.classList.remove('typing');
      bubble.textContent = '出错了：' + (e && e.message ? e.message : e);
      bubble.parentElement.classList.add('msg-error');
    }).then(function () {
      state.busy = false;
      autoGrow();
      scrollBottom();
    });
  }

  // ---------- 会话 ----------
  function saveSession() {
    try {
      call('saveSession', state.sessionId, JSON.stringify({
        id: state.sessionId,
        title: topTitle.textContent,
        time: Date.now(),
        messages: state.messages
      }));
    } catch (e) { /* 忽略 */ }
  }

  function newChat() {
    state.sessionId = Date.now().toString(36);
    state.messages = [];
    messagesEl.innerHTML = '';
    welcomeEl.style.display = '';
    topTitle.textContent = '新对话';
    inputEl.value = '';
    autoGrow();
    closeDrawer();
    showPage('chat');
  }

  // ---------- 抽屉 ----------
  function openDrawer() {
    drawer.classList.add('show');
    drawerMask.classList.add('show');
  }
  function closeDrawer() {
    drawer.classList.remove('show');
    drawerMask.classList.remove('show');
  }

  $('btnMenu').onclick = openDrawer;
  drawerMask.onclick = closeDrawer;
  $('btnNew').onclick = newChat;
  $('btnBack').onclick = function () {
    subpage.classList.remove('show');
    showPage('chat');
  };

  // ---------- 页面切换 ----------
  var NAV_TITLE = {
    history: '历史会话',
    models: '模型管理',
    settings: '设置',
    about: '关于'
  };

  Array.prototype.forEach.call(document.querySelectorAll('.nav-item'), function (btn) {
    btn.onclick = function () {
      var p = btn.getAttribute('data-page');
      closeDrawer();
      if (p === 'chat') { showPage('chat'); return; }
      showPage(p);
    };
  });

  function showPage(name) {
    if (name === 'chat') {
      subpage.classList.remove('show');
      return;
    }
    subTitle.textContent = NAV_TITLE[name] || name;
    subContent.innerHTML = '<div class="empty">加载中…</div>';
    subpage.classList.add('show');
    renderPage(name);
  }

  function renderPage(name) {
    if (name === 'history') return renderHistory();
    if (name === 'models') return renderModels();
    if (name === 'settings') return renderSettings();
    if (name === 'about') return renderAbout();
  }

  function renderHistory() {
    call('listSessions').then(function (json) {
      var list = [];
      try { list = JSON.parse(json || '[]'); } catch (e) {}
      if (!list.length) {
        subContent.innerHTML = '<div class="empty">还没有历史会话<br>开始一次对话吧</div>';
        return;
      }
      list.sort(function (a, b) { return (b.time || 0) - (a.time || 0); });
      subContent.innerHTML = '';
      list.forEach(function (s) {
        var d = document.createElement('div');
        d.className = 'card';
        var t = document.createElement('div');
        t.className = 'card-title';
        t.textContent = s.title || '未命名对话';
        var desc = document.createElement('div');
        desc.className = 'card-desc';
        desc.textContent = (s.messages ? s.messages.length : 0) + ' 条消息 · ' + fmtTime(s.time);
        d.appendChild(t);
        d.appendChild(desc);
        d.onclick = function () { openSession(s); };
        subContent.appendChild(d);
      });
    }).catch(function () {
      subContent.innerHTML = '<div class="empty">读取历史失败</div>';
    });
  }

  function fmtTime(ms) {
    if (!ms) return '';
    var d = new Date(ms);
    var pad = function (n) { return n < 10 ? '0' + n : n; };
    return (d.getMonth() + 1) + '月' + d.getDate() + '日 ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
  }

  function openSession(s) {
    state.sessionId = s.id || Date.now().toString(36);
    state.messages = (s.messages || []).slice();
    messagesEl.innerHTML = '';
    welcomeEl.style.display = 'none';
    topTitle.textContent = s.title || '对话';
    state.messages.forEach(function (m) {
      renderMsg(m.role === 'user' ? 'user' : 'ai', m.content);
    });
    subpage.classList.remove('show');
  }

  function renderModels() {
    call('modelInfo').then(function (json) {
      var info = {};
      try { info = JSON.parse(json || '{}'); } catch (e) {}
      subContent.innerHTML = '';
      subContent.appendChild(card(
        '当前模型',
        (info.name || 'Qwen3-0.6B') + ' · 本地运行',
        info.path ? '位置：' + info.path : ''
      ));
      subContent.appendChild(card(
        '更换模型',
        '把新的模型文件放到指定目录后，在设置中切换',
        '首版仅支持内置模型'
      ));
    }).catch(function () {
      subContent.innerHTML = '<div class="empty">读取模型信息失败</div>';
    });
  }

  function renderSettings() {
    subContent.innerHTML = '';
    subContent.appendChild(card('对话设置', '保留最近 12 条消息作为上下文', ''));
    subContent.appendChild(card('清除所有会话', '删除本机保存的全部对话记录', '', {
      btn: '清除', danger: true, onTap: function () {
        call('clearSessions').then(function () {
          subContent.querySelectorAll('.card')[1].querySelector('.card-desc').textContent = '已清除';
        });
      }
    }));
  }

  function renderAbout() {
    subContent.innerHTML = '';
    subContent.appendChild(card('永无止境', '版本 1.2.0', '本地智能助手，所有对话留在本机'));
    subContent.appendChild(card('引擎', 'Cactus Engine v1.14', '为移动设备优化的本地推理引擎'));
    subContent.appendChild(card('模型', 'Qwen3 系列', '在骁龙/麒麟芯片上本地运行'));
  }

  function card(title, desc, sub, opts) {
    opts = opts || {};
    var d = document.createElement('div');
    d.className = 'card';

    var row = document.createElement('div');
    row.className = 'card-row';

    var left = document.createElement('div');
    left.style.flex = '1';
    left.style.minWidth = '0';

    var t = document.createElement('div');
    t.className = 'card-title';
    t.textContent = title;
    left.appendChild(t);

    if (desc) {
      var de = document.createElement('div');
      de.className = 'card-desc';
      de.textContent = desc;
      left.appendChild(de);
    }
    if (sub) {
      var se = document.createElement('div');
      se.className = 'card-desc';
      se.style.opacity = '.65';
      se.textContent = sub;
      left.appendChild(se);
    }

    row.appendChild(left);

    if (opts.btn) {
      var b = document.createElement('button');
      b.className = 'btn-primary' + (opts.danger ? ' btn-danger' : '');
      b.style.padding = '8px 16px';
      b.style.fontSize = '13px';
      b.textContent = opts.btn;
      b.onclick = opts.onTap;
      row.appendChild(b);
    }

    d.appendChild(row);
    return d;
  }

  // ---------- 返回键 ----------
  window.__onBack = function () {
    if (drawer.classList.contains('show')) { closeDrawer(); return true; }
    if (subpage.classList.contains('show')) { subpage.classList.remove('show'); return true; }
    return false;
  };

  // ---------- 启动 ----------
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
