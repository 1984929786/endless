/* 永无止境 — 前端逻辑 v3（StepFun 云端大脑版）
 * 特性：快捷建议、消息时间戳、打字动画、Markdown 粗体/代码/引用
 */
(function () {
  'use strict';

  // ========== 桥接 ==========
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

  // ========== DOM ==========
  var $ = function (id) { return document.getElementById(id); };
  var bootPage = $('boot'), mainPage = $('main');
  var bootStatus = $('bootStatus'), bootBar = $('bootBar'), bootRetry = $('bootRetry'), bootSkip = $('bootSkip');
  var chatEl = $('chat'), messagesEl = $('messages'), welcomeEl = $('welcome');
  var inputEl = $('input'), sendBtn = $('btnSend'), topTitle = $('topTitle');
  var drawer = $('drawer'), drawerMask = $('drawerMask');
  var subpage = $('subpage'), subTitle = $('subTitle'), subContent = $('subContent');
  var modelBadge = $('modelBadge');

  // ========== State ==========
  var state = {
    messages: [],
    busy: false,
    agentReady: false,
    sessionId: Date.now().toString(36),
    pending: false
  };

  // 快捷建议
  var SUGGESTIONS = [
    '今天天气怎么样？',
    '帮我写一段 Python 代码',
    '解释一下什么是量子计算',
    '给我讲个有趣的历史故事'
  ];

  // ========== Boot ==========
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

  function boot() {
    setBoot('正在准备…', 30);
    call('status').then(function (s) {
      s = String(s || '');
      if (s === 'agent') {
        state.agentReady = true;
        setBoot('已连接', 100);
        setTimeout(function () { enterMain(true); }, 300);
      } else {
        setBoot('界面就绪', 100);
        setTimeout(function () { enterMain(false); }, 300);
      }
    }).catch(function (e) {
      setBoot('连接失败：' + (e && e.message ? e.message : String(e)), 0);
      bootBar.classList.remove('indeterminate');
      bootRetry.classList.remove('hidden');
    });
  }

  bootSkip.onclick = function () {
    setBoot('已跳过', 100);
    enterMain(false);
  };

  bootRetry.onclick = function () {
    bootRetry.classList.add('hidden');
    setBoot('重试中…', 20);
    call('bootstrap').then(function () { return call('status'); })
      .then(function (s) {
        state.agentReady = (String(s) === 'agent');
        setBoot(state.agentReady ? '已连接' : '界面就绪', 100);
        setTimeout(function () { enterMain(state.agentReady); }, 300);
      })
      .catch(function (e) {
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
      setBadge('已连接 · StepFun');
    } else {
      setBadge('未连接');
    }
    renderSuggestions();
  }

  // ========== Badge ==========
  function setBadge(text) {
    if (!modelBadge) return;
    modelBadge.textContent = text;
  }

  // ========== 快捷建议 ==========
  function renderSuggestions() {
    // 清空旧的建议
    var old = $('suggestions');
    if (old) old.remove();

    if (state.messages.length > 0) return;

    var wrap = document.createElement('div');
    wrap.id = 'suggestions';
    wrap.style.cssText = 'display:flex;flex-wrap:wrap;gap:8px;padding:0 16px 16px;justify-content:center;';

    SUGGESTIONS.forEach(function (s) {
      var chip = document.createElement('button');
      chip.className = 'suggestion-chip';
      chip.textContent = s;
      chip.onclick = function () {
        inputEl.value = s;
        autoGrow();
        send();
      };
      wrap.appendChild(chip);
    });

    // 插入到 welcome 和 messages 之间
    var chat = $('chat');
    chat.insertBefore(wrap, messagesEl);
  }

  // 动态注入 chip 样式
  var chipStyle = document.createElement('style');
  chipStyle.textContent = '.suggestion-chip{padding:10px 18px;border:1px solid rgba(108,140,255,0.2);border-radius:20px;background:rgba(108,140,255,0.06);color:var(--accent);font-size:14px;font-family:var(--font);cursor:pointer;transition:all 0.2s;}.suggestion-chip:active{background:rgba(108,140,255,0.15);transform:scale(0.96);}';
  document.head.appendChild(chipStyle);

  // ========== 渲染消息 ==========
  function renderMsg(role, content, opts) {
    opts = opts || {};
    var now = new Date();
    var timeStr = pad(now.getHours()) + ':' + pad(now.getMinutes());

    var wrap = document.createElement('div');
    wrap.className = 'msg ' + (role === 'user' ? 'user' : 'ai');
    if (opts.error) wrap.classList.add('msg-error');

    var av = document.createElement('div');
    av.className = 'avatar';
    av.textContent = role === 'user' ? '我' : '道';

    var body = document.createElement('div');
    body.className = 'msg-body';

    var bub = document.createElement('div');
    bub.className = 'bubble';
    if (opts.typing) bub.classList.add('typing');
    bub.innerHTML = formatText(content);

    var time = document.createElement('div');
    time.className = 'msg-time';
    time.textContent = timeStr;

    body.appendChild(bub);
    body.appendChild(time);
    wrap.appendChild(av);
    wrap.appendChild(body);
    messagesEl.appendChild(wrap);
    scrollBottom();
    return bub;
  }

  function pad(n) { return n < 10 ? '0' + n : '' + n; }

  // 轻量格式化：粗体、代码块、引用、换行
  function formatText(text) {
    if (!text) return '';
    text = esc(text);

    // 代码块 ```...```
    text = text.replace(/```(\w*)\n([\s\S]*?)```/g, function (_, lang, code) {
      return '<pre class="code-block"><code>' + code.trim() + '</code></pre>';
    });

    // 行内代码 `...`
    text = text.replace(/`([^`]+)`/g, '<code class="inline-code">$1</code>');

    // 粗体 **...**
    text = text.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');

    // 引用 >...
    text = text.replace(/^&gt; (.+)$/gm, '<blockquote>$1</blockquote>');

    // 换行
    text = text.replace(/\n/g, '<br>');

    return text;
  }

  function esc(s) {
    return String(s || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  // 注入格式化样式
  var fmtStyle = document.createElement('style');
  fmtStyle.textContent = [
    '.code-block{background:var(--bg);border:1px solid rgba(255,255,255,0.06);border-radius:8px;padding:12px;margin:8px 0;overflow-x:auto;font-size:13px;font-family:"Courier New",monospace;line-height:1.6;color:#a5d6ff;}',
    '.inline-code{background:var(--bg4);padding:2px 6px;border-radius:4px;font-size:13px;font-family:"Courier New",monospace;color:var(--accent2);}',
    '.msg.ai blockquote{border-left:3px solid var(--accent);padding-left:12px;margin:8px 0;color:var(--text2);font-style:italic;}'
  ].join('');
  document.head.appendChild(fmtStyle);

  function pushError(text) { renderMsg('ai', text, { error: true }); }

  function scrollBottom() {
    requestAnimationFrame(function () { chatEl.scrollTop = chatEl.scrollHeight; });
  }

  // ========== 发送 ==========
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
      pushError('正在连接 OpenClaw…若持续失败，请进入「网关状态」查看');
      return;
    }

    // 隐藏建议和欢迎
    var sug = $('suggestions');
    if (sug) sug.style.display = 'none';
    welcomeEl.style.display = 'none';

    inputEl.value = '';
    autoGrow();
    topTitle.textContent = text.length > 18 ? text.slice(0, 18) + '…' : text;

    renderMsg('user', text);
    state.messages.push({ role: 'user', content: text });

    state.busy = true;
    sendBtn.disabled = true;

    var bubble = renderMsg('ai', '', { typing: true });
    var t0 = Date.now();

    var timer = setInterval(function () {
      var s = Math.round((Date.now() - t0) / 1000);
      bubble.textContent = '处理中… ' + s + 's';
    }, 600);

    var payload = JSON.stringify({ messages: state.messages.slice(-16) });

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
          bubble.innerHTML = errMsg ? ('<strong>生成失败</strong><br>' + esc(errMsg)) : '这次没生成出正文，再发一次试试。';
          bubble.parentElement.classList.add('msg-error');
        } else {
          bubble.innerHTML = formatText(answer);
          state.messages.push({ role: 'assistant', content: answer });
          saveSession();
          // 刷新连接状态
          call('mode').then(function (m) {
            state.agentReady = (String(m) === 'agent');
            setBadge(state.agentReady ? '已连接 · StepFun' : '未连接');
          }).catch(function () {});
        }
        state.busy = false;
        sendBtn.disabled = false;
        autoGrow();
        scrollBottom();
      }
    }, 80);
  }

  // ========== 会话 ==========
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
    renderSuggestions();
  }

  // ========== 抽屉 ==========
  function openDrawer() { drawer.classList.add('show'); drawerMask.classList.add('show'); }
  function closeDrawer() { drawer.classList.remove('show'); drawerMask.classList.remove('show'); }
  $('btnMenu').onclick = openDrawer;
  drawerMask.onclick = closeDrawer;
  $('btnNew').onclick = newChat;
  $('btnBack').onclick = function () { subpage.classList.remove('show'); };

  // ========== 导航 ==========
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
    subTitle.textContent = { history: '历史会话', gateway: '网关状态', settings: '设置', about: '关于' }[name] || name;
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

  // ========== 历史 ==========
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
        d.className = 'session-card';
        var t = document.createElement('div');
        t.className = 'session-card-title';
        t.textContent = s.title || '未命名对话';
        var m = document.createElement('div');
        m.className = 'session-card-meta';
        m.textContent = (s.messages ? s.messages.length : 0) + ' 条消息 · ' + fmtTime(s.time);
        d.appendChild(t);
        d.appendChild(m);
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
    return (d.getMonth() + 1) + '/' + d.getDate() + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
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

  // ========== 网关 ==========
  function renderGateway() {
    call('gwInfo').then(function (json) {
      var info = {};
      try { info = JSON.parse(json || '{}'); } catch (e) {}
      var html = '';

      // 状态卡片
      html += '<div class="card"><div class="card-title">连接状态</div>';
      html += '<div style="display:flex;flex-direction:column;gap:8px;margin-top:4px;">';
      html += item('网关进程', info.proc ? '<span class="status-dot on"></span>运行中' : '<span class="status-dot off"></span>未运行');
      html += item('端口探活', info.probe ? '<span class="status-dot on"></span>正常' : '<span class="status-dot off"></span>不通');
      html += item('健康检查', info.health || '-');
      html += item('运行时', info.extracted ? '已释放' : '未释放');
      html += item('配置文件', info.hasConfig ? '存在' : '缺失');
      html += '</div></div>';

      // 诊断
      if (info.steps) {
        html += '<div class="card"><div class="card-title">启动诊断</div>';
        html += '<pre class="log-pre">' + esc(info.steps) + '</pre></div>';
      }

      // 日志
      if (info.logTail) {
        html += '<div class="card"><div class="card-title">日志</div>';
        html += '<pre class="log-pre">' + esc(info.logTail) + '</pre></div>';
      }

      // 操作按钮
      html += '<div style="display:flex;gap:8px;flex-wrap:wrap;">';
      html += '<button class="btn btn-primary" onclick="__gwStart()">启动网关</button>';
      html += '<button class="btn" onclick="__gwTest()">测试连接</button>';
      html += '<button class="btn" onclick="__exportLog()">导出日志</button>';
      html += '</div>';
      html += '<div id="gwResult" style="margin-top:12px;font-size:13px;color:var(--text2);"></div>';

      subContent.innerHTML = html;
    }).catch(function () {
      subContent.innerHTML = '<div class="empty">读取网关信息失败</div>';
    });
  }

  function item(label, val) {
    return '<div style="display:flex;justify-content:space-between;align-items:center;font-size:14px;">' +
      '<span style="color:var(--text2);">' + esc(label) + '</span>' +
      '<span style="color:var(--text);font-weight:500;">' + val + '</span></div>';
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
    call('gwTest').then(function (r) { if (el) el.textContent = String(r); })
      .catch(function (e) { if (el) el.textContent = '失败：' + (e && e.message ? e.message : String(e)); });
  };

  window.__exportLog = function () {
    call('exportLog').then(function (r) { alert('日志已导出：' + String(r)); })
      .catch(function (e) { alert('导出失败：' + (e && e.message ? e.message : String(e))); });
  };

  // ========== 设置 ==========
  function renderSettings() {
    call('getApiKeyMasked').then(function (masked) {
      masked = String(masked || '');
      var html = '';
      html += '<div class="card"><div class="card-title">StepFun API Key</div>';
      html += '<div class="card-desc">填入你的 StepFun API Key 以启用云端 AI 能力</div>';
      html += '<input id="apiKeyInput" class="api-input" type="text" placeholder="sk-..." value="' + esc(masked) + '">';
      html += '<button class="btn btn-primary" style="width:100%;" onclick="__saveKey()">保存 Key</button>';
      html += '</div>';

      html += '<div class="card"><div class="card-title">关于</div>';
      html += '<div class="card-desc">永无止境 v3.9.0<br>OpenClaw 网关 + StepFun 云端大脑<br>包名 com.xundao.yongwuzhijing<br>内嵌端口 18800</div>';
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
    if (!key || key.indexOf('…') > 0) { alert('请输入完整的 API Key'); return; }
    call('setApiKey', key).then(function (r) { alert(String(r)); })
      .catch(function (e) { alert('保存失败：' + (e && e.message ? e.message : String(e))); });
  };

  // ========== 关于 ==========
  function renderAbout() {
    subContent.innerHTML = '';
    subContent.appendChild(makeCard('永无止境', 'v3.9.0', 'OpenClaw 网关 + StepFun 云端大脑'));
    subContent.appendChild(makeCard('架构', 'App = 面板', 'OpenClaw = 手 · StepFun API = 脑'));
    subContent.appendChild(makeCard('包名', 'com.xundao.yongwuzhijing', '内嵌网关端口 18800'));
    subContent.appendChild(makeCard('能力', '文件 / 命令 / 浏览器 / 记忆 / 定时', '通过 OpenClaw agent 路径调用'));
  }

  function makeCard(title, desc, sub) {
    var c = document.createElement('div');
    c.className = 'card';
    var t = document.createElement('div');
    t.className = 'card-title';
    t.textContent = title;
    c.appendChild(t);
    if (desc) { var d = document.createElement('div'); d.className = 'card-desc'; d.textContent = desc; c.appendChild(d); }
    if (sub) { var s = document.createElement('div'); s.className = 'card-desc'; s.style.fontSize = '12px'; s.style.color = 'var(--text3)'; s.textContent = sub; c.appendChild(s); }
    return c;
  }

  // ========== Init ==========
  window.addEventListener('DOMContentLoaded', boot);
})();
