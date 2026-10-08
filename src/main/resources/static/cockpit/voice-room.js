(() => {
  "use strict";

  const Core = globalThis.MinikunCore;

  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
  const stage = $('#avatar-stage');
  const stageState = $('#stage-state');
  const stageTitle = $('#stage-title');
  const stageCaption = $('#stage-caption');
  const motionLabel = $('#motion-label');
  const live2dRuntimeLabel = $('#live2d-runtime-label');
  const live2dCanvas = $('#live2d-canvas');
  const live2dFallback = $('.live2d-fallback');
  const speechBubbleText = $('#speech-bubble-text');
  const bubbleState = $('#bubble-state');
  const bubbleContext = $('#bubble-context');
  const voiceLiveText = $('#voice-live-text');
  const waveTime = $('#wave-time');
  const latency = $('#latency');
  const deckStatus = $('#deck-status');
  const syncStatus = $('#sync-status');
  const syncTurns = $('#sync-turns');
  const queueCount = $('#queue-count');
  const queueList = $('.queue-list');
  const stageControls = $('.stage-controls');
  const input = $('#message-input');
  const messageForm = $('#message-form');
  const sendButton = $('.send-button', messageForm);
  const quickTalk = $('#quick-talk');
  const micButton = $('#mic-button');
  const conversationMode = $('#conversation-mode');
  const conversationModeLabel = $('#conversation-mode-label');
  const conversationModeHint = $('#conversation-mode-hint');
  const speed = $('#speed');
  const speedValue = $('#speed-value');
  const naturalPause = $('#natural-pause');
  const stopSpeaking = $('#stop-speaking');
  const cancelTurnButton = $('#cancel-turn');
  const toast = $('#toast');

  const recordingPolicy = Object.freeze({
    silenceMs: 850,
    minSpeechMs: 180,
    maxSeconds: 90,
    noiseFloorWindowMs: 350,
    minimumLevel: 0.018
  });

  const state = {
    ownerId: sessionStorage.getItem('minikun.owner') || 'default',
    token: sessionStorage.getItem('minikun.token') || '',
    model: localStorage.getItem('minikun.model') || 'mini-kun',
    conversationId: '',
    messages: [],
    paired: false,
    busy: false,
    transcribing: false,
    transcriptionController: null,
    activeRequest: null,
    activeAudio: null,
    activeAudioUrl: '',
    speechToken: 0,
    speechStartedAt: 0,
    speechClock: null,
    recordingStartedAt: 0,
    recordingTimer: null,
    recordingHasSpeech: false,
    recordingLastSpeechAt: 0,
    recordingNoiseFloor: 0,
    recordingStopping: false,
    audioContext: null,
    audioSource: null,
    audioProcessor: null,
    mediaStream: null,
    recordingChunks: [],
    syncTimer: null,
    toastTimer: null,
    selectedPreset: localStorage.getItem('minikun.voice-preset') || 'Warm companion',
    speechQueue: [],
    speechCurrent: null,
    speechWorker: null,
    speechWaiter: null,
    speechDone: false,
    speechChunker: null,
    speechControllers: new Set(),
    speechAudioContext: null,
    speechAudioSource: null,
    speechAnalyser: null,
    speechAnalyserData: null,
    speechAnalyserConnected: false,
    speechMetrics: null,
    lastSpeechMetrics: null,
    turnSerial: 0,
    activeTurnSerial: 0
  };

  const defaultBubble = 'พร้อมคุยครับพี่สาว วันนี้อยากให้มินิคุงช่วยคิด วางแผน หรืออยู่เป็นเพื่อนแบบไหนก่อนดี?';
  let lastSpeech = defaultBubble;

  const states = {
    ready: { status: 'พร้อมฟัง', title: 'พร้อมฟังครับ', wave: 'รอข้อความ', deck: 'READY' },
    listening: { status: 'กำลังฟัง', title: 'เล่ามาได้เลยครับ', wave: 'รับเสียงของเราอยู่', deck: 'LISTENING' },
    thinking: { status: 'กำลังคิด', title: 'ขอเรียบเรียงแป๊บหนึ่ง', wave: 'เตรียมจังหวะตอบกลับ', deck: 'THINKING' },
    speaking: { status: 'กำลังพูด', title: 'มินิคุงกำลังเล่าให้ฟัง', wave: 'เสียงตอบกลับกำลังไหล', deck: 'PLAYING' },
    error: { status: 'เชื่อมต่อสะดุด', title: 'ขอเช็กการเชื่อมต่อก่อนครับ', wave: 'ลองส่งข้อความอีกครั้ง', deck: 'ERROR' }
  };

  const voiceProfiles = {
    'Warm companion': { speed: 1.05, pauseMs: 180 },
    'Bright guide': { speed: 1.15, pauseMs: 90 },
    'Focus mode': { speed: 1.10, pauseMs: 140 }
  };

  const live2dRig = {
    canvas: live2dCanvas,
    ctx: live2dCanvas?.getContext('2d'),
    image: new Image(),
    frame: 0,
    nextBlink: 0,
    blinkStarted: 0,
    mouthLevel: 0
  };

  function uniqueId(prefix = '') { return Core.uniqueId(prefix); }

  function validConversationId(value) {
    return /^[a-zA-Z0-9._:-]{1,200}$/.test(String(value || '').trim());
  }

  function textContent(value) { return Core.textContent(value); }

  function normalizeMessage(value) { return Core.normalizeMessage(value); }

  function loadLocalConversations() { return Core.loadConversations(); }

  function conversationTitle(seed) { return Core.conversationTitle(seed); }

  function setConversationUrl(replace = true) {
    const query = new URLSearchParams(window.location.search);
    query.set('conversation_id', state.conversationId);
    const next = `${window.location.pathname}?${query}${window.location.hash}`;
    if (`${window.location.pathname}${window.location.search}${window.location.hash}` === next) return;
    window.history[replace ? 'replaceState' : 'pushState']({}, '', next);
  }

  function selectConversation() {
    const values = loadLocalConversations();
    const requested = new URLSearchParams(window.location.search).get('conversation_id');
    const remembered = localStorage.getItem('minikun.voice-conversation');
    const selectedId = validConversationId(requested) ? requested
      : validConversationId(remembered) ? remembered : values[0]?.id;
    state.conversationId = selectedId || uniqueId('web-');
    const current = values.find((value) => value.id === state.conversationId);
    state.messages = Array.isArray(current?.messages) ? current.messages.map(normalizeMessage).slice(-80) : [];
    localStorage.setItem('minikun.voice-conversation', state.conversationId);
    if (!requested) setConversationUrl();
  }

  function authHeaders(json = false) { return Core.authHeaders(state.token, json); }

  function withOwner(path) { return Core.withOwner(path, state.ownerId); }

  async function readResponse(response) { return Core.readResponse(response); }

  async function api(path, options = {}) {
    return Core.request(path, options, { ownerId: state.ownerId, token: state.token });
  }

  async function syncApi(path, options = {}) {
    return Core.syncRequest(path, options, { token: state.token });
  }

  function showToast(message, error = false) {
    clearTimeout(state.toastTimer);
    toast.textContent = message;
    toast.dataset.state = error ? 'error' : 'default';
    toast.classList.add('is-visible');
    state.toastTimer = setTimeout(() => toast.classList.remove('is-visible'), 3000);
  }

  function reducedMotion() {
    return Boolean(window.matchMedia?.('(prefers-reduced-motion: reduce)').matches);
  }

  function live2dAudioLevel(now) {
    if (state.speechAnalyser && state.speechAnalyserData) {
      state.speechAnalyser.getByteTimeDomainData(state.speechAnalyserData);
      let total = 0;
      for (const sample of state.speechAnalyserData) total += Math.abs(sample - 128);
      return Math.min(1, total / state.speechAnalyserData.length / 24);
    }
    return .18 + (.5 + .5 * Math.sin(now / 1000 * 11)) * .24;
  }

  function drawLive2dMouth(ctx, width, height, level) {
    if (level < .04) return;
    const x = width * .5;
    const y = height * .73;
    const coverX = width * .064;
    const coverY = height * .057;
    const cover = ctx.createRadialGradient(x, y - coverY * .35, 0, x, y, coverX * 1.3);
    cover.addColorStop(0, `rgba(255, 213, 196, ${.84 + level * .12})`);
    cover.addColorStop(1, 'rgba(239, 173, 161, .16)');
    ctx.save();
    ctx.fillStyle = cover;
    ctx.beginPath();
    ctx.ellipse(x, y, coverX, coverY, 0, 0, Math.PI * 2);
    ctx.fill();
    const openingY = height * (.009 + level * .035);
    ctx.fillStyle = '#5d2037';
    ctx.beginPath();
    ctx.ellipse(x, y, width * .052, openingY, 0, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = 'rgba(232, 83, 91, .9)';
    ctx.beginPath();
    ctx.ellipse(x, y + openingY * .38, width * .026, Math.max(2, openingY * .42), 0, 0, Math.PI * 2);
    ctx.fill();
    ctx.restore();
  }

  function drawLive2dBlink(ctx, width, height, progress) {
    if (progress <= .02) return;
    const skin = 'rgba(255, 210, 194, .9)';
    const eyes = [width * .32, width * .68];
    ctx.save();
    ctx.globalAlpha = progress;
    ctx.fillStyle = skin;
    for (const x of eyes) {
      ctx.beginPath();
      ctx.ellipse(x, height * .51, width * .105, height * .055, 0, 0, Math.PI * 2);
      ctx.fill();
      ctx.beginPath();
      ctx.moveTo(x - width * .085, height * .51);
      ctx.quadraticCurveTo(x, height * (.51 + progress * .018), x + width * .085, height * .51);
      ctx.strokeStyle = '#2d2930';
      ctx.lineWidth = Math.max(1.5, width * .008);
      ctx.stroke();
    }
    ctx.restore();
  }

  function drawLive2d(now) {
    live2dRig.frame = 0;
    if (document.hidden) return;
    const ctx = live2dRig.ctx;
    const image = live2dRig.image;
    if (!ctx || !image.complete || !image.naturalWidth) {
      live2dRig.frame = requestAnimationFrame(drawLive2d);
      return;
    }
    const width = live2dRig.canvas.width;
    const height = live2dRig.canvas.height;
    const motionReduced = reducedMotion();
    const isSpeaking = stage.dataset.state === 'speaking' && Boolean(state.speechCurrent);
    const targetMouth = isSpeaking && !motionReduced ? .16 + live2dAudioLevel(now) * .68 : 0;
    live2dRig.mouthLevel += (targetMouth - live2dRig.mouthLevel) * (motionReduced ? .35 : .16);
    if (!live2dRig.nextBlink) live2dRig.nextBlink = now + 2600;
    if (!motionReduced && !live2dRig.blinkStarted && now >= live2dRig.nextBlink) live2dRig.blinkStarted = now;
    let blinkProgress = 0;
    if (live2dRig.blinkStarted) {
      const elapsed = now - live2dRig.blinkStarted;
      if (elapsed < 190) blinkProgress = Math.sin((elapsed / 190) * Math.PI);
      else {
        live2dRig.blinkStarted = 0;
        live2dRig.nextBlink = now + 2600 + Math.random() * 2600;
      }
    }
    const seconds = now / 1000;
    const breath = motionReduced ? 0 : Math.sin(seconds * 1.45) * .006;
    const stateTilt = stage.dataset.state === 'listening' ? -.018 : stage.dataset.state === 'thinking' ? .014 : 0;
    const tilt = stateTilt + (motionReduced ? 0 : Math.sin(seconds * (isSpeaking ? 2.2 : .75)) * (isSpeaking ? .012 : .006));
    ctx.clearRect(0, 0, width, height);
    ctx.save();
    ctx.translate(width / 2, height * .96);
    ctx.rotate(tilt);
    ctx.scale(1 + breath, 1 + breath * .75);
    ctx.translate(-width / 2, -height * .96);
    ctx.drawImage(image, 0, 0, width, height);
    ctx.restore();
    drawLive2dMouth(ctx, width, height, live2dRig.mouthLevel);
    drawLive2dBlink(ctx, width, height, blinkProgress);
    live2dRig.frame = requestAnimationFrame(drawLive2d);
  }

  function initializeLive2d() {
    if (!live2dRig.ctx || !live2dCanvas) {
      if (live2dCanvas) live2dCanvas.hidden = true;
      if (live2dFallback) live2dFallback.style.display = 'block';
      return;
    }
    live2dRig.image.decoding = 'async';
    live2dRig.image.onload = () => { if (live2dFallback) live2dFallback.style.display = 'none'; };
    live2dRig.image.onerror = () => {
      live2dCanvas.hidden = true;
      if (live2dFallback) live2dFallback.style.display = 'block';
      if (live2dRuntimeLabel) live2dRuntimeLabel.textContent = 'STATIC FALLBACK';
    };
    live2dRig.image.src = '/cockpit/minikun-avatar.jpg';
    live2dRig.nextBlink = performance.now() + 2600;
    live2dRig.frame = requestAnimationFrame(drawLive2d);
  }

  document.addEventListener('visibilitychange', () => {
    if (document.hidden) {
      cancelAnimationFrame(live2dRig.frame);
      live2dRig.frame = 0;
    } else if (!live2dRig.frame) {
      live2dRig.frame = requestAnimationFrame(drawLive2d);
    }
  });

  function setStage(nextState, caption) {
    const copy = states[nextState] || states.ready;
    stage.dataset.state = nextState;
    stageState.textContent = copy.status;
    stageTitle.textContent = copy.title;
    stageCaption.textContent = caption || copy.wave;
    motionLabel.textContent = nextState === 'speaking' ? 'LIP SYNC' : nextState.toUpperCase();
    bubbleState.textContent = nextState === 'speaking' ? 'NOW SPEAKING' : nextState.toUpperCase();
    bubbleContext.textContent = nextState === 'speaking' ? 'natural pause กำลังทำงาน' : copy.wave;
    deckStatus.textContent = copy.deck;
    if (live2dRuntimeLabel) {
      live2dRuntimeLabel.textContent = {
        ready: 'IDLE RIG', listening: 'ATTENTION POSE', thinking: 'THINKING POSE',
        speaking: 'LIP-SYNC ACTIVE', error: 'RECOVERY POSE'
      }[nextState] || 'IDLE RIG';
    }
    stage.setAttribute('aria-busy', String(['listening', 'thinking', 'speaking'].includes(nextState)));
    updateControls();
  }

  function setBubble(text) {
    const value = String(text || '').trim() || '…';
    lastSpeech = value;
    speechBubbleText.textContent = value;
    voiceLiveText.textContent = value;
  }

  function resizeInput() {
    input.style.height = 'auto';
    const height = Math.min(input.scrollHeight, 130);
    input.style.height = `${height}px`;
    input.style.overflowY = input.scrollHeight > height ? 'auto' : 'hidden';
  }

  function liveConversationEnabled() {
    return conversationMode?.getAttribute('aria-pressed') === 'true';
  }

  function setConversationMode(enabled, announce = false) {
    const value = Boolean(enabled);
    conversationMode.setAttribute('aria-pressed', String(value));
    conversationModeLabel.textContent = value ? 'คุยสด' : 'พูดเพื่อพิมพ์';
    conversationModeHint.textContent = value
      ? 'พูดจบแล้วส่งให้อัตโนมัติ'
      : 'พูดจบแล้วตรวจข้อความก่อนส่ง';
    input.placeholder = value ? 'หรือพิมพ์เพื่อส่ง…' : 'พูดอะไรกับมินิคุงก็ได้…';
    localStorage.setItem('minikun.voice-mode', value ? 'conversation' : 'dictation');
    if (announce) showToast(value ? 'เปิดโหมดคุยสดแล้วครับ' : 'เปลี่ยนเป็นโหมดพูดเพื่อพิมพ์แล้วครับ');
  }

  function updateControls() {
    sendButton.disabled = state.busy || state.transcribing;
    quickTalk.disabled = state.busy || state.transcribing;
    micButton.disabled = state.transcribing;
    stopSpeaking.disabled = !state.speechCurrent;
    const cancellable = state.busy || state.transcribing;
    stageControls.classList.toggle('has-cancel', cancellable);
    cancelTurnButton.hidden = !cancellable;
    cancelTurnButton.disabled = !cancellable;
    cancelTurnButton.setAttribute('aria-label', state.transcribing ? 'ยกเลิกการถอดเสียง' : 'ยกเลิก turn');
    const label = $('span:last-child', cancelTurnButton);
    if (label) label.textContent = state.transcribing ? 'ยกเลิกการถอดเสียง' : 'ยกเลิก turn';
  }

  function updateConversationCount() {
    const turns = state.messages.filter((message) => message.role === 'user').length;
    syncTurns.textContent = `${turns} turn${turns === 1 ? '' : 's'} พร้อมซิงก์`;
  }

  function renderQueue(items = null) {
    const values = items === null ? [
      state.speechCurrent && { label: state.speechCurrent.text, state: 'now' },
      ...state.speechQueue.slice(0, 2).map((item, index) => ({
        label: item.text,
        state: index === 0 ? 'next' : `+${index}`
      }))
    ].filter(Boolean) : items.filter(Boolean);
    queueCount.textContent = `${values.length} block${values.length === 1 ? '' : 's'}`;
    queueList.replaceChildren();
    if (!values.length) {
      const empty = document.createElement('li');
      empty.innerHTML = '<span>ยังไม่มีเสียงในคิว</span><small>idle</small>';
      queueList.append(empty);
      return;
    }
    values.forEach((item, index) => {
      const row = document.createElement('li');
      if (index === 0) row.classList.add('current');
      const label = document.createElement('span');
      label.textContent = item.label;
      const stateLabel = document.createElement('small');
      stateLabel.textContent = item.state;
      row.append(label, stateLabel);
      queueList.append(row);
    });
  }

  function localPayload() {
    const values = loadLocalConversations();
    const existing = values.find((value) => value.id === state.conversationId) || {};
    return {
      ...existing,
      id: state.conversationId,
      title: existing.title && existing.title !== 'แชตใหม่'
        ? existing.title : conversationTitle(state.messages.find((message) => message.role === 'user')?.content),
      pinned: Boolean(existing.pinned),
      archived: Boolean(existing.archived),
      updatedAt: existing.updatedAt || Date.now(),
      messages: state.messages.slice(-80)
    };
  }

  function syncPayload() {
    return Core.syncPayload(localPayload());
  }

  function pendingSyncIds() {
    try {
      const values = JSON.parse(localStorage.getItem('minikun.sync-pending') || '[]');
      return Array.isArray(values) ? values : [];
    } catch (_) { return []; }
  }

  function saveLocal(seed = '') {
    const values = loadLocalConversations().filter((value) => value.id !== state.conversationId);
    const conversation = localPayload();
    if (seed && conversation.title === 'แชตใหม่') conversation.title = conversationTitle(seed);
    conversation.updatedAt = Date.now();
    values.unshift(conversation);
    try { localStorage.setItem('minikun.conversations', JSON.stringify(values.slice(0, 40))); } catch (_) { /* local history is best effort */ }
    try {
      localStorage.setItem('minikun.sync-pending', JSON.stringify([...new Set([
        ...pendingSyncIds(), state.conversationId
      ])]));
    } catch (_) { syncStatus.textContent = 'RETRY'; }
    localStorage.setItem('minikun.voice-conversation', state.conversationId);
    updateConversationCount();
    scheduleSync();
  }

  function scheduleSync() {
    if (!state.paired) return;
    clearTimeout(state.syncTimer);
    state.syncTimer = setTimeout(() => syncConversation(), 240);
  }

  async function syncConversation() {
    if (!state.paired) return false;
    syncStatus.textContent = 'SYNCING';
    try {
      const payload = syncPayload();
      await syncApi(`/v1/sync/conversations/${encodeURIComponent(state.conversationId)}`, {
        method: 'PUT', json: payload
      });
      if (JSON.stringify(syncPayload()) !== JSON.stringify(payload)) {
        scheduleSync();
        return false;
      }
      localStorage.setItem('minikun.sync-pending', JSON.stringify(
        pendingSyncIds().filter((id) => id !== state.conversationId)));
      syncStatus.textContent = 'SYNCED';
      return true;
    } catch (error) {
      syncStatus.textContent = 'RETRY';
      return false;
    }
  }

  function useRemoteConversation(value) {
    if (!value?.id) return;
    state.conversationId = String(value.id);
    state.messages = Array.isArray(value.messages) ? value.messages.map(normalizeMessage).slice(-80) : [];
    localStorage.setItem('minikun.voice-conversation', state.conversationId);
    setConversationUrl();
    try {
      const values = loadLocalConversations().filter((item) => item.id !== state.conversationId);
      localStorage.setItem('minikun.conversations', JSON.stringify([{ ...value, messages: state.messages }, ...values].slice(0, 40)));
    } catch (_) { /* keep the remote copy usable for this session */ }
    updateConversationCount();
    const latest = [...state.messages].reverse().find((message) => message.role === 'assistant' && message.content);
    if (latest) setBubble(latest.content);
  }

  async function initializeSync() {
    if (window.location.protocol !== 'https:') {
      syncStatus.textContent = 'LOCAL';
      return;
    }
    try {
      const session = await syncApi('/v1/sync/session', { method: 'POST', json: { deviceName: 'Voice Room' } });
      state.ownerId = session.ownerId || state.ownerId;
      sessionStorage.setItem('minikun.owner', state.ownerId);
      if (!session.paired) {
        syncStatus.textContent = 'PAIR';
        return;
      }
      state.paired = true;
      if (pendingSyncIds().includes(state.conversationId) && !await syncConversation()) return;
      const remote = await syncApi('/v1/sync/conversations');
      const requested = new URLSearchParams(window.location.search).get('conversation_id');
      const remoteCurrent = Array.isArray(remote) && remote.find((value) => value.id === state.conversationId);
      const remoteFallback = !requested && !state.messages.length && Array.isArray(remote) ? remote[0] : null;
      if (remoteCurrent || remoteFallback) useRemoteConversation(remoteCurrent || remoteFallback);
      syncStatus.textContent = 'SYNCED';
      updateConversationCount();
    } catch (_) {
      state.paired = false;
      syncStatus.textContent = 'LOCAL';
    }
  }

  function speechText(value) {
    return textContent(value)
      .replace(/```[\s\S]*?(?:```|$)/g, ' ข้ามส่วนโค้ด ')
      .replace(/`([^`]+)`/g, '$1')
      .replace(/!\[[^\]]*\]\([^)]*\)/g, '')
      .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
      .replace(/https?:\/\/\S+/g, ' ลิงก์ ')
      .replace(/`+/g, ' ')
      .replace(/[*_>#|~-]+/g, ' ')
      .replace(/\s+/g, ' ')
      .trim();
  }

  function isAppleMobile() {
    return /iPad|iPhone|iPod/.test(navigator.userAgent)
      || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  }

  function selectedTtsProfile() {
    const profile = voiceProfiles[state.selectedPreset] || voiceProfiles['Warm companion'];
    const currentSpeed = Number(speed.value);
    return {
      speed: Number.isFinite(currentSpeed) ? currentSpeed : profile.speed,
      pauseMs: naturalPause.getAttribute('aria-pressed') === 'true' ? profile.pauseMs : 0
    };
  }

  function isSpeechRun(token) {
    return token === state.speechToken;
  }

  function markSpeechMetric(name) {
    const metrics = state.speechMetrics;
    if (!metrics || metrics[name] !== null && metrics[name] !== undefined) return;
    metrics[name] = Math.round(performance.now() - metrics.startedAt);
  }

  function markSpeechPlayed() {
    const metrics = state.speechMetrics;
    const now = performance.now();
    if (!metrics) return;
    if (!metrics.firstAudioPlayedAt) {
      metrics.firstAudioPlayedAt = now;
      markSpeechMetric('firstAudioPlayedMs');
    }
    if (metrics.lastAudioEndedAt) metrics.maxGapMs = Math.max(metrics.maxGapMs, Math.round(now - metrics.lastAudioEndedAt));
  }

  function speechMetricsSnapshot() {
    const metrics = state.speechMetrics;
    if (!metrics) return null;
    const now = performance.now();
    const snapshot = {
      firstTokenMs: metrics.firstTokenMs,
      firstSegmentQueuedMs: metrics.firstSegmentQueuedMs,
      firstAudioReadyMs: metrics.firstAudioReadyMs,
      firstAudioPlayedMs: metrics.firstAudioPlayedMs,
      audioGapMs: metrics.maxGapMs,
      ttsDurationMs: metrics.firstAudioPlayedAt ? Math.max(0, Math.round(now - metrics.firstAudioPlayedAt)) : 0,
      segments: metrics.segments,
      queueDepth: metrics.queueMax,
      fallbacks: metrics.fallbacks,
      stopped: Boolean(metrics.stopped)
    };
    state.lastSpeechMetrics = snapshot;
    return snapshot;
  }

  function startSpeechClock() {
    if (state.speechClock) return;
    state.speechStartedAt = Date.now();
    state.speechClock = setInterval(() => {
      const elapsed = Math.floor((Date.now() - state.speechStartedAt) / 1000);
      waveTime.textContent = `00:${String(elapsed).padStart(2, '0')}`;
    }, 250);
  }

  function stopSpeech(nextState = 'ready', caption) {
    if (state.speechMetrics) {
      state.speechMetrics.stopped = true;
      speechMetricsSnapshot();
    }
    state.speechToken += 1;
    state.speechDone = true;
    state.speechChunker = null;
    state.speechWaiter?.();
    state.speechWaiter = null;
    state.speechControllers.forEach((controller) => controller.abort());
    state.speechControllers.clear();
    releaseSpeechAnalyser();
    state.speechQueue.splice(0).forEach((entry) => {
      if (entry.url) URL.revokeObjectURL(entry.url);
      entry.url = '';
    });
    if (state.speechCurrent?.url) {
      URL.revokeObjectURL(state.speechCurrent.url);
      state.speechCurrent.url = '';
    }
    state.speechCurrent = null;
    state.speechWorker = null;
    clearInterval(state.speechClock);
    state.speechClock = null;
    if ('speechSynthesis' in window) window.speechSynthesis.cancel();
    if (state.activeAudio) {
      state.activeAudio.pause();
      state.activeAudio.removeAttribute('src');
      state.activeAudio.load();
      state.activeAudio = null;
    }
    if (state.activeAudioUrl) {
      URL.revokeObjectURL(state.activeAudioUrl);
      state.activeAudioUrl = '';
    }
    stopSpeaking.disabled = true;
    waveTime.textContent = '00:00';
    renderQueue();
    setStage(nextState, caption);
  }

  function createSpeechChunker(onSegment) {
    let pending = '';
    const maxLength = 180;
    const boundary = (value) => {
      const match = /[。！？!?]+(?=\s|$)|\.(?=\s|$)|\n/.exec(value);
      return match ? match.index + match[0].length : 0;
    };
    const emit = (value) => {
      const clean = speechText(value);
      if (clean) onSegment(clean);
    };
    return (delta = '', flush = false) => {
      pending += String(delta || '');
      while (pending) {
        if (flush) {
          emit(pending);
          pending = '';
          break;
        }
        const end = boundary(pending);
        if (end > 0) {
          emit(pending.slice(0, end));
          pending = pending.slice(end);
          continue;
        }
        if (pending.length < maxLength) break;
        const window = pending.slice(0, maxLength + 1);
        const whitespace = window.lastIndexOf(' ');
        const splitAt = whitespace > Math.floor(maxLength * 0.55) ? whitespace : maxLength;
        emit(pending.slice(0, splitAt));
        pending = pending.slice(splitAt);
      }
    };
  }

  function enqueueSpeechSegment(text, token = state.speechToken) {
    const value = speechText(text);
    if (!value || !isSpeechRun(token)) return;
    const entry = { id: uniqueId('speech-'), text: value, mode: '', url: '', preparePromise: null, fallbacked: false };
    state.speechQueue.push(entry);
    const metrics = state.speechMetrics;
    if (metrics) {
      metrics.segments += 1;
      metrics.queueMax = Math.max(metrics.queueMax, state.speechQueue.length + (state.speechCurrent ? 1 : 0));
      if (metrics.firstSegmentQueuedMs === null) metrics.firstSegmentQueuedMs = Math.round(performance.now() - metrics.startedAt);
    }
    stopSpeaking.disabled = false;
    renderQueue();
    state.speechWaiter?.();
    state.speechWaiter = null;
  }

  function consumeSpeechText(delta, token, flush = false) {
    if (isSpeechRun(token)) state.speechChunker?.(delta, flush);
  }

  function beginSpeechQueue() {
    const token = ++state.speechToken;
    state.speechQueue = [];
    state.speechCurrent = null;
    state.speechDone = false;
    state.speechMetrics = {
      startedAt: performance.now(), firstTokenMs: null, firstSegmentQueuedMs: null,
      firstAudioReadyMs: null, firstAudioPlayedMs: null, firstAudioPlayedAt: 0,
      lastAudioEndedAt: 0, maxGapMs: 0, segments: 0, queueMax: 0, fallbacks: 0, stopped: false
    };
    state.speechChunker = createSpeechChunker((text) => enqueueSpeechSegment(text, token));
    state.speechWorker = runSpeechQueue(token);
    state.speechWorker.then(() => {
      if (isSpeechRun(token)) state.speechWorker = null;
    }, () => {
      if (isSpeechRun(token)) state.speechWorker = null;
    });
    return token;
  }

  function finishSpeechQueue(token) {
    if (!isSpeechRun(token)) return;
    state.speechChunker?.('', true);
    state.speechChunker = null;
    state.speechDone = true;
    state.speechWaiter?.();
    state.speechWaiter = null;
  }

  function waitForSpeechItem(token) {
    if (!isSpeechRun(token) || state.speechQueue.length || state.speechDone) return Promise.resolve();
    return new Promise((resolve) => { state.speechWaiter = resolve; });
  }

  async function waitForSpeechRun(token) {
    const worker = state.speechWorker;
    if (worker && isSpeechRun(token)) await worker;
  }

  function finishSpeechRun(token) {
    if (!isSpeechRun(token)) return;
    clearInterval(state.speechClock);
    state.speechClock = null;
    state.speechCurrent = null;
    stopSpeaking.disabled = true;
    waveTime.textContent = '00:00';
    renderQueue();
    speechMetricsSnapshot();
    setStage('ready', 'ถ้าอยากต่อ ลองเลือก preset หรือส่งข้อความใหม่ได้เลยครับ');
  }

  async function speakWithBrowser(text, token) {
    if (!('speechSynthesis' in window) || !('SpeechSynthesisUtterance' in window)) {
      return Promise.reject(new Error('เบราว์เซอร์นี้ยังไม่มีเสียงอ่าน'));
    }
    return new Promise((resolve, reject) => {
      const utterance = new SpeechSynthesisUtterance(text.slice(0, 4000));
      const voices = window.speechSynthesis.getVoices();
      const profile = selectedTtsProfile();
      utterance.voice = voices.find((voice) => /^th(-|_)/i.test(voice.lang)) || null;
      utterance.lang = 'th-TH';
      utterance.rate = profile.speed;
      utterance.pitch = 1.06;
      utterance.onend = () => resolve();
      utterance.onerror = (event) => reject(new Error(event.error === 'canceled' ? 'ยกเลิกเสียงแล้ว' : 'เสียงระบบเล่นไม่สำเร็จ'));
      window.speechSynthesis.speak(utterance);
      if (!isSpeechRun(token)) reject(new Error('ยกเลิกเสียงแล้ว'));
    });
  }

  async function connectSpeechAnalyser(audio) {
    const AudioContextClass = window.AudioContext || window.webkitAudioContext;
    if (!AudioContextClass) return;
    try {
      state.speechAudioContext ||= new AudioContextClass();
      await state.speechAudioContext.resume();
      state.speechAnalyser ||= state.speechAudioContext.createAnalyser();
      state.speechAnalyser.fftSize = 256;
      if (!state.speechAnalyserData) state.speechAnalyserData = new Uint8Array(state.speechAnalyser.fftSize);
      if (!state.speechAnalyserConnected) {
        state.speechAnalyser.connect(state.speechAudioContext.destination);
        state.speechAnalyserConnected = true;
      }
      state.speechAudioSource = state.speechAudioContext.createMediaElementSource(audio);
      state.speechAudioSource.connect(state.speechAnalyser);
    } catch (_) {
      releaseSpeechAnalyser();
    }
  }

  function releaseSpeechAnalyser() {
    try { state.speechAudioSource?.disconnect(); } catch (_) { /* audio source may already be detached */ }
    state.speechAudioSource = null;
  }

  async function fetchServerAudio(text, token) {
    const controller = new AbortController();
    state.speechControllers.add(controller);
    const response = await fetch(withOwner('/v1/audio/speech'), {
      method: 'POST',
      headers: authHeaders(true),
      signal: controller.signal,
      body: JSON.stringify({
        model: 'minikun-voice', input: text.slice(0, 1800), voice: 'minikun',
        response_format: 'wav', speed: selectedTtsProfile().speed
      })
    }).finally(() => state.speechControllers.delete(controller));
    if (!response.ok) {
      const body = await readResponse(response).catch(() => null);
      throw new Error(String(body?.message || body?.error?.message || body || `ระบบเสียงยังไม่พร้อม (${response.status})`));
    }
    const url = URL.createObjectURL(await response.blob());
    if (!isSpeechRun(token)) {
      URL.revokeObjectURL(url);
      throw new DOMException('ยกเลิกเสียงแล้ว', 'AbortError');
    }
    return url;
  }

  async function prepareSpeechEntry(entry, token) {
    if (!isSpeechRun(token)) throw new DOMException('ยกเลิกเสียงแล้ว', 'AbortError');
    if (isAppleMobile()) {
      entry.mode = 'browser';
      markSpeechMetric('firstAudioReadyMs');
      return;
    }
    try {
      entry.url = await fetchServerAudio(entry.text, token);
      entry.mode = 'server';
      markSpeechMetric('firstAudioReadyMs');
    } catch (error) {
      if (!isSpeechRun(token) || error.name === 'AbortError') throw error;
      entry.mode = 'browser';
      entry.fallbacked = true;
      if (state.speechMetrics) state.speechMetrics.fallbacks += 1;
      markSpeechMetric('firstAudioReadyMs');
    }
  }

  async function playSpeechEntry(entry, token) {
    if (!isSpeechRun(token)) throw new DOMException('ยกเลิกเสียงแล้ว', 'AbortError');
    setStage('speaking', 'กำลังพูดประโยคนี้ให้ฟังครับ');
    startSpeechClock();
    if (entry.mode === 'browser') {
      markSpeechPlayed();
      await speakWithBrowser(entry.text, token);
      if (state.speechMetrics) state.speechMetrics.lastAudioEndedAt = performance.now();
      return;
    }
    const audio = new Audio(entry.url);
    audio.preload = 'auto';
    audio.playsInline = true;
    state.activeAudio = audio;
    state.activeAudioUrl = entry.url;
    await connectSpeechAnalyser(audio);
    try {
      await new Promise((resolve, reject) => {
        let settled = false;
        const finish = () => {
          if (settled) return;
          settled = true;
          resolve();
        };
        const fail = () => {
          if (settled) return;
          settled = true;
          reject(new Error('เล่นเสียงจากเซิร์ฟเวอร์ไม่สำเร็จ'));
        };
        audio.addEventListener('ended', finish, { once: true });
        audio.addEventListener('error', fail, { once: true });
        Promise.resolve(audio.play()).then(() => {
          markSpeechPlayed();
        }).catch(fail);
      });
      if (state.speechMetrics) state.speechMetrics.lastAudioEndedAt = performance.now();
    } finally {
      if (state.activeAudio === audio) {
        state.activeAudio = null;
        state.activeAudioUrl = '';
      }
      audio.pause();
      audio.removeAttribute('src');
      audio.load();
      releaseSpeechAnalyser();
    }
  }

  async function runSpeechQueue(token) {
    try {
      while (isSpeechRun(token)) {
        if (!state.speechQueue.length) {
          if (state.speechDone) break;
          await waitForSpeechItem(token);
          continue;
        }
        const entry = state.speechQueue.shift();
        state.speechCurrent = entry;
        renderQueue();
        const next = state.speechQueue[0];
        if (next && !isAppleMobile() && !next.preparePromise) next.preparePromise = prepareSpeechEntry(next, token);
        try {
          entry.preparePromise ||= prepareSpeechEntry(entry, token);
          await entry.preparePromise;
          await playSpeechEntry(entry, token);
        } catch (error) {
          if (!isSpeechRun(token)) return;
          if (entry.mode === 'server' && !entry.fallbacked) {
            entry.mode = 'browser';
            entry.fallbacked = true;
            if (state.speechMetrics) state.speechMetrics.fallbacks += 1;
            try { await playSpeechEntry(entry, token); } catch (fallbackError) { showToast(fallbackError.message, true); }
          } else if (entry.mode === 'browser' && !entry.fallbacked && isAppleMobile() && error.name !== 'AbortError') {
            entry.mode = 'server';
            entry.fallbacked = true;
            if (state.speechMetrics) state.speechMetrics.fallbacks += 1;
            try {
              entry.url = await fetchServerAudio(entry.text, token);
              await playSpeechEntry(entry, token);
            } catch (fallbackError) {
              showToast(fallbackError.message, true);
            }
          } else if (error.name !== 'AbortError') {
            showToast(error.message, true);
          }
        } finally {
          if (entry.url) URL.revokeObjectURL(entry.url);
          entry.url = '';
          if (state.speechCurrent === entry) state.speechCurrent = null;
          renderQueue();
        }
        if (!isSpeechRun(token)) return;
        const pauseMs = selectedTtsProfile().pauseMs;
        if (pauseMs && (!state.speechDone || state.speechQueue.length)) {
          await new Promise((resolve) => setTimeout(resolve, pauseMs));
        }
        if (state.speechQueue.length) {
          renderQueue();
        } else if (!state.speechDone) {
          setStage('thinking', 'กำลังรอประโยคถัดไปครับ');
        }
      }
      if (isSpeechRun(token) && state.speechDone) finishSpeechRun(token);
    } catch (error) {
      if (isSpeechRun(token)) {
        showToast(error.message || 'ระบบเสียงหยุดทำงาน', true);
        finishSpeechRun(token);
      }
    }
  }

  async function speak(text) {
    if (state.busy || state.transcribing) return false;
    const value = speechText(text);
    if (!value) return false;
    stopSpeech('ready');
    const token = beginSpeechQueue();
    setBubble(text);
    setStage('thinking', 'กำลังเตรียมเสียงตอบกลับครับ');
    consumeSpeechText(text, token, true);
    finishSpeechQueue(token);
    await waitForSpeechRun(token);
    return isSpeechRun(token);
  }

  async function streamReply(speechToken) {
    const startedAt = Date.now();
    const controller = new AbortController();
    state.activeRequest = controller;
    const messages = state.messages
      .filter((message) => ['user', 'assistant'].includes(message.role) && message.content.trim())
      .slice(-40)
      .map((message) => ({ role: message.role, content: message.content }));
    const response = await fetch(withOwner('/v1/chat/completions'), {
      method: 'POST',
      headers: { ...authHeaders(true), 'X-Conversation-Id': state.conversationId },
      signal: controller.signal,
      body: JSON.stringify({
        model: state.model, messages, conversation_id: state.conversationId,
        owner_id: state.ownerId, stream: true, response_mode: 'voice'
      })
    });
    if (!response.ok) {
      const body = await readResponse(response).catch(() => null);
      throw new Error(String(body?.error?.message || body?.message || body || `เชื่อมต่อไม่สำเร็จ (${response.status})`));
    }
    const reader = response.body?.getReader();
    if (!reader) throw new Error('เบราว์เซอร์นี้ยังไม่รองรับคำตอบแบบ streaming');
    const decoder = new TextDecoder();
    let buffer = '';
    let content = '';
    let responseId = '';
    let firstTokenMs = 0;
    const consume = (line) => {
      const data = line.startsWith('data:') ? line.slice(5).trimStart() : line.trim();
      if (!data || data === '[DONE]' || !data.startsWith('{')) return;
      const chunk = JSON.parse(data);
      responseId = String(chunk.id || responseId);
      const choice = chunk.choices?.[0] || {};
      const delta = choice.delta?.content ?? choice.message?.content ?? '';
      if (delta) {
        if (!firstTokenMs) {
          firstTokenMs = Date.now() - startedAt;
          latency.textContent = `${firstTokenMs}ms`;
          if (state.speechMetrics) state.speechMetrics.firstTokenMs = firstTokenMs;
        }
        content += String(delta);
        if (!state.speechCurrent) setStage('thinking', 'มินิคุงกำลังเรียบเรียงคำตอบให้ฟังเป็นธรรมชาติ');
        setBubble(content);
        bubbleState.textContent = 'RECEIVING';
        bubbleContext.textContent = 'ข้อความกำลังมา';
        consumeSpeechText(String(delta), speechToken);
      }
    };
    while (true) {
      const { value, done } = await reader.read();
      buffer += decoder.decode(value || new Uint8Array(), { stream: !done });
      const lines = buffer.split(/\r?\n/);
      buffer = lines.pop() || '';
      for (const line of lines) consume(line);
      if (done) break;
    }
    if (buffer.trim()) consume(buffer);
    if (!content.trim()) throw new Error('มินิคุงยังไม่มีข้อความตอบกลับครับ');
    if (state.activeRequest === controller) state.activeRequest = null;
    return { content, responseId, firstTokenMs, totalMs: Date.now() - startedAt };
  }

  async function sendMessage(rawText) {
    const text = String(rawText || '').trim();
    if (!text || state.busy || state.transcribing) return;
    const turnSerial = ++state.turnSerial;
    state.activeTurnSerial = turnSerial;
    input.value = '';
    resizeInput();
    stopSpeech('ready');
    state.busy = true;
    updateControls();
    setStage('thinking', 'กำลังเชื่อมต่อกับมินิคุง…');
    setBubble('ขอเรียบเรียงคำตอบให้ฟังเป็นธรรมชาติก่อนนะครับ…');
    deckStatus.textContent = 'BUFFERING';
    const userMessage = { id: uniqueId('message-'), role: 'user', content: text, createdAt: Date.now(), status: 'complete' };
    state.messages.push(userMessage);
    saveLocal(text);
    const speechToken = beginSpeechQueue();
    try {
      const result = await streamReply(speechToken);
      finishSpeechQueue(speechToken);
      const assistantMessage = {
        id: uniqueId('message-'), role: 'assistant', content: result.content,
        createdAt: Date.now(), status: 'complete', responseId: result.responseId,
        timing: { firstTokenMs: result.firstTokenMs, totalMs: result.totalMs }
      };
      state.messages.push(assistantMessage);
      saveLocal(text);
      await waitForSpeechRun(speechToken);
      if (isSpeechRun(speechToken)) {
        assistantMessage.timing.tts = state.lastSpeechMetrics;
        saveLocal(text);
      }
    } catch (error) {
      finishSpeechQueue(speechToken);
      await waitForSpeechRun(speechToken);
      if (state.activeTurnSerial !== turnSerial) return;
      if (error.name === 'AbortError') {
        setBubble('หยุดคำตอบไว้ก่อนแล้วครับ');
        setStage('ready', 'พร้อมคุยต่อเมื่อพี่สาวต้องการครับ');
      } else {
        setBubble(`ขออภัยครับ ${error.message}`);
        setStage('error', 'ตรวจการเชื่อมต่อแล้วลองใหม่อีกครั้งครับ');
        showToast(error.message, true);
      }
    } finally {
      if (state.activeTurnSerial === turnSerial) {
        state.activeRequest = null;
        state.busy = false;
        updateControls();
      }
    }
  }

  function stopCurrent() {
    if (!state.speechCurrent) return;
    stopSpeech('ready', 'หยุดเสียงแล้วครับ — คำตอบยังอยู่บนหน้าจอ');
    updateControls();
  }

  function cancelTurn() {
    if (!state.busy && !state.transcribing) return;
    state.activeTurnSerial = ++state.turnSerial;
    state.activeRequest?.abort();
    state.transcriptionController?.abort();
    state.activeRequest = null;
    state.transcriptionController = null;
    stopSpeech('ready', 'ยกเลิก turn แล้วครับ พร้อมคุยต่อได้เลย');
    state.busy = false;
    state.transcribing = false;
    updateControls();
  }

  function wavBlob(chunks, sourceRate) { return Core.wavBlob(chunks, sourceRate); }

  async function transcribeAudio(blob) {
    const controller = new AbortController();
    state.transcribing = true;
    state.transcriptionController = controller;
    updateControls();
    setStage('thinking', 'กำลังถอดเสียงจากไมค์ครับ');
    setBubble('กำลังถอดเสียงอยู่ครับ…');
    try {
      const form = new FormData();
      form.append('file', new File([blob], 'minikun-recording.wav', { type: 'audio/wav' }));
      form.append('language', 'auto');
      const result = await api('/v1/audio/transcriptions', {
        method: 'POST', body: form, signal: controller.signal
      });
      const transcript = String(result?.text || result || '').trim();
      if (!transcript) throw new Error('ยังจับข้อความจากเสียงไม่ได้ครับ');
      if (liveConversationEnabled()) {
        state.transcribing = false;
        updateControls();
        setBubble('ได้ยินแล้วครับ กำลังตอบกลับให้ฟัง…');
        await sendMessage(transcript);
      } else {
        input.value = `${input.value}${input.value ? ' ' : ''}${transcript}`;
        resizeInput();
        input.focus();
        setBubble('ได้ยินแล้วครับ แก้ข้อความก่อนส่งได้เลย');
        setStage('ready', 'เติมข้อความจากไมค์ให้แล้ว แก้ก่อนส่งได้เลยครับ');
      }
    } catch (error) {
      if (error.name === 'AbortError') return;
      setStage('error', 'ตรวจไมโครโฟนหรือระบบถอดเสียงแล้วลองใหม่ครับ');
      showToast(error.message, true);
    } finally {
      if (state.transcriptionController === controller) state.transcriptionController = null;
      state.transcribing = false;
      updateControls();
    }
  }

  async function stopRecording() {
    if (state.recordingStopping) return;
    state.recordingStopping = true;
    clearInterval(state.recordingTimer);
    state.recordingTimer = null;
    micButton.classList.remove('is-recording');
    micButton.setAttribute('aria-pressed', 'false');
    micButton.setAttribute('aria-label', 'เปิดไมโครโฟน');
    state.audioProcessor?.disconnect();
    state.audioSource?.disconnect();
    state.mediaStream?.getTracks().forEach((track) => track.stop());
    const context = state.audioContext;
    const chunks = state.recordingChunks;
    state.audioProcessor = null;
    state.audioSource = null;
    state.mediaStream = null;
    state.audioContext = null;
    state.recordingChunks = [];
    state.recordingHasSpeech = false;
    state.recordingLastSpeechAt = 0;
    state.recordingNoiseFloor = 0;
    try {
      if (context) await context.close();
      if (chunks.length) await transcribeAudio(wavBlob(chunks, context?.sampleRate || 16000));
    } finally {
      state.recordingStopping = false;
      updateControls();
    }
  }

  function interruptForInput() {
    if (!state.busy && !state.speechCurrent) return;
    if (state.busy) {
      state.activeTurnSerial = ++state.turnSerial;
      state.activeRequest?.abort();
      state.activeRequest = null;
      state.busy = false;
    }
    stopSpeech('ready', 'หยุดเสียงแล้วครับ พูดแทรกได้เลย');
    updateControls();
  }

  async function toggleRecording() {
    if (state.audioContext) { await stopRecording(); return; }
    if (state.transcribing || state.recordingStopping) return;
    interruptForInput();
    if (!window.isSecureContext && !['localhost', '127.0.0.1'].includes(window.location.hostname)) {
      showToast('ต้องเปิดหน้าเว็บผ่าน HTTPS เพื่อใช้ไมโครโฟนครับ', true);
      return;
    }
    const AudioContextClass = window.AudioContext || window.webkitAudioContext;
    if (!navigator.mediaDevices?.getUserMedia || !AudioContextClass) {
      showToast('เบราว์เซอร์นี้ยังไม่รองรับการบันทึกเสียงครับ', true);
      return;
    }
    try {
      state.mediaStream = await navigator.mediaDevices.getUserMedia({ audio: true });
      state.audioContext = new AudioContextClass();
      await state.audioContext.resume();
      state.audioSource = state.audioContext.createMediaStreamSource(state.mediaStream);
      state.audioProcessor = state.audioContext.createScriptProcessor(4096, 1, 1);
      state.recordingChunks = [];
      state.audioProcessor.onaudioprocess = (event) => {
        const samples = event.inputBuffer.getChannelData(0);
        state.recordingChunks.push(new Float32Array(samples));
        event.outputBuffer.getChannelData(0).fill(0);
        let total = 0;
        for (const sample of samples) total += sample * sample;
        const level = Math.sqrt(total / samples.length);
        const now = Date.now();
        const elapsed = now - state.recordingStartedAt;
        if (elapsed <= recordingPolicy.noiseFloorWindowMs) {
          state.recordingNoiseFloor = state.recordingNoiseFloor
            ? state.recordingNoiseFloor * .8 + level * .2 : level;
        }
        const threshold = Math.max(recordingPolicy.minimumLevel, state.recordingNoiseFloor * 2.4);
        if (elapsed > recordingPolicy.noiseFloorWindowMs && level > threshold) {
          state.recordingHasSpeech = true;
          state.recordingLastSpeechAt = now;
        }
        if (liveConversationEnabled() && state.recordingHasSpeech
            && elapsed >= recordingPolicy.minSpeechMs
            && now - state.recordingLastSpeechAt >= recordingPolicy.silenceMs) {
          stopRecording();
        }
      };
      state.audioSource.connect(state.audioProcessor);
      state.audioProcessor.connect(state.audioContext.destination);
      state.recordingStartedAt = Date.now();
      micButton.classList.add('is-recording');
      micButton.setAttribute('aria-pressed', 'true');
      micButton.setAttribute('aria-label', 'หยุดบันทึกเสียง');
      setStage('listening', liveConversationEnabled()
        ? 'กำลังฟังอยู่ครับ พูดจบแล้วหยุดเอง' : 'กำลังฟังอยู่ครับ กดไมค์อีกครั้งเมื่อพูดจบ');
      setBubble('กำลังฟังอยู่ครับ เล่ามาได้เลย…');
      state.recordingTimer = setInterval(() => {
        const elapsed = Math.floor((Date.now() - state.recordingStartedAt) / 1000);
        waveTime.textContent = `00:${String(elapsed).padStart(2, '0')}`;
        if (elapsed >= recordingPolicy.maxSeconds) stopRecording();
      }, 250);
    } catch (_) {
      state.mediaStream?.getTracks().forEach((track) => track.stop());
      state.mediaStream = null;
      await state.audioContext?.close();
      state.audioContext = null;
      showToast('เปิดไมโครโฟนไม่สำเร็จ ตรวจ permission ของเบราว์เซอร์ครับ', true);
    }
  }

  function createNewRoom() {
    stopCurrent();
    state.conversationId = uniqueId('web-');
    state.messages = [];
    localStorage.setItem('minikun.voice-conversation', state.conversationId);
    setConversationUrl();
    latency.textContent = '—';
    lastSpeech = defaultBubble;
    setBubble(defaultBubble);
    setStage('ready');
    renderQueue();
    updateConversationCount();
    showToast('เริ่มห้องคุยใหม่แล้วครับ');
  }

  function openMain(view = 'chat') {
    const url = new URL('/cockpit/', window.location.origin);
    if (view === 'cockpit' || view === 'studio') url.searchParams.set('view', view);
    url.searchParams.set('conversation_id', state.conversationId);
    window.location.assign(`${url.pathname}${url.search}`);
  }

  function loadControls() {
    const defaultSpeed = 1.10;
    const storedSpeed = Number(localStorage.getItem('minikun.voice-speed'));
    speed.value = storedSpeed >= 0.75 && storedSpeed <= 1.25 ? storedSpeed.toFixed(2) : defaultSpeed.toFixed(2);
    speedValue.textContent = `${Number(speed.value).toFixed(2)}×`;
    const pauseEnabled = localStorage.getItem('minikun.voice-natural-pause') !== 'false';
    naturalPause.setAttribute('aria-pressed', String(pauseEnabled));
    naturalPause.setAttribute('aria-label', `${pauseEnabled ? 'ปิด' : 'เปิด'} natural pause`);
    $$('.preset').forEach((button) => button.classList.toggle('active', button.dataset.preset === state.selectedPreset));
    setConversationMode(localStorage.getItem('minikun.voice-mode') !== 'dictation');
  }

  function openVoiceSettings() {
    const deck = $('#voice-deck');
    if (deck) deck.open = true;
    const dialog = $('#voice-settings-dialog');
    if (dialog && !dialog.open) dialog.showModal();
  }

  selectConversation();
  $('#close-voice-settings').addEventListener('click', () => $('#voice-settings-dialog').close());
  loadControls();
  updateConversationCount();
  renderQueue();
  setStage('ready');
  initializeLive2d();
  const latest = [...state.messages].reverse().find((message) => message.role === 'assistant' && message.content);
  if (latest) { lastSpeech = latest.content; setBubble(latest.content); }
  resizeInput();
  updateControls();
  initializeSync();
  window.addEventListener('online', () => {
    if (state.paired && pendingSyncIds().includes(state.conversationId)) syncConversation();
  });

  messageForm.addEventListener('submit', (event) => { event.preventDefault(); sendMessage(input.value); });
  input.addEventListener('input', resizeInput);
  input.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      sendMessage(input.value);
    }
  });
  quickTalk.addEventListener('click', () => sendMessage('คืนนี้ช่วยวางแผนให้หน่อยนะ'));
  stopSpeaking.addEventListener('click', stopCurrent);
  cancelTurnButton.addEventListener('click', cancelTurn);
  $('#replay-current').addEventListener('click', () => speak(lastSpeech));
  $('#top-settings').addEventListener('click', openVoiceSettings);
  $('#rail-settings').addEventListener('click', openVoiceSettings);
  $('#new-room').addEventListener('click', createNewRoom);
  $('#open-chat-history').addEventListener('click', () => openMain('chat'));
  $('.brand').addEventListener('click', (event) => { event.preventDefault(); openMain('chat'); });
  $$('.nav-item').forEach((button) => button.addEventListener('click', () => {
    if (button.classList.contains('active')) return;
    openMain(button.dataset.view || 'chat');
  }));
  $$('.prompt-chip').forEach((button) => button.addEventListener('click', () => {
    input.value = button.dataset.prompt;
    input.focus();
    resizeInput();
  }));
  conversationMode.addEventListener('click', () => {
    setConversationMode(!liveConversationEnabled(), true);
  });
  $$('.preset').forEach((button) => button.addEventListener('click', () => {
    $$('.preset').forEach((item) => item.classList.remove('active'));
    button.classList.add('active');
    state.selectedPreset = button.dataset.preset;
    localStorage.setItem('minikun.voice-preset', state.selectedPreset);
    const profile = voiceProfiles[state.selectedPreset];
    if (profile) {
      speed.value = profile.speed.toFixed(2);
      speedValue.textContent = `${profile.speed.toFixed(2)}×`;
      localStorage.setItem('minikun.voice-speed', speed.value);
    }
    showToast(`บันทึกโทน ${button.dataset.preset} ไว้ใช้กับเสียงถัดไปแล้วครับ`);
    if (stage.dataset.state === 'ready') stageCaption.textContent = `${button.dataset.preset} พร้อมใช้กับคำตอบถัดไปครับ`;
  }));
  speed.addEventListener('input', () => {
    speedValue.textContent = `${Number(speed.value).toFixed(2)}×`;
    localStorage.setItem('minikun.voice-speed', speed.value);
  });
  naturalPause.addEventListener('click', (event) => {
    const enabled = event.currentTarget.getAttribute('aria-pressed') !== 'true';
    event.currentTarget.setAttribute('aria-pressed', String(enabled));
    event.currentTarget.setAttribute('aria-label', `${enabled ? 'ปิด' : 'เปิด'} natural pause`);
    localStorage.setItem('minikun.voice-natural-pause', String(enabled));
    showToast(enabled ? 'เปิด natural pause แล้ว' : 'ปิด natural pause แล้ว');
  });
  micButton.addEventListener('click', toggleRecording);
  window.addEventListener('beforeunload', () => {
    state.mediaStream?.getTracks().forEach((track) => track.stop());
    state.audioContext?.close();
    state.speechAudioContext?.close();
    state.activeRequest?.abort();
    stopSpeech();
  });
})();
