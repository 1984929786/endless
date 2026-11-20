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
  var bootStatus = $('bootStatus'), bootBar = $('bootBar'), bootRetry = $('bootRetry'), bootSkip = $('bootSkip');
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
    modelStaged: false,   // 模型文件已释放到磁盘，只差加载进内存
    agentReady: false,    // OpenClaw 已连接 = 有工具（文件/命令/浏览器）
    sessionId: Date.now().toString(36),
    streaming: ''
  };

  // ---------- 启动 ----------
  // 关键：进界面不等模型。模型加载放主界面按钮触发，
  // 这样即使 nativeInit 崩溃，界面也已经能操作（道友 2026-11-20 要求）。
  function boot() {
    setBoot('正在准备界面…', 20);
    var st = '';
    call('lastStage').then(function (s) { st = String(s || ''); }).catch(function () {});
    // 只查状态，不触发加载
    call('status').then(function (s) {
      s = String(s || '');
      // agent = OpenClaw 已连接，手已经装上了，不需要等本地模型
      if (s === 'agent') {
        setBoot('就绪', 100);
        setTimeout(function () { enterMain(false, false, true); }, 250);
        return;
      }
      if (s === 'ready') {
        // 上次已经加载好（进程还活着），直接进
        setBoot('就绪', 100);
        setTimeout(function () { enterMain(true); }, 250);
        return;
      }
      // staged = 模型文件已释放过，只差加载进内存
      var staged = (s === 'staged');
      // 其它情况一律进主界面，模型状态由用户手动触发
      setBoot('界面就绪', 100);
      setTimeout(function () {
        enterMain(false, staged);
        if (st) setReleaseHint('上次启动停在：' + st);
      }, 250);
    }).catch(function (e) {
      // 桥接都不可用也别卡启动页
      bootFail(e && e.message ? e.message : String(e));
    });
  }

  var pollTimer = null;
  var pollStart = 0;

  /** 在主界面轮询模型状态（不再占用启动页） */
  function pollStatus(n) {
    if (!pollStart) pollStart = Date.now();
    if (Date.now() - pollStart > 600000) {
      releaseDone(false, '加载超时（10 分钟）');
      return;
    }

    call('status').then(function (s) {
      s = String(s || '');
      if (s === 'ready') {
        releaseDone(true, '模型已就绪');
        return;
      }
      if (s.indexOf('fail:') === 0) {
        releaseDone(false, s.slice(5));
        return;
      }
      if (s.indexOf('loading:') === 0) {
        var msg = s.slice(8);
        setReleaseHint('释放/加载中：' + (msg || '…'));
        setBoot(msg || '正在加载…', 10 + Math.min(85, n));
        pollTimer = setTimeout(function () { pollStatus(n + 1); }, 500);
        return;
      }
      // idle：说明还没开始，触发一次
      call('loadModel').catch(function () {});
      pollTimer = setTimeout(function () { pollStatus(n + 1); }, 700);
    }).catch(function (e) {
      releaseDone(false, e && e.message ? e.message : String(e));
    });
  }

  function bootFail(msg) {
    if (pollTimer) { clearTimeout(pollTimer); pollTimer = null; }
    pollStart = 0;
    setBoot('界面异常：' + msg, 0);
    bootBar.classList.remove('indeterminate');
    bootSkip.classList.remove('hidden');
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

  // 启动页：跳过（无条件进主界面）
  bootSkip.onclick = function () {
    setBoot('已跳过', 100);
    enterMain(false);
  };

  // 启动页：重试
  bootRetry.onclick = function () {
    bootRetry.classList.add('hidden');
    setBoot('正在重试…', 5);
    pollStart = 0;
    if (pollTimer) { clearTimeout(pollTimer); pollTimer = null; }
    call('loadModel').then(function () {
      enterMain(false);
      pollStatus(0);
    }).catch(function (e) {
      bootFail(e && e.message ? e.message : String(e));
    });
  };

  /** ready 参数决定徽标，不再无条件当成就绪 */
  function enterMain(ready, staged, agent) {
    bootPage.classList.remove('active');
    mainPage.classList.add('active');
    state.modelReady = !!ready;
    state.agentReady = !!agent;

    // Agent 可用 = 有手（文件/命令/浏览器/定时），这才是 App 的正式形态。
    // 本地模型只是断网兜底，不应该是拦路的门槛。
    if (state.agentReady) {
      setBadge('已连接 OpenClaw', true);
      setReleaseHint('');
      var ab = $('btnRelease'); if (ab) ab.classList.add('hidden');
      return;
    }

    if (ready) {
      setBadge('本地模型就绪', true);
      setReleaseHint('');
      var b = $('btnRelease'); if (b) b.classList.add('hidden');
      return;
    }
    setBadge('模型未就绪（离线仅本地）', false);
    // 模型文件已经释放过了 —— 只需加载进内存，不要再说「释放」
    // （道友 2026-11-21 反馈：第二次进去还要释放？）
    var btn = $('btnRelease');
    if (staged) {
      state.modelStaged = true;
      if (btn) { btn.classList.remove('hidden'); btn.textContent = '加载模型'; }
      setReleaseHint('模型文件已就绪，点一下加载进内存（几秒）');
    } else {
      state.modelStaged = false;
      if (btn) { btn.classList.remove('hidden'); btn.textContent = '释放内置模型'; }
    }
  }

  // ---------- 模型 ----------
  function setReleaseHint(t) {
    var e = $('releaseHint');
    if (e) e.textContent = t || '';
  }

  /** 用户点「释放内置模型」/「加载模型」/ 重试时调用 */
  function loadModel() {
    if (state.modelReady) return;
    setBadge('模型加载中…', false);
    setReleaseHint(state.modelStaged
      ? '正在加载模型进内存…'
      : '正在释放内置模型（约 400MB，首次较慢）…');
    pollStart = 0;
    if (pollTimer) { clearTimeout(pollTimer); pollTimer = null; }
    call('loadModel').then(function () {
      pollStatus(0);
    }).catch(function (e) {
      releaseDone(false, e && e.message ? e.message : String(e));
    });
  }

  /** 统一收尾 */
  function releaseDone(ok, msg) {
    if (pollTimer) { clearTimeout(pollTimer); pollTimer = null; }
    pollStart = 0;
    if (ok) {
      state.modelReady = true;
      setBadge('本地模型就绪', true);
      setReleaseHint('');
      var b = $('btnRelease'); if (b) b.classList.add('hidden');
    } else {
      state.modelReady = false;
      setBadge('模型未就绪', false);
      setReleaseHint('加载失败：' + msg + '（可直接重试，无需重装）');
      var r = $('btnRelease'); if (r) { r.classList.remove('hidden'); r.textContent = '重试'; }
    }
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

    // Agent 可用或有本地模型就放行。只有两者都没有才拦。
    // （规格书：OpenClaw 是手，本地模型是断网兜底。
    //   之前只看 modelReady，结果 Agent 已就绪还拦着人不让发——道友 2026-11-21 报的“答不上新闻”就是这个根源）
    if (!state.agentReady && !state.modelReady) {
      pushError('正在连接…若持续失败，请先在设置里查看网关状态');
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

    // 计时器：让用户看到在干活
    var timer = setInterval(function () {
      var s = Math.round((Date.now() - t0) / 1000);
      bubble.textContent = (state.agentReady ? '处理中（可能正在调用工具）… ' : '思考中… ') + s + 's';
    }, 500);

    var payload = JSON.stringify({ messages: state.messages.slice(-12) });

    // 关键：call() 返回的是 Promise，必须 .then 拿值。
    // 之前写成 String(call(...)) 会把 Promise 对象转成 "[object Promise]"。
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
          bubble.textContent = errMsg
            ? ('生成失败：' + errMsg)
            : '这次没生成出正文。再发一次试试。';
          bubble.parentElement.classList.add('msg-error');
        } else {
          bubble.textContent = answer;
          state.messages.push({ role: 'assistant', content: answer });
          saveSession();
          // 每轮结束后重测模式：网关可能直上直下
          call('mode').then(function (m) {
            state.agentReady = (String(m) === 'agent');
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
        (info.name || 'Qwen3-1.7B') + ' · 本地运行',
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

    // 崩溃诊断
    var diag = document.createElement('div');
    diag.className = 'card';
    var dt = document.createElement('div');
    dt.className = 'card-title';
    dt.textContent = '崩溃日志';
    diag.appendChild(dt);

    var dd = document.createElement('div');
    dd.className = 'card-desc';
    dd.textContent = '如果应用闪退过，这里能看到原因';
    diag.appendChild(dd);

    var out = document.createElement('pre');
    out.style.cssText = 'margin-top:10px;padding:10px;background:var(--bg);border-radius:8px;font-size:11px;line-height:1.5;max-height:240px;overflow:auto;white-space:pre-wrap;word-break:break-all;display:none;';
    diag.appendChild(out);

    var brow = document.createElement('div');
    brow.style.cssText = 'display:flex;gap:8px;margin-top:10px;';

    var bShow = document.createElement('button');
    bShow.className = 'btn-primary';
    bShow.style.cssText = 'padding:8px 16px;font-size:13px;';
    bShow.textContent = '查看';
    bShow.onclick = function () {
      call('lastStage').then(function (st) {
        call('crashLog').then(function (log) {
          call('memInfo').then(function (mem) {
            var txt = '最后阶段: ' + (st || '(无)') + '\n\n内存: ' + (mem || '?') + '\n\n' + (log || '(无崩溃记录)');
            out.textContent = txt;
            out.style.display = '';
          }).catch(function () {
            out.textContent = '最后阶段: ' + (st || '(无)') + '\n\n' + (log || '(无崩溃记录)');
            out.style.display = '';
          });
        });
      });
    };

    var bCopy = document.createElement('button');
    bCopy.className = 'btn-primary';
    bCopy.style.cssText = 'padding:8px 16px;font-size:13px;background:var(--bg-3);';
    bCopy.textContent = '复制';
    bCopy.onclick = function () {
      if (!out.textContent) { bShow.onclick(); setTimeout(bCopy.onclick, 400); return; }
      var ta = document.createElement('textarea');
      ta.value = out.textContent;
      document.body.appendChild(ta);
      ta.select();
      try { document.execCommand('copy'); bCopy.textContent = '已复制'; } catch (e) {}
      document.body.removeChild(ta);
      setTimeout(function () { bCopy.textContent = '复制'; }, 1500);
    };

    brow.appendChild(bShow);
    brow.appendChild(bCopy);
    diag.appendChild(brow);
    subContent.appendChild(diag);
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

  // 主界面的「释放内置模型」按钮
  var releaseBtn = $('btnRelease');
  if (releaseBtn) releaseBtn.onclick = function () { loadModel(); };

  // ---------- 启动 ----------
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
