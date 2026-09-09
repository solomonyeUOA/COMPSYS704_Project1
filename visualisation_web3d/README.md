# M1 Experimental Web3D Visualisation

This directory is an optional proof of concept for the **Hierarchical
Automated Bottling System 3D Monitoring HMI**. The validated Swing
visualisation in `visualisation/` remains the canonical production UI and is
not modified or replaced.

## Safety boundary

Web3D is read-only monitoring. The Java server binds only to `127.0.0.1`, and
the browser can only make `GET` requests to `/api/state` and the static asset
server. There is no action endpoint, no `POST` control path and no connection
to Controller or Plant actuator signals.

The snapshot adapter reuses `ABSVisualisationFlowModel` and
`ABSVisualisationTeamIpModel` semantics. Model state is captured as immutable
JSON; the browser interpolates geometry between snapshots but never advances
the real completed count.

## Phase 1 telemetry mode

The canonical SystemJ visualisation CD is deliberately unchanged in this
experiment. Start with `--demo` to use the explicit **DEMO / TEST ONLY** state
source. It sends representative status edges through the same Java update
boundary and visual reconciliation model; it is not real Plant telemetry.

Without `--demo`, the server waits for calls to its read-only update methods:

- `updateStatus(String, int)`
- `updateRequiredBottles(int)`
- `updateCompletedBottles(int)`
- `updateFtEvidence(String)`

A later approved phase can fan out the existing `VIZ_*` values to these
methods without changing Controller ownership or adding browser controls.

## Three.js

The 3D renderer uses Three.js `0.160.0` as a local ES module at
`gui/vendor/three.module.min.js`. It is loaded by `gui/app.js` with:

```javascript
import * as THREE from './vendor/three.module.min.js';
```

No npm, Node.js backend, CDN connection or frontend build step is required.
The upstream MIT license is retained beside the vendored module.

## Compile and launch with Java 8

From the repository root, use the project-pinned Temurin Java 8u502 and the
verified SystemJ classpath. If the normal project build has already populated
`build/classes`, compile the three experimental files with the existing M1
model classes on the classpath:

```powershell
$javaHomeProject = 'C:\Program Files\Eclipse Adoptium\jdk-8.0.502.7-hotspot'
$systemJLib = 'D:\COMPSYS704\COMPSYS704_Project1_SystemJ_lib'

& "$javaHomeProject\bin\javac.exe" `
  -cp "build/classes;$systemJLib/*" `
  -d build/classes `
  visualisation/ABSVisualisationFlowModel.java `
  visualisation/ABSVisualisationTeamIpModel.java `
  visualisation_web3d/ABSWebSnapshot.java `
  visualisation_web3d/ABSVisualisationWebServer.java `
  visualisation_web3d/ABSVisualisationWebServerSelfTest.java

& "$javaHomeProject\bin\java.exe" `
  -cp "build/classes;$systemJLib/*" `
  ABSVisualisationWebServer --demo
```

The default browser URL is:

```text
http://127.0.0.1:18081/
```

If port 18081 is busy, the server tries the next nine loopback ports and
prints the selected URL. Use `--no-browser` to suppress automatic browser
opening. Override the first port with `-Dm1.web3d.port=<port>`.

## Eclipse launch

1. Refresh the project and run the normal project build so the validated M1
   visualisation model classes are available.
2. Create **Run Configurations → Java Application**.
3. Name it `M1 Web3D Visualisation (Experimental)`.
4. Select this project and main class `ABSVisualisationWebServer`.
5. Set the working directory to the repository root.
6. Add program argument `--demo` for the Phase 1 demonstration.
7. Use the Temurin Java 8u502 JRE and run. The browser opens automatically.

Stopping this Java launch stops the optional HTTP bridge. The Swing
visualisation remains independently launchable and unchanged.

## What the prototype demonstrates

- complete isometric low-poly production-line overview;
- Loader, Conveyor, six-position Rotary, Filler A, Filler B, Lid, Capper and
  Unloader geometry;
- one or more symbolic bottles driven by the existing flow model;
- rotary and bottle interpolation between telemetry snapshots;
- status colours, required/completed counts and a single shared browser-side
  telemetry snapshot;
- a two-level, single-page hierarchy: the complete line remains visible at
  Level 1, while raycasting or the accessible station selector opens a
  machine-specific Level 2 detail view;
- selected-station highlighting, a deliberately subtle camera focus and an
  `OVERVIEW` view-reset button that never controls the plant;
- distinct Loader, Conveyor, Rotary, Filler A, Filler B, Lid, Capper and
  Unloader detail animations rather than a generic machine card;
- selectable M2 Digital Twin, M3 Fault Tolerance and M4 Two-Size information
  views that retain the established LIVE / STATIC CAPABILITY / NOT EXPOSED TO
  M1 evidence rules;
- local 5–10 Hz state polling separated from `requestAnimationFrame`.

## Known Phase 1 limitations

- SystemJ still feeds only the canonical Swing view; live Web3D fan-out is not
  wired until a later approved phase.
- M2 live Digital Twin state and M4 live bottle size are not exposed to M1, so
  those cards remain capability representations.
- Geometry is symbolic and deliberately not a physical simulation.
- The demonstration uses an established 60/40 fill visual assumption and does
  not claim liquid-level sensor telemetry.
