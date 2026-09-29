// Network setup screen, shared by the desktop agent and the desktop client.
// The main process decides when to show it (no network yet, or the user
// asked for the invite / to leave) and exposes window.mccSetup via preload.
import QRCode from 'qrcode';
import {
  decodeInvite,
  encodeInvite,
  newNetworkId,
  parseFirebaseSnippet,
  parseTurnServers,
  validateNetwork,
  type NetworkConfig,
} from '../network.js';
import { testNetwork } from '../signaling.js';

type Role = 'agent' | 'client';

declare global {
  interface Window {
    mccSetup: {
      role: Role;
      getNetwork: () => Promise<NetworkConfig | null>;
      getDeviceName: () => Promise<string>;
      getAutostart: () => Promise<boolean>;
      saveNetwork: (cfg: NetworkConfig, opts: { deviceName?: string; autostart?: boolean }) => Promise<void>;
      leaveNetwork: () => Promise<void>;
      done: () => void;
    };
  }
}

const api = window.mccSetup;
const app = document.getElementById('app')!;
const mode = new URLSearchParams(location.search).get('mode') ?? 'first';
// Generated up front so it can be shown in the TURN section: a TURN worker
// only serves the network id it was deployed with (ALLOWED_NETWORK_ID).
const pendingNetworkId = newNetworkId();

const esc = (s: string) => s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!);
const $ = <T extends HTMLElement>(sel: string) => app.querySelector(sel) as T;

function brand() {
  return `<div class="brand">CAMERAS<b>//</b>CONTROLLER · ${api.role === 'agent' ? 'АГЕНТ' : 'КЛИЕНТ'}</div>`;
}

function showMsg(kind: 'err' | 'ok' | 'warn', text: string) {
  const el = $<HTMLElement>('#msg');
  el.className = `msg ${kind}`;
  el.textContent = text;
  el.hidden = false;
}

async function busy(btn: HTMLButtonElement, label: string, fn: () => Promise<void>) {
  const old = btn.textContent;
  btn.disabled = true;
  btn.textContent = label;
  try {
    await fn();
  } catch (err) {
    showMsg('err', (err as Error).message);
  } finally {
    btn.disabled = false;
    btn.textContent = old;
  }
}

// ─────────────── client: first run ───────────────

function renderClientFirst(tab: 'create' | 'join' = 'create') {
  app.innerHTML = `${brand()}
    <h1>Ваша сеть камер</h1>
    <p>Сеть — это ваши собственные устройства и ваш собственный Firebase-проект. Никто, кроме устройств с кодом приглашения, её не видит.
    Пошаговая инструкция — в файле SETUP.md репозитория.</p>
    <div class="tabs" role="tablist">
      <button class="tab" role="tab" data-tab="create" aria-selected="${tab === 'create'}">Создать новую сеть</button>
      <button class="tab" role="tab" data-tab="join" aria-selected="${tab === 'join'}">Подключиться по коду</button>
    </div>
    <div class="card">${tab === 'create' ? createForm() : joinForm()}</div>
    <div id="msg" hidden></div>`;
  app.querySelectorAll<HTMLButtonElement>('.tab').forEach((b) => (b.onclick = () => renderClientFirst(b.dataset.tab as 'create' | 'join')));
  if (tab === 'create') wireCreate();
  else wireJoin();
}

function createForm() {
  return `
    <label for="name">Название сети <small>— только для вас</small></label>
    <input type="text" id="name" value="Моя сеть" maxlength="60">
    <label for="fb">Конфиг Firebase <small>— Project settings → Your apps → SDK setup and configuration → Config</small></label>
    <textarea id="fb" rows="9" spellcheck="false" placeholder='const firebaseConfig = {
  apiKey: "…",
  authDomain: "…",
  databaseURL: "https://…firebasedatabase.app",
  projectId: "…",
  appId: "…"
};'></textarea>
    <details>
      <summary>TURN-сервер (нужен для мобильного интернета и строгих NAT)</summary>
      <p style="margin-top:10px">Заполните одно из двух — см. docs/TURN.md. Без TURN камеры работают в пределах одной сети и через большинство домашних роутеров.</p>
      <label>ID будущей сети <small>— значение секрета ALLOWED_NETWORK_ID для воркера</small></label>
      <input type="text" readonly value="${pendingNetworkId}" onclick="this.select()">
      <label for="worker">Адрес TURN-воркера Cloudflare</label>
      <input type="text" id="worker" placeholder="https://cc-turn.<ваш-поддомен>.workers.dev">
      <label for="static">Или статические TURN-серверы (JSON)</label>
      <textarea id="static" rows="4" spellcheck="false" placeholder='[{"urls":"turn:turn.example.com:3478","username":"…","credential":"…"}]'></textarea>
    </details>
    <div class="row"><button class="primary" id="go">Проверить и создать сеть</button></div>`;
}

function readTurn() {
  const worker = ($<HTMLInputElement>('#worker').value || '').trim();
  const stat = ($<HTMLTextAreaElement>('#static').value || '').trim();
  const turn: NetworkConfig['turn'] = {};
  if (worker) turn.workerUrl = worker.replace(/\/+$/, '');
  if (stat) {
    try {
      turn.servers = parseTurnServers(stat);
    } catch (err) {
      throw new Error(`TURN: ${(err as Error).message}`);
    }
  }
  return turn.workerUrl || turn.servers ? turn : undefined;
}

function wireCreate() {
  const go = $<HTMLButtonElement>('#go');
  go.onclick = () =>
    busy(go, 'Проверяю подключение…', async () => {
      const firebase = parseFirebaseSnippet($<HTMLTextAreaElement>('#fb').value);
      const cfg = validateNetwork({
        v: 1,
        name: $<HTMLInputElement>('#name').value.trim() || 'Моя сеть',
        firebase,
        networkId: pendingNetworkId,
        turn: readTurn(),
      });
      await testNetwork(cfg);
      await api.saveNetwork(cfg, {});
      renderInvite(cfg, true);
    });
}

function joinForm() {
  return `
    <label for="code">Код приглашения <small>— откройте «Пригласить устройство» на устройстве, которое уже в сети</small></label>
    <textarea id="code" rows="6" spellcheck="false" placeholder="CC1.…"></textarea>
    <div class="row"><button class="primary" id="go">Подключиться</button></div>`;
}

function wireJoin() {
  const go = $<HTMLButtonElement>('#go');
  go.onclick = () =>
    busy(go, 'Проверяю код…', async () => {
      const cfg = decodeInvite($<HTMLTextAreaElement>('#code').value);
      await testNetwork(cfg);
      await api.saveNetwork(cfg, {});
      api.done();
    });
}

// ─────────────── agent: first run ───────────────

async function renderAgentFirst() {
  const [name, autostart] = await Promise.all([api.getDeviceName(), api.getAutostart()]);
  app.innerHTML = `${brand()}
    <h1>Подключить эту камеру к своей сети</h1>
    <p>После подключения этот компьютер станет камерой вашей сети CamerasController:</p>
    <ul class="list">
      <li>камера и микрофон включаются <b>только</b> пока их смотрят с вашего клиента;</li>
      <li>в области уведомлений всегда видна иконка агента, во время просмотра она становится красной и появляется уведомление;</li>
      <li>отключиться от сети или закрыть агент можно в меню этой иконки.</li>
    </ul>
    <div class="card">
      <label for="code">Код приглашения <small>— «Пригласить устройство» в клиенте</small></label>
      <textarea id="code" rows="6" spellcheck="false" placeholder="CC1.…"></textarea>
      <label for="dname">Имя этого устройства</label>
      <input type="text" id="dname" value="${esc(name)}" maxlength="100">
      <label class="check"><input type="checkbox" id="auto" ${autostart ? 'checked' : ''}> Запускать агент при входе в систему</label>
      <label class="check"><input type="checkbox" id="consent"> Это моё устройство (или у меня есть согласие его владельца и людей, которые могут попасть в кадр), и я разрешаю передавать с него видео и звук в мою сеть.</label>
      <div class="row"><button class="primary" id="go">Подключить</button></div>
    </div>
    <div id="msg" hidden></div>`;
  const go = $<HTMLButtonElement>('#go');
  go.onclick = () =>
    busy(go, 'Проверяю код…', async () => {
      if (!$<HTMLInputElement>('#consent').checked) throw new Error('Подтвердите, что имеете право подключить это устройство.');
      const cfg = decodeInvite($<HTMLTextAreaElement>('#code').value);
      const deviceName = $<HTMLInputElement>('#dname').value.trim() || name;
      await testNetwork(cfg);
      await api.saveNetwork(cfg, { deviceName, autostart: $<HTMLInputElement>('#auto').checked });
      api.done();
    });
}

// ─────────────── invite / manage ───────────────

function renderInvite(cfg: NetworkConfig, fresh = false) {
  const code = encodeInvite(cfg);
  app.innerHTML = `${brand()}
    <h1>${fresh ? 'Сеть создана' : 'Пригласить устройство'}</h1>
    <p>Отсканируйте QR на телефоне или вставьте код в агент/клиент на другом устройстве. <b>Код — это пароль от сети:</b> отправляйте его только себе.</p>
    <div class="card">
      <div class="invite">
        <canvas id="qr"></canvas>
        <div>
          <label for="code">Код приглашения · «${esc(cfg.name)}»</label>
          <textarea id="code" readonly spellcheck="false">${esc(code)}</textarea>
          <div class="row"><button class="ghost" id="copy">Скопировать код</button></div>
        </div>
      </div>
      ${cfg.turn ? '' : '<div class="msg warn">TURN не настроен: камеры за мобильным интернетом могут не подключаться. См. docs/TURN.md.</div>'}
    </div>
    <div class="row">
      <button class="primary" id="done">${fresh ? 'Открыть пульт' : 'Готово'}</button>
      ${fresh ? '' : '<button class="ghost danger" id="leave">Отключить это устройство от сети</button>'}
    </div>
    <div id="msg" hidden></div>`;
  const qr = $<HTMLCanvasElement>('#qr');
  QRCode.toCanvas(qr, code, { errorCorrectionLevel: 'L', margin: 1, width: 480 }).then(() => {
    qr.style.width = qr.style.height = '240px'; // drawn at 2x for sharpness
  }).catch(() => {
    $<HTMLCanvasElement>('#qr').replaceWith(Object.assign(document.createElement('p'), { textContent: 'Код слишком длинный для QR — используйте текст.' }));
  });
  $<HTMLButtonElement>('#copy').onclick = async () => {
    await navigator.clipboard.writeText(code);
    showMsg('ok', 'Код скопирован. Не публикуйте его — он даёт доступ ко всем камерам сети.');
  };
  $<HTMLButtonElement>('#done').onclick = () => api.done();
  const leave = app.querySelector<HTMLButtonElement>('#leave');
  if (leave) leave.onclick = () => confirmLeave();
}

async function renderManageAgent() {
  const cfg = await api.getNetwork();
  app.innerHTML = `${brand()}
    <h1>Сеть «${esc(cfg?.name ?? '—')}»</h1>
    <p>Этот компьютер работает как камера этой сети. Если отключиться, агент перестанет быть виден и удалит сохранённый код.</p>
    <div class="row">
      <button class="ghost danger" id="leave">Отключиться от сети</button>
      <button class="primary" id="done">Закрыть</button>
    </div>
    <div id="msg" hidden></div>`;
  $<HTMLButtonElement>('#leave').onclick = () => confirmLeave();
  $<HTMLButtonElement>('#done').onclick = () => api.done();
}

function confirmLeave() {
  if (!confirm('Отключить это устройство от сети? Сохранённый код будет удалён, для повторного подключения понадобится новый код приглашения.')) return;
  api.leaveNetwork();
}

// ─────────────── entry ───────────────

(async () => {
  const cfg = await api.getNetwork();
  if (cfg && mode === 'invite' && api.role === 'client') return renderInvite(cfg);
  if (cfg && mode === 'manage' && api.role === 'agent') return renderManageAgent();
  if (api.role === 'agent') return renderAgentFirst();
  return renderClientFirst();
})();
