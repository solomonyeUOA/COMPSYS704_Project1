const statePill = document.querySelector('#state-pill');
const lineMessage = document.querySelector('#line-message');
const nextAction = document.querySelector('#next-action');
const faultName = document.querySelector('#fault-name');
const incidentContext = document.querySelector('#incident-context');
const severity = document.querySelector('#severity');
const decision = document.querySelector('#decision');
const policy = document.querySelector('#policy');
const evidence = document.querySelector('#evidence');
const attempt = document.querySelector('#attempt');
const metrics = document.querySelector('#metrics');
const trace = document.querySelector('#trace');
const faultLab = document.querySelector('#fault-lab');
const mode = document.querySelector('#mode');
const blockedBottle = document.querySelector('#blocked-bottle');
const actionResult = document.querySelector('#action-result');

let lastState = null;

const friendlyFault = value => value === '-' ? 'No active fault' :
  value.toLowerCase().split('_').map(word =>
    word.charAt(0).toUpperCase() + word.slice(1)).join(' ');

function stateClass(state) {
  if (state === 'IDLE') return 'state-idle';
  if (state === 'RECOVERY_READY') return 'state-ready';
  if (state === 'LOCKED_OUT' || state === 'FAILED') return 'state-blocked';
  return 'state-wait';
}

function workflowIndex(state) {
  if (state === 'IDLE') return -1;
  if (state === 'WAITING_SAFE_STOP') return 1;
  if (state === 'WAITING_ACK' || state === 'WAITING_RESULT' ||
      state === 'RESOURCE_WAIT' || state === 'MANUAL_RECOVERY' ||
      state === 'LOCKED_OUT' || state === 'FAILED') return 2;
  if (state === 'RECOVERY_READY') return 4;
  return 0;
}

function nextActionText(data) {
  if (data.state === 'IDLE') return 'Waiting for the next Controller fault event';
  if (data.state === 'WAITING_SAFE_STOP') return 'Blocked at safe stop: M1 confirmation is required';
  if (data.state === 'WAITING_ACK') return 'Waiting for the Controller to acknowledge the recovery request';
  if (data.state === 'WAITING_RESULT') return 'Waiting for new Controller result evidence';
  if (data.state === 'RESOURCE_WAIT') return 'Blocked until the lid magazine is replenished';
  if (data.state === 'LOCKED_OUT' && data.decision.startsWith('AWAIT_NEWER'))
    return 'Manual check recorded; newer Controller evidence is still required';
  if (data.state === 'LOCKED_OUT') return 'Automatic recovery prohibited; manual reconciliation is required';
  if (data.state === 'RECOVERY_READY') return 'Recovery verified; line remains paused until M1 approves resume';
  return 'Keep the line stopped and investigate the fault';
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
  document.body.classList.toggle('running', running);
  document.body.classList.toggle('paused', !running);
  document.body.classList.toggle('ready', ready);

  const affected = affectedStages(data);
  document.querySelectorAll('.machine').forEach(machine => {
    const selected = affected.includes(Number(machine.dataset.stage));
    machine.classList.toggle('affected', selected && !ready);
    machine.classList.toggle('verified', selected && ready);
    const marker = machine.querySelector('.fault-marker');
    marker.textContent = ready ? 'RECOVERY VERIFIED' :
      (data.state === 'LOCKED_OUT' || data.state === 'FAILED' ?
        'BLOCKED HERE' : 'FAULT HERE');
  });

  const blockedStage = affected.length ? affected[0] : 0;
  blockedBottle.style.setProperty('--blocked-left',
    `${3 + (blockedStage / 7) * 94}%`);

  lineMessage.className = `line-message ${running ? 'healthy' : ready ? 'ready' : 'paused'}`;
  lineMessage.innerHTML = `<span class="live-dot"></span><span>${running ?
    'Line running normally' : ready ?
    'Line paused: waiting for M1 resume' :
    `Line stopped at ${friendlyFault(data.fault)}`}</span>`;
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
  nextAction.textContent = nextActionText(data);
}

function renderIncident(data) {
  statePill.textContent = data.state.replaceAll('_', ' ');
  statePill.className = `state-pill ${stateClass(data.state)}`;
  faultName.textContent = friendlyFault(data.fault);
  incidentContext.textContent = data.eventId === '-' ?
    'The supervisor is monitoring all three subsystems.' :
    `${data.subsystem} / event ${data.eventId} / bottle ${data.bottleId}`;
  severity.textContent = data.severity === '-' ? 'NORMAL' : data.severity;
  severity.className = `severity ${data.severity === '-' ? 'neutral' : data.severity.toLowerCase()}`;
  decision.textContent = data.decision;
  policy.textContent = data.policy === 'NONE' ? 'No action required' : data.policy;
  evidence.textContent = data.evidence === 'NONE' ? 'None' : data.evidence;
  attempt.textContent = `${data.attempt} / 1`;
  metrics.textContent = data.metrics;
  mode.textContent = data.testMode ? 'TEST MODE' : 'MONITOR ONLY';
  faultLab.hidden = !data.testMode;
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
    trace.innerHTML = '<li class="empty">No events recorded</li>';
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
    const data = await response.json();
    renderPlant(data);
    renderRecovery(data);
    renderIncident(data);
    renderActions(data);
    renderTrace(data.history);
    lastState = data;
  } catch (error) {
    lineMessage.className = 'line-message paused';
    lineMessage.textContent = 'Supervisor connection lost';
  }
}

async function action(parameters) {
  actionResult.textContent = '';
  const response = await fetch('/api/action', {
    method: 'POST',
    headers: {'Content-Type': 'application/x-www-form-urlencoded'},
    body: new URLSearchParams(parameters)
  });
  if (!response.ok) {
    const result = await response.json();
    actionResult.textContent = result.error || 'Action rejected by the supervisor';
  }
  await loadState();
}

document.querySelectorAll('[data-fault]').forEach(button =>
  button.addEventListener('click', () => action({action: 'inject', fault: button.dataset.fault})));
document.querySelectorAll('[data-action]').forEach(button =>
  button.addEventListener('click', () => action({action: button.dataset.action})));

loadState();
setInterval(loadState, 300);
