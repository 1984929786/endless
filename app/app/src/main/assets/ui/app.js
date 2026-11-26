/* 永无止境 — 前端逻辑 v2（StepFun 云端大脑版）
 * 本地模型推理已移除。AI 能力由 OpenClaw 网关 + StepFun API 提供。
 */
(function () {
  'use strict';

  // ---------- 桥接 ----------
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
    } catch (e) { return Promise.reject(e); }
  }

  // ---------- DOM ----------
  var $ = function (id) { return document.getElementById(id); };
  var bootPage = $('boot'), mainPage = $('main');
  var bootStatus = $('bootStatus'), bootBar = $('bootBar'), bootRetry = $('bootRetry'), bootSkip = $('bootSkip');
  var chatEl = $('chat'), messagesEl = $('messages'), welcomeEl = $('welcome');
  var inputEl = $('input'), sendBtn = $('btnSend'), topTitle = $('topTitle');
  var drawer = $('drawer'), drawerMask = $('drawerMask');
  var subpage = $('subpage'), subTitle = $('subTitle'), subContent = $('subContent');
  var modelBadge = $('modelBadge');

  // ---------- 状态 ----------
  var state = {
    messages: [],
    busy: false,
    agentReady: false,
    sessionId: Date.now().toString(36),
    streaming: ''
  };

  // ---------- 启动 ----------
  function boot() {
    setBoot('正在连接 OpenClaw…', 30);
    call('status').then(function (s) {
      s = String(s || '');
      if (s === 'agent') {
        state.agentReady = true;
        setBoot('已连接 OpenClaw', 100);
        setTimeout(function () { enterMain(true); }, 300);
      } else {
        setBoot('界面就绪，等待网关…', 100);
        setTimeout(function () { enterMain(false); }, 300);
      }
    }).catch(function (e) {
      setBoot('桥接异常：' + (e && e.message ? e.message : String(e)), 0);
      bootBar.classList.remove('indeterminate');
      bootSkip.classList.remove('hidden');
    });
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

  bootSkip.onclick = function () {
    setBoot('已跳过', 100);
    enterMain(false);
  };

  bootRetry.onclick = function () {
    bootRetry.classList.add('hidden');
    setBoot('正在重试…', 20);
    call('bootstrap').then(function () {
      return call('status');
    }).then(function (s) {
      state.agentReady = (String(s) === 'agent');
      setBoot(state.agentReady ? '已连接' : '界面就绪', 100);
      setTimeout(function () { enterMain(state.agentReady); }, 300);
    }).catch(function (e) {
      setBoot('重试失败：' + (e && e.message ? e.message : String(e)), 0);
      bootBar.classList.add('indeterminate');
      bootSkip.classList.remove('hidden');
    });
  };

  function enterMain(ready) {
    bootPage.classList.remove('active');
    mainPage.classList.add('active');
    state.agentReady = !!ready;

    if (state.agentReady) {
      setBadge('已连接 OpenClaw · StepFun', true);
      var ab = $('btnRelease'); if (ab) ab.classList.add('hidden');
    } else {
      setBadge('未连接', false);
    }
  }

  // ---------- 模型信息（简化） ----------
  function setBadge(text, ready) {
    if (!modelBadge) return;
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
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); }
  });
  sendBtn.onclick = send;

  function send() {
    var text = inputEl.value.trim();
    if (!text || state.busy) return;
    if (!state.agentReady) {
      pushError('正在连接 OpenClaw…若持续失败，请进入「网关」页面查看状态');
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
    var t0 = Date.now();

    var timer = setInterval(function () {
      var s = Math.round((Date.now() - t0) / 1000);
      bubble.textContent = '处理中（可能正在调用工具）… ' + s + 's';
    }, 500);

    var payload = JSON.stringify({ messages: state.messages.slice(-12) });

    setTimeout(function () {
      call('complete', payload).then(function (v) {
        finish(String(v == null ? '' : v));
      }).catch(function (e) {
        finish('', e && e.message ? e.message : String(e));
      });

      function finish(answer, errMsg) {
        clearInterval(timer);
        bubble.classList.remove('typing');
        if (!answer) {
          bubble.textContent = errMsg ? ('生成失败：' + errMsg) : '这次没生成出正文。再发一次试试。';
          bubble.parentElement.classList.add('msg-error');
        } else {
          bubble.textContent = answer;
          state.messages.push({ role: 'assistant', content: answer });
          saveSession();
          call('mode').then(function (m) {
            state.agentReady = (String(m) === 'agent');
            setBadge(state.agentReady ? '已连接 OpenClaw · StepFun' : '未连接', state.agentReady);
          }).catch(function () {});
        }
        state.busy = false;
        sendBtn.disabled = false;
        autoGrow();
        scrollBottom();
      }
    }, 60);
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
    } catch (e) {}
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
  function openDrawer() { drawer.classList.add('show'); drawerMask.classList.add('show'); }
  function closeDrawer() { drawer.classList.remove('show'); drawerMask.classList.remove('show'); }
  $('btnMenu').onclick = openDrawer;
  drawerMask.onclick = closeDrawer;
  $('btnNew').onclick = newChat;
  $('btnBack').onclick = function () { subpage.classList.remove('show'); showPage('chat'); };

  // ---------- 页面切换 ----------
  var NAV_TITLE = { history: '历史会话', gateway: '网关', settings: '设置', about: '关于' };

  Array.prototype.forEach.call(document.querySelectorAll('.nav-item'), function (btn) {
    btn.onclick = function () {
      var p = btn.getAttribute('data-page');
      closeDrawer();
      if (p === 'chat') { showPage('chat'); return; }
      showPage(p);
    };
  });

  function showPage(name) {
    if (name === 'chat') { subpage.classList.remove('show'); return; }
    subTitle.textContent = NAV_TITLE[name] || name;
    subContent.innerHTML = '<div class="empty">加载中…</div>';
    subpage.classList.add('show');
    renderPage(name);
  }

  function renderPage(name) {
    if (name === 'history') return renderHistory();
    if (name === 'gateway') return renderGateway();
    if (name === 'settings') return renderSettings();
    if (name === 'about') return renderAbout();
  }

  // ---------- 历史会话 ----------
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

  // ---------- 网关页面 ----------
  function renderGateway() {
    call('gwInfo').then(function (json) {
      var info = {};
      try { info = JSON.parse(json || '{}'); } catch (e) {}
      var lines = [];
      lines.push('<div class="card"><div class="card-title">网关状态</div><div class="card-desc">');
      lines.push('端口: ' + (info.port || '-'));
      lines.push('进程: ' + (info.proc ? '运行中' : '未运行'));
      lines.push('探活: ' + (info.probe ? '正常' : '不通'));
      lines.push('健康: ' + (info.health || '-'));
      lines.push('Token: ' + (info.tokenHint || '-'));
      lines.push('配置: ' + (info.hasConfig ? '存在' : '缺失'));
      lines.push('运行时: ' + (info.extracted ? '已释放' : '未释放'));
      lines.push('</div></div>');

      // 诊断步骤
      if (info.steps) {
        lines.push('<div class="card"><div class="card-title">启动诊断</div>');
        lines.push('<pre style="font-size:12px;color:#888;white-space:pre-wrap;word-break:break-all;">' + esc(info.steps) + '</pre>');
        lines.push('</div>');
      }

      // 日志尾
      if (info.logTail) {
        lines.push('<div class="card"><div class="card-title">网关日志</div>');
        lines.push('<pre style="font-size:11px;color:#666;white-space:pre-wrap;word-break:break-all;max-height:240px;overflow-y:auto;">' + esc(info.logTail) + '</pre>');
        lines.push('</div>');
      }

      // 操作按钮
      lines.push('<div style="display:flex;gap:8px;flex-wrap:wrap;margin-top:12px;">');
      lines.push('<button class="btn btn-primary" onclick="__gwStart()">启动网关</button>');
      lines.push('<button class="btn" onclick="__gwTest()">测试连接</button>');
      lines.push('<button class="btn" onclick="__exportLog()">导出日志</button>');
      lines.push('<button class="btn" onclick="__exportCrash()">导出崩溃</button>');
      lines.push('</div>');
      lines.push('<div id="gwResult" style="margin-top:12px;font-size:13px;color:#888;"></div>');

      subContent.innerHTML = lines.join('');
    }).catch(function () {
      subContent.innerHTML = '<div class="empty">读取网关信息失败</div>';
    });
  }

  function esc(s) {
    return String(s || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  window.__gwStart = function () {
    var el = $('gwResult');
    if (el) el.textContent = '启动中…';
    call('gwStart').then(function (r) {
      if (el) el.textContent = String(r);
      setTimeout(function () { renderPage('gateway'); }, 2000);
    }).catch(function (e) {
      if (el) el.textContent = '失败：' + (e && e.message ? e.message : String(e));
    });
  };

  window.__gwTest = function () {
    var el = $('gwResult');
    if (el) el.textContent = '测试中…';
    call('gwTest').then(function (r) {
      if (el) el.textContent = String(r);
    }).catch(function (e) {
      if (el) el.textContent = '失败：' + (e && e.message ? e.message : String(e));
    });
  };

  window.__exportLog = function () {
    call('exportLog').then(function (r) {
      alert('日志已导出：' + String(r));
    }).catch(function (e) {
      alert('导出失败：' + (e && e.message ? e.message : String(e)));
    });
  };

  window.__exportCrash = function () {
    call('exportCrash').then(function (r) {
      alert(String(r));
    }).catch(function (e) {
      alert('导出失败：' + (e && e.message ? e.message : String(e)));
    });
  };

  // ---------- 设置页面 ----------
  function renderSettings() {
    call('getApiKeyMasked').then(function (masked) {
      masked = String(masked || '');
      var hasKey = masked.length > 0;

      var html = '<div class="card"><div class="card-title">StepFun API Key</div>';
      html += '<div class="card-desc">填入你的 StepFun API Key 以启用云端 AI</div>';
      html += '<input id="apiKeyInput" type="text" placeholder="sk-..." style="width:100%;margin-top:8px;padding:10px;background:#1a1d24;border:1px solid #2a2e38;color:#e0e0e0;border-radius:8px;font-size:14px;" value="' + esc(masked) + '">';
      html += '<button class="btn btn-primary" style="margin-top:10px;" onclick="__saveKey()">保存 Key</button>';
      html += '</div>';

      html += '<div class="card"><div class="card-title">关于</div>';
      html += '<div class="card-desc">永无止境 v3.9<br>OpenClaw 网关 + StepFun API<br>包名 com.xundao.yongwuzhijing</div>';
      html += '</div>';

      subContent.innerHTML = html;
    }).catch(function () {
      subContent.innerHTML = '<div class="empty">读取设置失败</div>';
    });
  }

  window.__saveKey = function () {
    var input = $('apiKeyInput');
    if (!input) return;
    var key = input.value.trim();
    if (!key || key.indexOf('…') > 0) {
      alert('请输入完整的 API Key');
      return;
    }
    call('setApiKey', key).then(function (r) {
      alert(String(r));
    }).catch(function (e) {
      alert('保存失败：' + (e && e.message ? e.message : String(e)));
    });
  };

  // ---------- 关于 ----------
  function renderAbout() {
    subContent.innerHTML = '';
    subContent.appendChild(card('永无止境', 'v3.9.0', 'OpenClaw 网关 + StepFun 云端大脑'));
    subContent.appendChild(card('架构', 'App = 面板', 'OpenClaw = 手 · StepFun API = 脑'));
    subContent.appendChild(card('包名', 'com.xundao.yongwuzhijing', '内嵌网关端口 18800'));
  }

  function card(title, desc, sub) {
    var c = document.createElement('div');
    c.className = 'card';
    var t = document.createElement('div');
    t.className = 'card-title';
    t.textContent = title;
    c.appendChild(t);
    if (desc) {
      var d = document.createElement('div');
      d.className = 'card-desc';
      d.textContent = desc;
      c.appendChild(d);
    }
    if (sub) {
      var s = document.createElement('div');
      s.className = 'card-desc';
      s.style.fontSize = '12px';
      s.style.color = '#666';
      s.textContent = sub;
      c.appendChild(s);
    }
    return c;
  }

  // ---------- 启动 ----------
  window.addEventListener('DOMContentLoaded', boot);
})();
