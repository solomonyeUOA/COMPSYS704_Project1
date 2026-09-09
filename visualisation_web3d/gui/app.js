import * as THREE from './vendor/three.module.min.js';

const STATUS_COLOURS = [0x66798a, 0x4a9eff, 0xf4ab48, 0x4ed18b, 0xff616d];
const WAITING_COLOUR = 0x526477;
const STATION_X = [-9.2, -6.5, -3.0, 0.7, 3.2, 5.8, 8.2, 11.0];
const STATION_PRESENTATION = [
  'Bottle queue and symbolic dispense',
  'Belt transfer and roller motion',
  'Six-position circular index table',
  'Symbolic 60% liquid A layer',
  'Symbolic 40% liquid B layer',
  'Magazine pick and lid placement',
  'Head descent and tightening angle',
  'Exit transfer and real completion boundary'
];

const ui = {
  canvas: document.querySelector('#factory-canvas'),
  scene: document.querySelector('#scene'),
  error: document.querySelector('#scene-error'),
  connection: document.querySelector('#connection'),
  source: document.querySelector('#source-mode'),
  required: document.querySelector('#required-count'),
  completed: document.querySelector('#completed-count'),
  visual: document.querySelector('#visual-count'),
  flowMode: document.querySelector('#flow-mode'),
  lastUpdate: document.querySelector('#last-update'),
  workspace: document.querySelector('.workspace'),
  stationNav: document.querySelector('#station-nav'),
  overview: document.querySelector('#overview-button'),
  detailPanel: document.querySelector('#detail-panel'),
  closeDetail: document.querySelector('#close-detail'),
  detailCanvas: document.querySelector('#detail-canvas'),
  detailCode: document.querySelector('#detail-code'),
  detailName: document.querySelector('#detail-name'),
  detailStatus: document.querySelector('#detail-status'),
  detailProgress: document.querySelector('#detail-progress-value'),
  detailProgressBar: document.querySelector('#detail-progress-bar'),
  detailEvidence: document.querySelector('#detail-evidence'),
  detailLifecycle: document.querySelector('#detail-lifecycle'),
  detailPhase: document.querySelector('#detail-phase'),
  detailPresentation: document.querySelector('#detail-presentation'),
  stationSpecific: document.querySelector('#station-specific'),
  teamGrid: document.querySelector('#team-ip-grid'),
  teamDialog: document.querySelector('#team-detail-dialog'),
  teamClose: document.querySelector('#close-team-detail'),
  teamMember: document.querySelector('#team-detail-member'),
  teamTitle: document.querySelector('#team-detail-title'),
  teamSummary: document.querySelector('#team-detail-summary'),
  teamArchitecture: document.querySelector('#team-detail-architecture'),
  teamHeadline: document.querySelector('#team-detail-headline'),
  teamLive: document.querySelector('#team-detail-live'),
  teamCapabilities: document.querySelector('#team-detail-capabilities'),
  teamMode: document.querySelector('#team-detail-mode')
};

let latestState = null;
let selectedStation = null;
let selectedTeamIp = null;
let lastStateReceivedAt = 0;
let pollFailures = 0;
let sceneBundle = null;
let detailSceneBundle = null;

function setConnection(mode, text) {
  ui.connection.className = `connection ${mode}`;
  ui.connection.lastChild.textContent = text;
}

function statusClass(name) {
  const value = String(name || 'WAITING').toLowerCase();
  return ['ready', 'busy', 'done', 'fault'].includes(value) ? value : 'waiting';
}

function formatPercent(value) {
  return `${Math.round(Math.max(0, Math.min(100, Number(value) || 0)))}%`;
}

function renderState(state) {
  latestState = state;
  ui.source.textContent = state.telemetrySource;
  ui.required.textContent = state.requiredBottles;
  ui.completed.textContent = state.completedBottles;
  ui.visual.textContent = state.visualCompletedBottles;
  ui.flowMode.textContent = state.flowMode;
  ui.lastUpdate.textContent = new Date(state.timestamp).toLocaleTimeString();
  setConnection('is-live', state.demoMode ? 'DEMO LIVE' : 'LIVE');

  for (const button of ui.stationNav.querySelectorAll('button')) {
    const index = Number(button.dataset.station);
    const machine = state.machines[index];
    button.classList.toggle('selected', index === selectedStation);
    button.classList.toggle('is-busy', machine?.statusName === 'BUSY');
    button.classList.toggle('is-fault', machine?.statusName === 'FAULT');
  }

  if (selectedStation !== null) renderSelectedMachine();
  renderTeamIp(state.teamIp || []);
  if (selectedTeamIp !== null && ui.teamDialog.open) {
    renderTeamIpDetail(selectedTeamIp);
  }
}

function renderSelectedMachine() {
  if (selectedStation === null || !latestState?.machines?.[selectedStation]) return;
  const machine = latestState.machines[selectedStation];
  const progress = formatPercent(machine.progress);
  ui.detailCode.textContent = `STATION ${String(selectedStation + 1).padStart(2, '0')}`;
  ui.detailName.textContent = machine.name;
  ui.detailStatus.textContent = machine.statusName;
  ui.detailStatus.className = `status-pill ${statusClass(machine.statusName)}`;
  ui.detailProgress.textContent = progress;
  ui.detailProgressBar.style.width = progress;
  ui.detailEvidence.textContent = machine.statusReceived
    ? `${machine.statusName} / VIZ_* received`
    : 'Not received';
  ui.detailLifecycle.textContent = machine.lifecycle;
  ui.detailPhase.textContent = machine.phase;
  ui.detailPresentation.textContent = STATION_PRESENTATION[selectedStation];
  ui.stationSpecific.replaceChildren(stationSpecificContent(machine));
}

function stationSpecificContent(machine) {
  const fragment = document.createDocumentFragment();
  const strong = document.createElement('strong');
  const detail = document.createElement('div');
  if (machine.key === 'rotary') {
    const occupied = machine.rotaryPositions.filter(Boolean).length;
    strong.textContent = 'Rotary geometry';
    detail.textContent = `${machine.rotaryPhase} · ${occupied}/6 symbolic positions occupied · ${Math.round(machine.rotaryAngle)}° table angle`;
  } else if (machine.key === 'fillerA') {
    strong.textContent = 'Liquid A presentation';
    detail.textContent = `${Math.round(machine.liquidA)}% symbolic A layer · no inferred sensor value`;
  } else if (machine.key === 'fillerB') {
    strong.textContent = 'Layered fill presentation';
    detail.textContent = `${Math.round(machine.liquidA)}% A + ${Math.round(machine.liquidB)}% B · established 60/40 demo assumption`;
  } else if (machine.key === 'capper') {
    strong.textContent = 'Cap presentation';
    detail.textContent = `${Math.round(machine.tighteningAngle)}° symbolic tightening angle`;
  } else if (machine.key === 'conveyor') {
    strong.textContent = 'Transfer presentation';
    detail.textContent = `${Math.round(machine.rollerAngle)}° roller angle · bottle position remains model-anchored`;
  } else {
    strong.textContent = 'Visual semantics';
    detail.textContent = STATION_PRESENTATION[machine.index];
  }
  fragment.append(strong, detail);
  return fragment;
}

function renderTeamIp(items) {
  const cards = items.map((item, index) => {
    const card = document.createElement('article');
    card.className = 'team-card';
    card.dataset.teamIp = String(index);
    card.tabIndex = 0;
    card.setAttribute('role', 'button');
    card.setAttribute('aria-label', `Open ${item.member} ${item.title} detail`);
    const head = document.createElement('div');
    head.className = 'team-card-head';
    const titleWrap = document.createElement('div');
    const member = document.createElement('span');
    member.className = 'team-member';
    member.textContent = item.member;
    const title = document.createElement('h3');
    title.textContent = item.title;
    titleWrap.append(member, title);
    const mode = document.createElement('span');
    mode.className = `team-mode${item.liveEvidenceAvailable ? ' live' : ''}`;
    mode.textContent = item.liveEvidenceAvailable ? 'LIVE' : item.mode;
    head.append(titleWrap, mode);

    const summary = document.createElement('p');
    summary.textContent = item.summary;
    const evidence = document.createElement('div');
    evidence.className = 'team-evidence';
    const evidenceTitle = document.createElement('strong');
    evidenceTitle.textContent = item.liveHeadline;
    const lines = document.createElement('ul');
    for (const line of item.liveLines) {
      const entry = document.createElement('li');
      entry.textContent = line;
      lines.append(entry);
    }
    evidence.append(evidenceTitle, lines);
    const representation = document.createElement('span');
    representation.className = 'team-representation';
    representation.textContent = item.m1Representation;
    card.append(head, summary, evidence, representation);
    return card;
  });
  ui.teamGrid.replaceChildren(...cards);
}

function renderTeamIpDetail(index) {
  const item = latestState?.teamIp?.[index];
  if (!item) return;
  ui.teamMember.textContent = `${item.member} / TEAM IP DETAIL`;
  ui.teamTitle.textContent = item.title;
  ui.teamSummary.textContent = item.summary;
  ui.teamHeadline.textContent = item.liveHeadline;
  ui.teamMode.textContent = item.liveEvidenceAvailable
    ? `${item.mode} / LIVE EVIDENCE`
    : item.mode;
  ui.teamArchitecture.replaceChildren(...item.architectureNodes.map(node => {
    const entry = document.createElement('li');
    entry.textContent = node;
    return entry;
  }));
  ui.teamLive.replaceChildren(...item.liveLines.map(line => {
    const entry = document.createElement('li');
    entry.textContent = line;
    return entry;
  }));
  ui.teamCapabilities.replaceChildren(...item.capabilityLines.map(line => {
    const entry = document.createElement('span');
    entry.textContent = line;
    return entry;
  }));
}

function openTeamIpDetail(index) {
  selectedTeamIp = index;
  renderTeamIpDetail(index);
  if (!ui.teamDialog.open) ui.teamDialog.showModal();
  document.body.dataset.teamIpDetail = latestState?.teamIp?.[index]?.member || '';
}

function closeTeamIpDetail() {
  selectedTeamIp = null;
  delete document.body.dataset.teamIpDetail;
  if (ui.teamDialog.open) ui.teamDialog.close();
}

async function pollState() {
  try {
    const response = await fetch('/api/state', {cache: 'no-store'});
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const state = await response.json();
    if (state.readOnly !== true) throw new Error('unsafe state boundary');
    lastStateReceivedAt = performance.now();
    pollFailures = 0;
    renderState(state);
  } catch (error) {
    pollFailures += 1;
    if (pollFailures >= 3) setConnection('is-offline', 'OFFLINE');
  }
}

function createScene() {
  const renderer = new THREE.WebGLRenderer({canvas: ui.canvas, antialias: true});
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
  renderer.setSize(ui.scene.clientWidth, ui.scene.clientHeight, false);
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFSoftShadowMap;
  renderer.outputColorSpace = THREE.SRGBColorSpace;

  const scene = new THREE.Scene();
  scene.background = new THREE.Color(0x08121e);
  scene.fog = new THREE.Fog(0x08121e, 27, 52);
  const camera = new THREE.PerspectiveCamera(38, 1, .1, 100);
  const cameraState = {
    theta: .74,
    phi: .94,
    radius: 29,
    target: new THREE.Vector3(.8, .4, 0),
    desiredRadius: 29,
    desiredTarget: new THREE.Vector3(.8, .4, 0)
  };

  scene.add(new THREE.HemisphereLight(0xc9edff, 0x172433, 1.8));
  const keyLight = new THREE.DirectionalLight(0xffffff, 2.3);
  keyLight.position.set(-7, 16, 12);
  keyLight.castShadow = true;
  keyLight.shadow.mapSize.set(1024, 1024);
  scene.add(keyLight);
  const rimLight = new THREE.DirectionalLight(0x37d6d0, 1.5);
  rimLight.position.set(14, 7, -12);
  scene.add(rimLight);

  const ground = new THREE.Mesh(
    new THREE.PlaneGeometry(34, 15),
    new THREE.MeshStandardMaterial({color: 0x0d1b28, roughness: .88, metalness: .15})
  );
  ground.rotation.x = -Math.PI / 2;
  ground.receiveShadow = true;
  scene.add(ground);
  const grid = new THREE.GridHelper(34, 34, 0x214761, 0x162c3e);
  grid.position.y = .012;
  scene.add(grid);

  const factory = new THREE.Group();
  scene.add(factory);
  const stationRoots = [];
  const indicators = [];
  const selectors = [];
  const selectionRings = [];

  for (let index = 0; index < STATION_X.length; index += 1) {
    const root = new THREE.Group();
    root.position.x = STATION_X[index];
    root.userData.stationIndex = index;
    factory.add(root);
    stationRoots.push(root);
    buildStation(index, root);
    root.traverse(object => {
      if (object.isMesh) {
        object.userData.stationIndex = index;
        object.castShadow = true;
        object.receiveShadow = true;
        selectors.push(object);
      }
    });
    const indicator = new THREE.Mesh(
      new THREE.SphereGeometry(.13, 16, 10),
      new THREE.MeshStandardMaterial({color: WAITING_COLOUR, emissive: WAITING_COLOUR, emissiveIntensity: 1.4})
    );
    indicator.position.set(0, 3.35, 0);
    root.add(indicator);
    indicators.push(indicator);
    const ring = new THREE.Mesh(
      new THREE.RingGeometry(1.15, 1.28, 40),
      new THREE.MeshBasicMaterial({color: 0x37d6d0, side: THREE.DoubleSide, transparent: true, opacity: .9})
    );
    ring.rotation.x = -Math.PI / 2;
    ring.position.y = .035;
    ring.visible = index === selectedStation;
    root.add(ring);
    selectionRings.push(ring);
  }

  const route = new THREE.Mesh(
    new THREE.BoxGeometry(22.2, .16, 1.05),
    new THREE.MeshStandardMaterial({color: 0x152b3b, metalness: .6, roughness: .42})
  );
  route.position.set(.8, .37, 0);
  route.receiveShadow = true;
  scene.add(route);
  for (let x = -10; x <= 12; x += .75) {
    const roller = new THREE.Mesh(
      new THREE.CylinderGeometry(.09, .09, 1.12, 12),
      new THREE.MeshStandardMaterial({color: 0x3f596c, metalness: .8, roughness: .3})
    );
    roller.rotation.x = Math.PI / 2;
    roller.position.set(x, .5, 0);
    scene.add(roller);
  }

  const flowLine = new THREE.Line(
    new THREE.BufferGeometry().setFromPoints([
      new THREE.Vector3(-10.5, .08, 1.55),
      new THREE.Vector3(12.3, .08, 1.55)
    ]),
    new THREE.LineBasicMaterial({color: 0x37d6d0, transparent: true, opacity: .42})
  );
  scene.add(flowLine);

  const bottleMeshes = [];
  const rotaryRoot = stationRoots[2].getObjectByName('rotary-index');
  const clock = new THREE.Clock();
  const raycaster = new THREE.Raycaster();
  const pointer = new THREE.Vector2();

  function resize() {
    const width = Math.max(1, ui.scene.clientWidth);
    const height = Math.max(1, ui.scene.clientHeight);
    renderer.setSize(width, height, false);
    camera.aspect = width / height;
    camera.updateProjectionMatrix();
  }

  function updateCamera() {
    cameraState.target.lerp(cameraState.desiredTarget, .075);
    cameraState.radius = THREE.MathUtils.lerp(
      cameraState.radius,
      cameraState.desiredRadius,
      .075
    );
    const {theta, phi, radius, target} = cameraState;
    camera.position.set(
      target.x + radius * Math.sin(phi) * Math.sin(theta),
      target.y + radius * Math.cos(phi),
      target.z + radius * Math.sin(phi) * Math.cos(theta)
    );
    camera.lookAt(target);
  }

  function updateScene(delta) {
    const machines = latestState?.machines;
    if (machines) {
      machines.forEach((machine, index) => {
        const colour = machine.status >= 0 && machine.status <= 4
          ? STATUS_COLOURS[machine.status]
          : WAITING_COLOUR;
        indicators[index].material.color.setHex(colour);
        indicators[index].material.emissive.setHex(colour);
        const targetScale = machine.statusName === 'BUSY' ? 1.35 : 1;
        indicators[index].scale.lerp(new THREE.Vector3(targetScale, targetScale, targetScale), .12);
        selectionRings[index].visible = index === selectedStation;
      });
      if (rotaryRoot) {
        const targetAngle = -THREE.MathUtils.degToRad(machines[2].rotaryAngle || 0);
        rotaryRoot.rotation.y = dampAngle(rotaryRoot.rotation.y, targetAngle, 8, delta);
      }
    }
    syncBottles(latestState?.visualBottles || [], bottleMeshes, factory, delta);
    if (performance.now() - lastStateReceivedAt > 1500 && pollFailures > 0) {
      setConnection('is-offline', 'OFFLINE');
    }
  }

  function render() {
    const delta = Math.min(clock.getDelta(), .05);
    updateScene(delta);
    if (detailSceneBundle) detailSceneBundle.render(delta);
    updateCamera();
    renderer.render(scene, camera);
    requestAnimationFrame(render);
  }

  let drag = null;
  ui.canvas.addEventListener('pointerdown', event => {
    drag = {x: event.clientX, y: event.clientY, theta: cameraState.theta, phi: cameraState.phi, moved: false};
    ui.canvas.setPointerCapture(event.pointerId);
  });
  ui.canvas.addEventListener('pointermove', event => {
    if (!drag) return;
    const dx = event.clientX - drag.x;
    const dy = event.clientY - drag.y;
    drag.moved ||= Math.abs(dx) + Math.abs(dy) > 5;
    cameraState.theta = drag.theta - dx * .006;
    cameraState.phi = THREE.MathUtils.clamp(drag.phi + dy * .005, .45, 1.34);
  });
  ui.canvas.addEventListener('pointerup', event => {
    if (drag && !drag.moved) {
      const rect = ui.canvas.getBoundingClientRect();
      pointer.x = ((event.clientX - rect.left) / rect.width) * 2 - 1;
      pointer.y = -((event.clientY - rect.top) / rect.height) * 2 + 1;
      raycaster.setFromCamera(pointer, camera);
      const hit = raycaster.intersectObjects(selectors, false)[0];
      if (hit && Number.isInteger(hit.object.userData.stationIndex)) {
        selectStation(hit.object.userData.stationIndex);
      }
    }
    drag = null;
  });
  ui.canvas.addEventListener('wheel', event => {
    event.preventDefault();
    cameraState.desiredRadius = THREE.MathUtils.clamp(
      cameraState.desiredRadius + event.deltaY * .015,
      19,
      42
    );
  }, {passive: false});

  new ResizeObserver(resize).observe(ui.scene);
  resize();
  updateCamera();
  render();
  return {
    renderer,
    scene,
    camera,
    stationRoots,
    focusStation(index) {
      const overviewX = .8;
      cameraState.desiredTarget.set(
        overviewX + (STATION_X[index] - overviewX) * .28,
        .6,
        0
      );
      cameraState.desiredRadius = 30;
    },
    resetOverview() {
      cameraState.theta = .74;
      cameraState.phi = .94;
      cameraState.desiredTarget.set(.8, .4, 0);
      cameraState.desiredRadius = 29;
    }
  };
}

function standardMaterial(colour, options = {}) {
  return new THREE.MeshStandardMaterial({
    color: colour,
    metalness: options.metalness ?? .48,
    roughness: options.roughness ?? .46,
    transparent: options.transparent ?? false,
    opacity: options.opacity ?? 1
  });
}

function addBox(parent, size, position, colour, options) {
  const mesh = new THREE.Mesh(new THREE.BoxGeometry(...size), standardMaterial(colour, options));
  mesh.position.set(...position);
  parent.add(mesh);
  return mesh;
}

function addCylinder(parent, radii, position, colour, sides = 20, options) {
  const mesh = new THREE.Mesh(
    new THREE.CylinderGeometry(radii[0], radii[1], radii[2], sides),
    standardMaterial(colour, options)
  );
  mesh.position.set(...position);
  parent.add(mesh);
  return mesh;
}

function buildStation(index, root) {
  const steel = 0x456173;
  const dark = 0x1a2e3d;
  const accent = 0x2f9ca9;
  addBox(root, [2.1, .2, 2.5], [0, .12, 0], 0x122536, {metalness: .65});
  if (index === 0) {
    addBox(root, [1.25, 2.2, 1.25], [0, 1.25, 0], dark);
    const hopper = addCylinder(root, [.76, .38, 1.15], [0, 2.45, 0], accent, 6);
    hopper.rotation.y = Math.PI / 6;
    addBox(root, [.25, .8, .25], [0, .55, 0], steel);
  } else if (index === 1) {
    addBox(root, [2.5, .34, 1.35], [0, .82, 0], 0x27475b, {metalness: .7});
    for (let x = -.9; x <= .9; x += .45) {
      const roller = addCylinder(root, [.11, .11, 1.42], [x, 1.03, 0], steel, 12);
      roller.rotation.x = Math.PI / 2;
    }
    addBox(root, [.16, .7, .16], [-.9, .4, -.45], steel);
    addBox(root, [.16, .7, .16], [.9, .4, -.45], steel);
  } else if (index === 2) {
    const rotary = new THREE.Group();
    rotary.name = 'rotary-index';
    rotary.position.y = .82;
    root.add(rotary);
    addCylinder(rotary, [1.18, 1.18, .32], [0, 0, 0], 0x287e8c, 32, {metalness: .72});
    for (let slot = 0; slot < 6; slot += 1) {
      const angle = slot * Math.PI / 3;
      addCylinder(rotary, [.18, .18, .08], [Math.cos(angle) * .72, .2, Math.sin(angle) * .72], 0x9eb6c5, 18);
    }
    addCylinder(root, [.28, .38, .78], [0, .4, 0], steel, 18);
    addBox(root, [.14, 2.25, .14], [0, 2.0, 0], accent);
  } else if (index === 3 || index === 4) {
    const liquidColour = index === 3 ? 0x2f8fe5 : 0x8c64d8;
    addBox(root, [1.7, 2.35, .3], [0, 1.4, -.65], dark);
    addCylinder(root, [.58, .58, 1.35], [0, 2.25, -.18], liquidColour, 20, {metalness: .25});
    addCylinder(root, [.12, .12, 1.5], [0, 1.45, 0], 0x9fb3c0, 12, {metalness: .82});
    addCylinder(root, [.18, .08, .45], [0, .77, 0], liquidColour, 14);
  } else if (index === 5) {
    addCylinder(root, [.56, .56, 1.9], [0, 1.65, -.25], 0x6d8797, 20, {metalness: .66});
    for (let layer = 0; layer < 5; layer += 1) {
      addCylinder(root, [.62, .62, .08], [0, 1.0 + layer * .28, -.25], 0x37b4bd, 20);
    }
    addBox(root, [.2, 1.6, .2], [.65, 1.45, 0], steel);
    addBox(root, [.75, .16, .2], [.35, 2.2, 0], steel);
  } else if (index === 6) {
    addBox(root, [.22, 2.6, .22], [-.7, 1.45, 0], steel);
    addBox(root, [.22, 2.6, .22], [.7, 1.45, 0], steel);
    addBox(root, [1.65, .24, .55], [0, 2.72, 0], dark);
    addCylinder(root, [.36, .36, .6], [0, 2.25, 0], 0x9e7a3e, 18, {metalness: .7});
    addCylinder(root, [.13, .13, 1.1], [0, 1.48, 0], 0xb8c5cc, 12, {metalness: .86});
  } else {
    const chute = addBox(root, [2.45, .2, 1.25], [0, .9, 0], 0x2d5666, {metalness: .72});
    chute.rotation.z = -.12;
    addBox(root, [.2, 1.0, .2], [-.8, .45, -.38], steel);
    addBox(root, [.2, .7, .2], [.8, .35, -.38], steel);
    addBox(root, [.55, 1.3, 1.5], [1.1, .72, 0], dark);
  }
}

function createDetailScene() {
  const renderer = new THREE.WebGLRenderer({canvas: ui.detailCanvas, antialias: true, alpha: true});
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  const scene = new THREE.Scene();
  scene.add(new THREE.HemisphereLight(0xd6f4ff, 0x172433, 2.2));
  const light = new THREE.DirectionalLight(0xffffff, 2.4);
  light.position.set(-4, 7, 6);
  scene.add(light);
  const camera = new THREE.PerspectiveCamera(36, 1, .1, 50);
  camera.position.set(5.1, 4.4, 7.2);
  camera.lookAt(0, 1.25, 0);
  const floor = new THREE.Mesh(
    new THREE.CircleGeometry(3.3, 40),
    new THREE.MeshStandardMaterial({color: 0x102433, metalness: .3, roughness: .75})
  );
  floor.rotation.x = -Math.PI / 2;
  scene.add(floor);

  let stationRoot = null;
  let bottle = null;
  let stationIndex = null;

  function resize() {
    const width = Math.max(1, ui.detailCanvas.clientWidth);
    const height = Math.max(1, ui.detailCanvas.clientHeight);
    renderer.setSize(width, height, false);
    camera.aspect = width / height;
    camera.updateProjectionMatrix();
  }

  function select(index) {
    if (stationRoot) scene.remove(stationRoot);
    if (bottle) scene.remove(bottle);
    stationIndex = index;
    stationRoot = new THREE.Group();
    stationRoot.scale.setScalar(.92);
    buildStation(index, stationRoot);
    scene.add(stationRoot);
    bottle = createBottle();
    bottle.scale.setScalar(1.18);
    scene.add(bottle);
    resize();
  }

  function render(delta) {
    if (stationIndex === null || selectedStation !== stationIndex || !latestState) return;
    const machine = latestState.machines[stationIndex];
    const record = latestState.visualBottles.find(item =>
      item.stage === stationIndex && item.lifecycle !== 'COMPLETED'
    );
    const progress = THREE.MathUtils.clamp((machine.progress || 0) / 100, 0, 1);
    bottle.visible = Boolean(record);
    if (record) {
      bottle.userData.liquidA.visible = stationIndex >= 3;
      bottle.userData.liquidB.visible = stationIndex >= 4;
      bottle.userData.cap.visible = stationIndex >= 5;
      if (stationIndex === 0) {
        bottle.position.set(-1.15 + progress * 1.15, .46 + progress * .2, .1);
      } else if (stationIndex === 1) {
        bottle.position.set(-1.25 + progress * 2.5, 1.08, 0);
      } else if (stationIndex === 2) {
        const angle = THREE.MathUtils.degToRad(machine.rotaryAngle || 0);
        bottle.position.set(Math.cos(angle) * .73, 1.02, Math.sin(angle) * .73);
      } else if (stationIndex === 3 || stationIndex === 4) {
        bottle.position.set(0, .46, .35);
      } else if (stationIndex === 5) {
        bottle.position.set(.25, .46, .2);
        bottle.userData.cap.position.y = 1.25 - progress * .2;
      } else if (stationIndex === 6) {
        bottle.position.set(0, .46, .18);
        bottle.userData.cap.rotation.y = THREE.MathUtils.degToRad(machine.tighteningAngle || 0);
      } else {
        bottle.position.set(-.95 + progress * 2.1, .7 - progress * .12, 0);
      }
      const aLevel = stationIndex < 3 ? 0 : stationIndex === 3
        ? machine.liquidA / 60 : 1;
      const bLevel = stationIndex < 4 ? 0 : stationIndex === 4
        ? machine.liquidB / 40 : 1;
      bottle.userData.liquidA.scale.y = Math.max(.02, aLevel);
      bottle.userData.liquidB.scale.y = Math.max(.02, bLevel);
    }
    const rotary = stationRoot.getObjectByName('rotary-index');
    if (rotary) {
      const target = -THREE.MathUtils.degToRad(machine.rotaryAngle || 0);
      rotary.rotation.y = dampAngle(rotary.rotation.y, target, 9, delta);
    }
    renderer.render(scene, camera);
  }

  new ResizeObserver(resize).observe(ui.detailCanvas);
  return {select, render, resize};
}

function createBottle() {
  const root = new THREE.Group();
  const glass = new THREE.Mesh(
    new THREE.CylinderGeometry(.18, .21, .72, 16),
    new THREE.MeshPhysicalMaterial({color: 0xcfefff, transparent: true, opacity: .36, roughness: .12, transmission: .35})
  );
  glass.position.y = .46;
  const neck = new THREE.Mesh(
    new THREE.CylinderGeometry(.1, .12, .24, 14),
    standardMaterial(0xd9f3ff, {transparent: true, opacity: .5, metalness: .05})
  );
  neck.position.y = .93;
  const liquidA = new THREE.Mesh(
    new THREE.CylinderGeometry(.155, .18, .48, 14),
    standardMaterial(0x2f8fe5, {transparent: true, opacity: .86, metalness: .05})
  );
  liquidA.position.y = .28;
  const liquidB = new THREE.Mesh(
    new THREE.CylinderGeometry(.155, .17, .32, 14),
    standardMaterial(0x8c64d8, {transparent: true, opacity: .88, metalness: .05})
  );
  liquidB.position.y = .62;
  const cap = new THREE.Mesh(
    new THREE.CylinderGeometry(.12, .12, .1, 14),
    standardMaterial(0xe7ba58, {metalness: .62, roughness: .35})
  );
  cap.position.y = 1.08;
  cap.visible = false;
  root.add(glass, neck, liquidA, liquidB, cap);
  root.userData = {liquidA, liquidB, cap, target: new THREE.Vector3()};
  root.visible = false;
  return root;
}

function syncBottles(records, meshes, parent, delta) {
  while (meshes.length < records.length) {
    const bottle = createBottle();
    parent.add(bottle);
    meshes.push(bottle);
  }
  records.forEach((record, index) => {
    const bottle = meshes[index];
    bottle.visible = record.lifecycle !== 'COMPLETED';
    if (!bottle.visible) return;
    bottle.userData.target.copy(bottlePosition(record, index));
    const blend = 1 - Math.exp(-8 * delta);
    bottle.position.lerp(bottle.userData.target, blend);
    const aLevel = record.stage < 3 ? 0 : record.stage === 3
      ? (latestState.machines[3].liquidA / 60) : 1;
    const bLevel = record.stage < 4 ? 0 : record.stage === 4
      ? (latestState.machines[4].liquidB / 40) : 1;
    bottle.userData.liquidA.visible = aLevel > .01;
    bottle.userData.liquidB.visible = bLevel > .01;
    bottle.userData.cap.visible = record.stage >= 5;
    bottle.userData.liquidA.scale.y = Math.max(.02, aLevel);
    bottle.userData.liquidB.scale.y = Math.max(.02, bLevel);
  });
  for (let index = records.length; index < meshes.length; index += 1) meshes[index].visible = false;
}

function bottlePosition(record, visualIndex) {
  if (record.stage < 0) return new THREE.Vector3(-10.8 - visualIndex * .45, .53, .85);
  const stage = Math.max(0, Math.min(7, record.stage));
  if (stage === 2) {
    const angle = THREE.MathUtils.degToRad((latestState.machines[2].rotaryAngle || 0) + visualIndex * 60);
    return new THREE.Vector3(STATION_X[2] + Math.cos(angle) * .75, .72, Math.sin(angle) * .75);
  }
  const nextX = stage < 7 ? STATION_X[stage + 1] : STATION_X[stage] + 1.2;
  const held = Math.min(.9, Math.max(0, Number(record.progress) / 100));
  return new THREE.Vector3(THREE.MathUtils.lerp(STATION_X[stage], nextX, held), .53, 0);
}

function dampAngle(current, target, lambda, delta) {
  let difference = (target - current + Math.PI) % (Math.PI * 2) - Math.PI;
  if (difference < -Math.PI) difference += Math.PI * 2;
  return current + difference * (1 - Math.exp(-lambda * delta));
}

function selectStation(index) {
  selectedStation = Math.max(0, Math.min(7, index));
  ui.detailPanel.hidden = false;
  ui.workspace.classList.add('detail-open');
  document.body.dataset.selectedStation = String(selectedStation);
  sceneBundle?.focusStation(selectedStation);
  detailSceneBundle?.select(selectedStation);
  renderSelectedMachine();
  if (latestState) renderState(latestState);
}

function returnToOverview() {
  selectedStation = null;
  ui.detailPanel.hidden = true;
  ui.workspace.classList.remove('detail-open');
  delete document.body.dataset.selectedStation;
  sceneBundle?.resetOverview();
  for (const button of ui.stationNav.querySelectorAll('button')) {
    button.classList.remove('selected');
  }
}

ui.stationNav.addEventListener('click', event => {
  const button = event.target.closest('button[data-station]');
  if (button) selectStation(Number(button.dataset.station));
});
ui.overview.addEventListener('click', returnToOverview);
ui.closeDetail.addEventListener('click', returnToOverview);
ui.teamGrid.addEventListener('click', event => {
  const card = event.target.closest('[data-team-ip]');
  if (card) openTeamIpDetail(Number(card.dataset.teamIp));
});
ui.teamGrid.addEventListener('keydown', event => {
  if (event.key !== 'Enter' && event.key !== ' ') return;
  const card = event.target.closest('[data-team-ip]');
  if (!card) return;
  event.preventDefault();
  openTeamIpDetail(Number(card.dataset.teamIp));
});
ui.teamClose.addEventListener('click', closeTeamIpDetail);
ui.teamDialog.addEventListener('cancel', event => {
  event.preventDefault();
  closeTeamIpDetail();
});

try {
  sceneBundle = createScene();
  detailSceneBundle = createDetailScene();
} catch (error) {
  ui.error.hidden = false;
  ui.error.textContent = `WebGL scene unavailable: ${error.message}`;
  setConnection('is-offline', '3D ERROR');
}

pollState();
setInterval(pollState, 150);
