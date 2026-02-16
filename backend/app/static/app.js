async function api(path, options = {}) {
  const response = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  });
  if (!response.ok) {
    const body = await response.text();
    throw new Error(`${response.status} ${body}`);
  }
  return response.json();
}

const el = {
  autoEnabled: document.getElementById('autoEnabled'),
  patchOwner: document.getElementById('patchOwner'),
  patchRepo: document.getElementById('patchRepo'),
  ghPublishEnabled: document.getElementById('ghPublishEnabled'),
  ghOwner: document.getElementById('ghOwner'),
  ghRepo: document.getElementById('ghRepo'),
  ghToken: document.getElementById('ghToken'),
  catalogInfo: document.getElementById('catalogInfo'),
  catalogList: document.getElementById('catalogList'),
  stagesList: document.getElementById('stagesList'),
  jobsList: document.getElementById('jobsList'),
  currentRelease: document.getElementById('currentRelease'),
  debugOutput: document.getElementById('debugOutput'),
  refreshCatalogBtn: document.getElementById('refreshCatalogBtn'),
  saveConfigBtn: document.getElementById('saveConfigBtn'),
  triggerPatchBtn: document.getElementById('triggerPatchBtn'),
  testBackendBtn: document.getElementById('testBackendBtn'),
  testGithubBtn: document.getElementById('testGithubBtn'),
};

let latestState = null;

function renderCatalog(state) {
  const catalog = state.catalog || [];
  const selectedMap = new Map((state.config?.selected_apps || []).map((item) => [item.package, item]));

  el.catalogInfo.textContent = `${catalog.length} app(s) detectee(s) par RVX`;
  if (!catalog.length) {
    el.catalogList.innerHTML = '<div class="muted">Catalogue vide. Clique sur "Rafraichir catalogue RVX".</div>';
    return;
  }

  el.catalogList.innerHTML = catalog
    .map((app) => {
      const selected = selectedMap.get(app.appPackage);
      const enabled = !!selected;
      const auto = selected ? !!selected.auto : true;
      const arch = selected?.arch || '';

      return `
        <div class="app-row" data-package="${app.appPackage}">
          <div>
            <strong>${app.appName}</strong>
            <div class="muted">${app.appPackage}</div>
          </div>
          <label>
            <span>Selection</span>
            <input type="checkbox" class="app-enabled" ${enabled ? 'checked' : ''} />
          </label>
          <label>
            <span>Auto</span>
            <input type="checkbox" class="app-auto" ${auto ? 'checked' : ''} />
          </label>
          <label>
            <span>Arch</span>
            <select class="app-arch">
              <option value="" ${arch === '' ? 'selected' : ''}>auto</option>
              <option value="arm64-v8a" ${arch === 'arm64-v8a' ? 'selected' : ''}>arm64-v8a</option>
              <option value="armeabi-v7a" ${arch === 'armeabi-v7a' ? 'selected' : ''}>armeabi-v7a</option>
              <option value="x86_64" ${arch === 'x86_64' ? 'selected' : ''}>x86_64</option>
            </select>
          </label>
        </div>`;
    })
    .join('');
}

function renderStages(state) {
  const stages = state.stages || [];
  if (!stages.length) {
    el.stagesList.innerHTML = '<div class="muted">Aucun stage pour l\'instant.</div>';
    return;
  }

  el.stagesList.innerHTML = stages
    .map((stage) => {
      const apps = (stage.apps || []).map((a) => `- ${a.app_name || a.package} (${a.file_name})`).join('\n');
      const controls =
        stage.status === 'ready'
          ? `<button class="primary" onclick="broadcastStage('${stage.id}')">Diffuser</button>
             <button class="warn" onclick="cancelStage('${stage.id}')">Annuler</button>`
          : `<span class="muted">${stage.status}</span>`;

      return `<div class="stage-row">
        <strong>${stage.id}</strong>
        <div class="muted">${stage.created_at}</div>
        <pre>${apps || 'Aucun APK'}</pre>
        <div class="grouped">${controls}</div>
      </div>`;
    })
    .join('');
}

function renderJobs(state) {
  const jobs = state.jobs || [];
  if (!jobs.length) {
    el.jobsList.innerHTML = '<div class="muted">Aucun job.</div>';
    return;
  }

  el.jobsList.innerHTML = jobs
    .slice(0, 5)
    .map((job) => {
      const logs = (job.logs || []).slice(-20).join('\n');
      return `<div class="job-row">
        <strong>${job.id}</strong>
        <div>Trigger: ${job.trigger}</div>
        <div>Status: <strong>${job.status}</strong></div>
        <div class="muted">${job.created_at} -> ${job.finished_at || 'en cours'}</div>
        <pre>${logs || 'Aucun log'}</pre>
      </div>`;
    })
    .join('');
}

function renderState(state) {
  latestState = state;

  const config = state.config || {};
  el.autoEnabled.checked = !!config.auto_enabled;
  el.patchOwner.value = config.rvx_patch_source?.owner || 'inotia00';
  el.patchRepo.value = config.rvx_patch_source?.repo || 'revanced-patches';
  el.ghPublishEnabled.checked = !!config.github_publish?.enabled;
  el.ghOwner.value = config.github_publish?.owner || '';
  el.ghRepo.value = config.github_publish?.repo || '';
  el.ghToken.value = config.github_publish?.token || '';

  renderCatalog(state);
  renderStages(state);
  renderJobs(state);

  el.currentRelease.textContent = JSON.stringify(state.current_release, null, 2);
}

function collectSelectedApps() {
  const rows = document.querySelectorAll('.app-row');
  const selected = [];
  rows.forEach((row) => {
    const enabled = row.querySelector('.app-enabled')?.checked;
    if (!enabled) return;
    const auto = !!row.querySelector('.app-auto')?.checked;
    const arch = row.querySelector('.app-arch')?.value || '';
    selected.push({
      package: row.getAttribute('data-package'),
      auto,
      arch: arch || null,
    });
  });
  return selected;
}

async function loadState() {
  try {
    const state = await api('/api/state');
    renderState(state);
  } catch (error) {
    el.debugOutput.textContent = `Erreur chargement state: ${error}`;
  }
}

async function refreshCatalog() {
  el.debugOutput.textContent = 'Refresh catalogue en cours...';
  try {
    const state = await api('/api/catalog/refresh', { method: 'POST' });
    renderState(state);
    el.debugOutput.textContent = 'Catalogue RVX rafraichi.';
  } catch (error) {
    el.debugOutput.textContent = `Erreur refresh catalogue: ${error}`;
  }
}

async function saveConfig() {
  const payload = {
    auto_enabled: el.autoEnabled.checked,
    rvx_patch_source: {
      owner: el.patchOwner.value.trim(),
      repo: el.patchRepo.value.trim(),
    },
    github_publish: {
      enabled: el.ghPublishEnabled.checked,
      owner: el.ghOwner.value.trim(),
      repo: el.ghRepo.value.trim(),
      token: el.ghToken.value.trim(),
      prerelease: false,
    },
    selected_apps: collectSelectedApps(),
  };

  try {
    const state = await api('/api/config', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
    renderState(state);
    el.debugOutput.textContent = 'Configuration sauvegardee.';
  } catch (error) {
    el.debugOutput.textContent = `Erreur save config: ${error}`;
  }
}

async function triggerPatch() {
  try {
    await saveConfig();
    const response = await api('/api/jobs/trigger', {
      method: 'POST',
      body: JSON.stringify({ packages: [] }),
    });
    el.debugOutput.textContent = `Job lance: ${response.job_id}`;
    await loadState();
  } catch (error) {
    el.debugOutput.textContent = `Erreur trigger patch: ${error}`;
  }
}

async function testSource(sourceId) {
  try {
    const response = await api('/api/debug/repository', {
      method: 'POST',
      body: JSON.stringify({ source_id: sourceId }),
    });
    el.debugOutput.textContent = JSON.stringify(response, null, 2);
  } catch (error) {
    el.debugOutput.textContent = `Erreur test source: ${error}`;
  }
}

async function broadcastStage(stageId) {
  try {
    await api(`/api/stages/${stageId}/broadcast`, { method: 'POST' });
    el.debugOutput.textContent = `Stage ${stageId} diffuse.`;
    await loadState();
  } catch (error) {
    el.debugOutput.textContent = `Erreur diffusion: ${error}`;
  }
}

async function cancelStage(stageId) {
  try {
    await api(`/api/stages/${stageId}/cancel`, { method: 'POST' });
    el.debugOutput.textContent = `Stage ${stageId} annule.`;
    await loadState();
  } catch (error) {
    el.debugOutput.textContent = `Erreur annulation: ${error}`;
  }
}

window.broadcastStage = broadcastStage;
window.cancelStage = cancelStage;

el.refreshCatalogBtn.addEventListener('click', refreshCatalog);
el.saveConfigBtn.addEventListener('click', saveConfig);
el.triggerPatchBtn.addEventListener('click', triggerPatch);
el.testBackendBtn.addEventListener('click', () => testSource('backend_main'));
el.testGithubBtn.addEventListener('click', () => testSource('github_debug'));

loadState();
setInterval(loadState, 7000);
