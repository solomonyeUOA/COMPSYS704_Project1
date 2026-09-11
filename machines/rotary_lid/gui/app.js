const $ = selector => document.querySelector(selector);
const statePill = $('#state-pill');
const lineMessage = $('#line-message');
const nextAction = $('#next-action');
const faultName = $('#fault-name');
const incidentContext = $('#incident-context');
const severity = $('#severity');
const severityIcon = $('#severity-icon');
const decision = $('#decision');
const policy = $('#policy');
const evidence = $('#evidence');
const attempt = $('#attempt');
const metrics = $('#metrics');
const trace = $('#trace');
const faultLab = $('#fault-lab');
const mode = $('#mode');
const blockedBottle = $('#blocked-bottle');
const processLine = $('#process-line');
const connection = $('#connection');
const actionResult = $('#action-result');
const systemClock = $('#system-clock');

const translations = {
  en: {
    appTitle: 'Fault-Tolerance Supervisor', supervisorDecision: 'SUPERVISOR DECISION',
    confirmSafeStop: 'Confirm safe stop', recordReconciliation: 'Record reconciliation',
    submitEvidence: 'Submit controller evidence', simulateResume: 'Simulate M1 resume', reset: 'Reset',
    liveProcess: 'MACHINE CELL 01 / LIVE PROCESS', processTitle: 'Bottle journey and machine state',
    stations: 'stations', domains: 'supervised domains', maxRetry: 'max retry',
    bottleLoader: 'Bottle loader', bottleLoaderDesc: 'Empty bottle admitted',
    bottleTransfer: 'Bottle transfer', bottleTransferDesc: 'Arrival and position',
    rotaryTable: 'Rotary table', rotaryTableDesc: '60-degree index',
    fillerA: 'Filler A', fillerADesc: 'Primary liquid dose', fillerB: 'Filler B', fillerBDesc: 'Secondary liquid dose',
    lidHandling: 'Lid handling', lidHandlingDesc: 'Pick and placement', capper: 'Capper', capperDesc: 'Closure verification',
    labelUnload: 'Label / unload', labelUnloadDesc: 'Departure confirmed', available: 'Available',
    activeFault: 'Active fault', waitingDownstream: 'Waiting downstream', recoveryPath: 'RECOVERY PATH',
    recoveryTitle: 'Evidence-gated recovery sequence', m1Authority: 'M1 owns HOLD / RESUME',
    detect: 'Detect', classifyEvent: 'Classify event', safeStop: 'Safe stop', m1Confirmation: 'M1 confirmation',
    recover: 'Recover', boundedAction: 'Bounded action', verify: 'Verify', independentEvidence: 'Independent evidence',
    resume: 'Resume', m1Decision: 'M1 decision', activeIncident: 'ACTIVE INCIDENT', decisionEvidence: 'Decision evidence',
    decision: 'Decision', attemptBudget: 'Attempt budget', recoveryPolicy: 'Recovery policy',
    validatedEvidence: 'Validated evidence', actuatorAuthority: 'Actuator authority: GP controllers',
    testMode: 'TEST MODE', scenarioInjection: 'Scenario injection', productionPolicyEngine: 'Uses the production policy engine',
    rotary: 'Rotary', transfer: 'Transfer', alignmentTimeout: 'Alignment timeout', noAutoRehome: 'No automatic rehome',
    motorStall: 'Motor stall', immediateLockout: 'Immediate lockout', positionSensorFailure: 'Position sensor failure',
    manualEvidenceRequired: 'Manual evidence required', magazineEmpty: 'Magazine empty', resourceWait: 'Resource wait',
    pickTimeout: 'Pick timeout', oneGuardedRetry: 'One guarded retry', placementTimeout: 'Placement timeout',
    manualVerification: 'Manual verification', arrivalTimeout: 'Arrival timeout', departureTimeout: 'Departure timeout',
    photoEyeFailure: 'Photo-eye failure', lidSensorFault: 'Lid sensor fault', noBlindRetry: 'No blind retry',
    positionConflict: 'Position conflict', coordinatedSafeStop: 'Coordinated safe stop',
    traceEvidence: 'TRACE EVIDENCE', protocolTimeline: 'Protocol timeline', newestFirst: 'Newest event first',
    footerBoundary: 'FaultSupervisorCD supervises policy and evidence. It never drives an actuator.',
    nextAction: 'Next action', noActiveFault: 'No active fault', monitoring: 'Monitoring rotary, lid handling and bottle transfer.',
    waitingFault: 'Waiting for the next controller fault event', requireSafeStop: 'M1 safe-stop confirmation is required',
    waitAck: 'Controller must acknowledge the bounded recovery request', waitResult: 'Waiting for newer controller result evidence',
    replenishLids: 'Replenish the lid magazine, then submit controller evidence', newerEvidence: 'Manual check accepted; newer controller evidence is still required',
    manualReconcile: 'Automatic retry prohibited; reconcile the machine manually', recoveryVerified: 'Recovery verified; M1 may now approve resume',
    investigate: 'Keep the line stopped and investigate the fault', productionRunning: 'Production line running',
    recoveryHeld: 'Recovery verified / line held', stoppedAt: 'Stopped at', ready: 'READY', verified: 'VERIFIED',
    faultStatus: 'FAULT', heldSafe: 'HELD SAFE', waiting: 'WAITING', normal: 'NORMAL',
    warning: 'WARNING', critical: 'CRITICAL', resource: 'RESOURCE', monitorOnly: 'MONITOR ONLY',
    none: 'None', noAction: 'No action required', commandAccepted: 'Command accepted', actionRejected: 'Action rejected by the supervisor',
    connectionLost: 'Supervisor connection lost', noEvents: 'No protocol events recorded'
  },
  zh: {
    appTitle: '容错监督系统', supervisorDecision: '监督决策', confirmSafeStop: '确认安全停机',
    recordReconciliation: '记录人工核对', submitEvidence: '提交控制器证据', simulateResume: '模拟 M1 恢复授权', reset: '重置',
    liveProcess: '机器单元 01 / 实时流程', processTitle: '瓶体流程与设备状态', stations: '个工位',
    domains: '个监督域', maxRetry: '次最大重试', bottleLoader: '上瓶机', bottleLoaderDesc: '空瓶进入系统',
    bottleTransfer: '瓶体输送', bottleTransferDesc: '到达与位置确认', rotaryTable: '旋转工作台', rotaryTableDesc: '每次转动 60 度',
    fillerA: '灌装机 A', fillerADesc: '第一阶段液体灌装', fillerB: '灌装机 B', fillerBDesc: '第二阶段液体灌装',
    lidHandling: '瓶盖装载', lidHandlingDesc: '取盖与放盖', capper: '旋盖机', capperDesc: '瓶盖闭合确认',
    labelUnload: '贴标与卸载', labelUnloadDesc: '离站确认', available: '可用', activeFault: '当前故障',
    waitingDownstream: '下游等待', recoveryPath: '恢复路径', recoveryTitle: '基于证据门控的恢复流程',
    m1Authority: 'M1 持有停线 / 恢复权限', detect: '检测', classifyEvent: '识别并分类事件', safeStop: '安全停机',
    m1Confirmation: '等待 M1 确认', recover: '恢复处理', boundedAction: '执行有限恢复动作', verify: '验证',
    independentEvidence: '检查独立证据', resume: '恢复运行', m1Decision: '由 M1 决策', activeIncident: '当前事件',
    decisionEvidence: '决策与证据', decision: '监督决策', attemptBudget: '重试额度', recoveryPolicy: '恢复策略',
    validatedEvidence: '已验证证据', actuatorAuthority: '执行器权限：GP 控制器', testMode: '测试模式',
    scenarioInjection: '故障场景注入', productionPolicyEngine: '使用正式恢复策略引擎', rotary: '旋转工作台', transfer: '瓶体输送',
    alignmentTimeout: '对位超时', noAutoRehome: '禁止自动回原点', motorStall: '电机堵转', immediateLockout: '立即锁定',
    positionSensorFailure: '位置传感器故障', manualEvidenceRequired: '需要人工核对证据', magazineEmpty: '瓶盖仓为空',
    resourceWait: '等待补充资源', pickTimeout: '取盖超时', oneGuardedRetry: '最多一次受控重试', placementTimeout: '放盖超时',
    manualVerification: '需要人工确认', arrivalTimeout: '到达超时', departureTimeout: '离站超时',
    photoEyeFailure: '光电传感器故障', lidSensorFault: '瓶盖传感器故障', noBlindRetry: '禁止盲目重试',
    positionConflict: '位置状态冲突', coordinatedSafeStop: '协调全线安全停机', traceEvidence: '追踪证据',
    protocolTimeline: '协议事件时间线', newestFirst: '最新事件在前',
    footerBoundary: 'FaultSupervisorCD 只负责策略与证据监督，不直接控制任何执行器。',
    nextAction: '下一步操作', noActiveFault: '当前无故障', monitoring: '正在监督旋转工作台、瓶盖装载和瓶体输送。',
    waitingFault: '等待下一个控制器故障事件', requireSafeStop: '需要 M1 确认全线已安全停机',
    waitAck: '等待控制器确认有限恢复请求', waitResult: '等待控制器返回更新版本的结果证据',
    replenishLids: '补充瓶盖后提交控制器证据', newerEvidence: '人工核对已记录，仍需更新版本的控制器证据',
    manualReconcile: '禁止自动重试；请人工核对设备与瓶体位置', recoveryVerified: '恢复证据已通过；等待 M1 授权恢复运行',
    investigate: '保持停线并检查故障', productionRunning: '生产线正常运行', recoveryHeld: '恢复已验证 / 生产线保持停止',
    stoppedAt: '停止于', ready: '就绪', verified: '已验证', faultStatus: '故障', heldSafe: '安全保持', waiting: '等待',
    normal: '正常', warning: '警告', critical: '严重', resource: '资源', monitorOnly: '仅监控',
    none: '无', noAction: '无需操作', commandAccepted: '操作已接受',
    actionRejected: '监督系统拒绝该操作', connectionLost: '与监督系统的连接已中断', noEvents: '暂无协议事件'
  }
};

const requestedLanguage = new URLSearchParams(window.location.search).get('lang');
let currentLanguage = requestedLanguage === 'zh' || requestedLanguage === 'en' ?
  requestedLanguage : localStorage.getItem('m3-language') === 'zh' ? 'zh' : 'en';
const t = key => translations[currentLanguage][key] || translations.en[key] || key;

function translateStatic() {
  document.documentElement.lang = currentLanguage === 'zh' ? 'zh-CN' : 'en';
  document.documentElement.dataset.language = currentLanguage;
  document.querySelectorAll('[data-i18n]').forEach(element => {
    element.textContent = t(element.dataset.i18n);
  });
  document.querySelectorAll('[data-language]').forEach(button => {
    button.classList.toggle('selected', button.dataset.language === currentLanguage);
  });
}

const faultKeys = {
  ALIGNMENT_TIMEOUT: 'alignmentTimeout', MOTOR_STALL: 'motorStall',
  POSITION_SENSOR_FAILURE: 'positionSensorFailure', MAGAZINE_EMPTY: 'magazineEmpty',
  PICK_TIMEOUT: 'pickTimeout', PLACEMENT_TIMEOUT: 'placementTimeout',
  ARRIVAL_TIMEOUT: 'arrivalTimeout', DEPARTURE_TIMEOUT: 'departureTimeout',
  PHOTO_EYE_FAILURE: 'photoEyeFailure', LID_SENSOR_FAULT: 'lidSensorFault',
  POSITION_CONFLICT: 'positionConflict'
};
const friendlyFault = value => value === '-' ? t('noActiveFault') :
  t(faultKeys[value] || value);

function stateClass(state) {
  if (state === 'IDLE') return 'state-idle';
  if (state === 'RECOVERY_READY') return 'state-ready';
  if (state === 'LOCKED_OUT' || state === 'FAILED') return 'state-blocked';
  return 'state-wait';
}

function workflowIndex(state) {
  if (state === 'IDLE') return -1;
  if (state === 'WAITING_SAFE_STOP') return 1;
  if (state === 'WAITING_RESULT') return 3;
  if (state === 'RECOVERY_READY') return 4;
  if (state === 'WAITING_ACK' || state === 'RESOURCE_WAIT' ||
      state === 'MANUAL_RECOVERY' || state === 'LOCKED_OUT' ||
      state === 'FAILED') return 2;
  return 0;
}

function nextActionText(data) {
  if (data.state === 'IDLE') return t('waitingFault');
  if (data.state === 'WAITING_SAFE_STOP') return t('requireSafeStop');
  if (data.state === 'WAITING_ACK') return t('waitAck');
  if (data.state === 'WAITING_RESULT') return t('waitResult');
  if (data.state === 'RESOURCE_WAIT') return t('replenishLids');
  if (data.state === 'LOCKED_OUT' && data.decision.startsWith('AWAIT_NEWER'))
    return t('newerEvidence');
  if (data.state === 'LOCKED_OUT') return t('manualReconcile');
  if (data.state === 'RECOVERY_READY') return t('recoveryVerified');
  return t('investigate');
}

function affectedStages(data) {
  if (data.subsystem === 'ROTARY') return [2];
  if (data.subsystem === 'LID') return [5];
  if (data.subsystem !== 'TRANSFER') return [];
  if (data.fault === 'DEPARTURE_TIMEOUT') return [7];
  if (data.fault === 'POSITION_CONFLICT') return [1, 2];
  if (data.fault === 'PHOTO_EYE_FAILURE') return [1, 7];
  return [1];
}

function renderPlant(data) {
  const running = data.state === 'IDLE';
  const ready = data.state === 'RECOVERY_READY';
  const affected = affectedStages(data);
  const stopStage = affected.length ? affected[0] : -1;

  document.body.classList.toggle('is-running', running);
  document.body.classList.toggle('is-paused', !running);
  document.body.classList.toggle('is-ready', ready);

  document.querySelectorAll('.station').forEach(station => {
    const stage = Number(station.dataset.stage);
    const selected = affected.includes(stage);
    station.classList.toggle('affected', selected && !ready);
    station.classList.toggle('verified', selected && ready);
    station.classList.toggle('upstream', stopStage >= 0 && stage < stopStage);
    station.classList.toggle('downstream', stopStage >= 0 && stage > stopStage);
    station.querySelector('em').textContent = running ? t('ready') :
      selected ? (ready ? t('verified') : t('faultStatus')) :
      stage < stopStage ? t('heldSafe') : t('waiting');
  });

  if (stopStage >= 0) {
    const position = 5 + ((stopStage + .5) / 8) * 90;
    processLine.style.setProperty('--bottle-left', `${position}%`);
    processLine.style.setProperty('--rail-stop', `${position}%`);
  }

  lineMessage.className = `line-state ${running ? 'healthy' : ready ? 'ready' : 'paused'}`;
  lineMessage.innerHTML = `<i></i><span>${running ? t('productionRunning') :
    ready ? t('recoveryHeld') :
    `${t('stoppedAt')} ${friendlyFault(data.fault)}`}</span>`;
}

function renderRecovery(data) {
  const active = workflowIndex(data.state);
  const blocked = data.state === 'LOCKED_OUT' || data.state === 'FAILED';
  document.querySelectorAll('#recovery-flow li').forEach(item => {
    const index = Number(item.dataset.step);
    item.classList.toggle('complete', active >= 0 && index < active);
    item.classList.toggle('active', index === active && !blocked);
    item.classList.toggle('blocked', index === active && blocked);
  });
  nextAction.innerHTML = `<span>${t('nextAction')}</span>${escapeHtml(nextActionText(data))}`;
}

function renderIncident(data) {
  const normal = data.severity === '-';
  const tone = normal ? 'normal' : data.severity.toLowerCase();
  statePill.textContent = currentLanguage === 'zh' ? {
    IDLE: '待机', WAITING_SAFE_STOP: '等待安全停机', WAITING_ACK: '等待确认',
    WAITING_RESULT: '等待结果', RESOURCE_WAIT: '等待资源', MANUAL_RECOVERY: '人工恢复',
    LOCKED_OUT: '已锁定', RECOVERY_READY: '恢复就绪', FAILED: '恢复失败'
  }[data.state] || data.state : data.state.replaceAll('_', ' ');
  statePill.className = `state-pill ${stateClass(data.state)}`;
  faultName.textContent = friendlyFault(data.fault);
  incidentContext.textContent = data.eventId === '-' ?
    t('monitoring') :
    `${data.subsystem} / ${data.eventId} / ${currentLanguage === 'zh' ? '瓶体' : 'bottle'} ${data.bottleId}`;
  severity.textContent = normal ? t('normal') : t(tone);
  severity.className = `severity ${normal ? 'neutral' : tone}`;
  severityIcon.className = `severity-icon ${tone}`;
  severityIcon.textContent = normal ? '\u2713' : '!';
  decision.textContent = data.decision;
  policy.textContent = data.policy === 'NONE' ? t('noAction') : data.policy;
  evidence.textContent = data.evidence === 'NONE' ? t('none') : data.evidence;
  attempt.textContent = `${data.attempt} / 1`;
  metrics.textContent = data.metrics;
  mode.textContent = data.testMode ? t('testMode') : t('monitorOnly');
  faultLab.hidden = !data.testMode;
  document.body.dataset.supervisorState = data.state;
  $('.command-band').style.borderLeftColor = normal ? 'var(--green)' :
    tone === 'critical' ? 'var(--red)' : 'var(--amber)';
}

function renderActions(data) {
  const mapping = {
    'safe-stop': data.state === 'WAITING_SAFE_STOP',
    'manual-evidence': data.state === 'LOCKED_OUT' && !data.decision.startsWith('AWAIT_NEWER'),
    'controller-evidence': data.state === 'WAITING_ACK' || data.state === 'RESOURCE_WAIT' ||
      (data.state === 'LOCKED_OUT' && data.decision.startsWith('AWAIT_NEWER')),
    'resume': data.state === 'RECOVERY_READY',
    'reset': data.state !== 'IDLE'
  };
  document.querySelectorAll('[data-action]').forEach(button =>
    button.classList.toggle('visible', Boolean(mapping[button.dataset.action])));
  document.querySelectorAll('[data-fault]').forEach(button =>
    button.disabled = data.state !== 'IDLE');
}

function renderTrace(history) {
  if (!history.length) {
    trace.innerHTML = `<li class="empty">${t('noEvents')}</li>`;
    return;
  }
  trace.innerHTML = history.slice().reverse().map(entry => {
    const split = entry.indexOf(' ');
    const sequence = split > 0 ? entry.slice(0, split) : '';
    const message = split > 0 ? entry.slice(split + 1) : entry;
    const tone = /FAILED|REJECTED|FAULT|LOCKED/.test(message) ? 'failure' :
      /READY|RESUME|VERIFIED|AUTHORIZED/.test(message) ? 'success' : '';
    return `<li><span class="seq">${escapeHtml(sequence)}</span><span class="${tone}">${escapeHtml(message)}</span></li>`;
  }).join('');
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, character => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#039;'
  })[character]);
}

async function loadState() {
  try {
    const response = await fetch('/api/state', {cache: 'no-store'});
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const data = await response.json();
    renderPlant(data); renderRecovery(data); renderIncident(data);
    renderActions(data); renderTrace(data.history);
    connection.classList.remove('offline');
    connection.innerHTML = '<i></i>LIVE';
  } catch (error) {
    connection.classList.add('offline');
    connection.innerHTML = '<i></i>OFFLINE';
    lineMessage.className = 'line-state paused';
    lineMessage.innerHTML = '<i></i><span>Supervisor connection lost</span>';
  }
}

async function action(parameters) {
  actionResult.textContent = '';
  try {
    const response = await fetch('/api/action', {
      method: 'POST', headers: {'Content-Type': 'application/x-www-form-urlencoded'},
      body: new URLSearchParams(parameters)
    });
    if (!response.ok) {
      const result = await response.json();
      actionResult.textContent = result.error || t('actionRejected');
      actionResult.className = 'action-error';
    } else {
      actionResult.textContent = t('commandAccepted');
      actionResult.className = 'action-success';
      window.setTimeout(() => { actionResult.textContent = ''; }, 1400);
    }
  } catch (error) {
    actionResult.textContent = t('connectionLost');
  }
  await loadState();
}

function updateClock() {
  systemClock.textContent = new Date().toLocaleTimeString('en-NZ', {
    hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit'
  });
}

document.querySelectorAll('[data-tab]').forEach(button => button.addEventListener('click', () => {
  document.querySelectorAll('[data-tab]').forEach(tab => tab.classList.toggle('selected', tab === button));
  document.querySelectorAll('[data-panel]').forEach(panel => panel.classList.toggle('selected', panel.dataset.panel === button.dataset.tab));
}));
document.querySelectorAll('[data-language]').forEach(button => button.addEventListener('click', () => {
  currentLanguage = button.dataset.language;
  localStorage.setItem('m3-language', currentLanguage);
  const url = new URL(window.location.href);
  url.searchParams.set('lang', currentLanguage);
  window.history.replaceState({}, '', url);
  translateStatic();
  loadState();
}));
document.querySelectorAll('[data-fault]').forEach(button => button.addEventListener('click', () => action({action: 'inject', fault: button.dataset.fault})));
document.querySelectorAll('[data-action]').forEach(button => button.addEventListener('click', () => action({action: button.dataset.action})));

translateStatic();
loadState();
updateClock();
setInterval(loadState, 300);
setInterval(updateClock, 1000);
