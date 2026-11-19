(function () {
'use strict';
const A = window.Android || {
  now: () => Date.now(), fmt: (ms) => new Date(ms).toTimeString().slice(0, 8), zone: () => 'WIB', getExtra: () => 0, fontData: () => '',
  loadSettings: () => '{}', saveSettings() {}, setConfig() {}, setCameraActive() {},
  capture() {}, setGps() {}, getLocation: () => '{"state":"off"}', perms: () => '{"camera":true,"loc":true}',
  requestPerms() {}, setCanGoBack() {}, openGallery() {}, focus() {}
};
const $ = (s, r) => (r || document).querySelector(s);
const $$ = (s, r) => Array.from((r || document).querySelectorAll(s));

/* ---------- state ---------- */
const STAMP = { text: '', font: 'sans-serif', size: 3.6, color: '#ffffff', bold: true, pos: 8,
  bg: false, bgColor: '#000000', bgOpacity: 45, showDate: true, showTime: true, showZone: true, timeSep: '.',
  showGps: true, dateFormat: 'dd MMMM yyyy' };
const DEF = { v: 2, a: STAMP, b: STAMP, delay: 5, gpsOn: true, gpsFormat: 'addr', manualAddr: '', manualLat: '', manualLon: '',
  ratio: '4:3', res: 'max', quality: 95 };
let S = (function () {
  let o = {};
  try { o = JSON.parse(A.loadSettings() || '{}'); } catch (e) {}
  if (o.v !== 2) { // migrasi dari versi lama: pertahankan pengaturan umum + teks A/B
    const keep = {};
    ['delay', 'gpsOn', 'manualLat', 'manualLon', 'ratio', 'res', 'quality'].forEach(k => { if (o[k] !== undefined) keep[k] = o[k]; });
    keep.a = { text: (o.a && o.a.text) || '' }; keep.b = { text: (o.b && o.b.text) || '' };
    o = keep;
  }
  return Object.assign({}, DEF, o, { v: 2, a: Object.assign({}, STAMP, o.a), b: Object.assign({}, STAMP, o.b) });
})();
let saveT;
function save() { clearTimeout(saveT); saveT = setTimeout(() => A.saveSettings(JSON.stringify(S)), 250); }
function pushConfig() { A.setConfig(JSON.stringify({ ratio: S.ratio, res: S.res, quality: S.quality })); }

/* ---------- util ---------- */
function toast(m) { const t = $('#toast'); t.textContent = m; t.classList.add('show'); clearTimeout(toast.t); toast.t = setTimeout(() => t.classList.remove('show'), 2800); }
const fmt5 = (n) => n.toFixed(5);
function manualCoords() {
  const la = parseFloat(String(S.manualLat).replace(',', '.')), lo = parseFloat(String(S.manualLon).replace(',', '.'));
  if (isNaN(la) || isNaN(lo) || Math.abs(la) > 90 || Math.abs(lo) > 180) return '';
  return fmt5(la) + ' ' + fmt5(lo);
}
function compose(mode, addr, coords) {
  const out = [];
  if (mode === 'coord') { if (coords) out.push(coords); }
  else if (mode === 'both') { if (addr) out.push(addr); if (coords) out.push(coords); }
  else { const x = addr || coords; if (x) out.push(x); }
  return out;
}
let loc = { state: 'off' };
function gpsLines() {
  if (S.gpsOn) return loc.state === 'ok' ? compose(S.gpsFormat, loc.addr || '', fmt5(loc.lat) + ' ' + fmt5(loc.lon)) : [];
  return compose(S.gpsFormat, S.manualAddr.trim(), manualCoords());
}
function cssFont(id) { return id === 'noto-naskh' ? "'NotoNaskhArabicUI', sans-serif" : id; }

/* font Noto Naskh Arabic UI (dari assets/fonts atau font sistem HP) */
const notoRegular = (A.fontData && A.fontData(false)) || '';
function b64buf(d) { const b = atob(d), u = new Uint8Array(b.length); for (let i = 0; i < b.length; i++) u[i] = b.charCodeAt(i); return u.buffer; }
function loadNoto() {
  if (!window.FontFace || !notoRegular) return;
  const add = (d, w) => { try { const f = new FontFace('NotoNaskhArabicUI', b64buf(d), { weight: w }); f.load().then(() => { document.fonts.add(f); renderStamp(); }).catch(() => {}); } catch (e) {} };
  add(notoRegular, '400');
  const bd = A.fontData(true); if (bd) add(bd, '700');
}

/* ---------- navigasi ---------- */
let cur = 'camera', drawerOpen = false;
function updateBack() { A.setCanGoBack(drawerOpen || cur !== 'camera'); }
function openDrawer() { drawerOpen = true; $('#drawer').classList.add('open'); $('#scrim').classList.remove('hidden'); $$('#drawer button').forEach(b => b.classList.toggle('on', b.dataset.go === cur)); updateBack(); }
function closeDrawer() { drawerOpen = false; $('#drawer').classList.remove('open'); $('#scrim').classList.add('hidden'); updateBack(); }
function go(p) {
  if (p === 'gallery') { closeDrawer(); A.openGallery(); return; }
  cur = p;
  $$('.page').forEach(e => e.classList.toggle('active', e.id === 'p-' + p));
  document.body.classList.toggle('cam', p === 'camera');
  A.setCameraActive(p === 'camera');
  closeDrawer();
  if (p === 'camera') { renderAll(); checkPerm(); }
  if (p === 'gps') refreshGpsPage();
  if (p === 'delay') refreshDelay();
  const sc = $('#p-' + p + ' .scroll'); if (sc) sc.scrollTop = 0;
}
window.goBack = function () { if (drawerOpen) closeDrawer(); else if (cur !== 'camera') go('camera'); };
$('#btn-menu').onclick = openDrawer;
$('#scrim').onclick = closeDrawer;
$$('#drawer button').forEach(b => b.onclick = () => go(b.dataset.go));
$$('.back').forEach(b => b.onclick = () => go('camera'));
$('#btn-gal').onclick = () => A.openGallery();
$('#btn-ratio').onclick = () => go('ratio');

/* ---------- form Foto A / B ---------- */
const FONTS = [['sans-serif', 'Sans'], ['sans-serif-condensed', 'Sans Condensed'], ['serif', 'Serif'], ['monospace', 'Mono'],
  ['cursive', 'Tulisan tangan'], ['casual', 'Casual'], ['noto-naskh', 'Noto Naskh Arabic UI']];
const DFMT = [['dd MMMM yyyy', '17 November 2026'], ['dd/MM/yyyy', '17/11/2026'], ['yyyy-MM-dd', '2026-11-17'], ['dd MMM yyyy', '17 Nov 2026'], ['EEEE, dd MMMM yyyy', 'Selasa, 17 November 2026']];
function buildForm(key) {
  const page = $('#p-' + key), s = S[key], T = key.toUpperCase();
  const opt = (arr, v) => arr.map(o => `<option value="${o[0]}"${o[0] === v ? ' selected' : ''}>${o[1]}</option>`).join('');
  page.innerHTML = `
  <header class="bar"><button class="icon-btn back"><svg><use href="#i-back"/></svg></button><h1>Pengaturan Foto ${T}</h1></header>
  <div class="scroll">
    <div class="card">
      <div class="k">Baris atas (otomatis)</div>
      <label class="row"><span>Tanggal</span><input type="checkbox" class="sw" data-k="showDate"${s.showDate ? ' checked' : ''}></label>
      <label class="row"><span>Jam</span><input type="checkbox" class="sw" data-k="showTime"${s.showTime ? ' checked' : ''}></label>
      <label class="row"><span>Zona waktu (WIB/WITA/WIT)</span><input type="checkbox" class="sw" data-k="showZone"${s.showZone ? ' checked' : ''}></label>
      <label class="row"><span>Lokasi (di bawah jam)</span><input type="checkbox" class="sw" data-k="showGps"${s.showGps ? ' checked' : ''}></label>
      <div class="two">
        <label class="field"><span>Format tanggal</span><select data-k="dateFormat">${opt(DFMT, s.dateFormat)}</select></label>
        <label class="field"><span>Pemisah jam</span><select data-k="timeSep">${opt([['.', '17.31.46'], [':', '17:31:46']], s.timeSep)}</select></label>
      </div>
    </div>
    <div class="card">
      <label class="field"><span>Teks tambahan (tampil di bawah jam &amp; lokasi)</span><textarea data-k="text" placeholder="Contoh: Lokasi proyek">${s.text.replace(/</g, '&lt;')}</textarea></label>
    </div>
    <div class="card">
      <label class="field"><span>Font</span><select data-k="font">${opt(FONTS, s.font)}</select></label>
      ${notoRegular ? '' : '<div class="hint warn">Noto Naskh Arabic UI tidak ditemukan di HP ini, otomatis pakai Sans.</div>'}
      <label class="field"><span>Ukuran: <b data-v="size">${s.size}</b>%</span><input type="range" data-k="size" min="1.5" max="10" step="0.1" value="${s.size}"></label>
      <div class="two">
        <label class="field"><span>Warna teks</span><input type="color" data-k="color" value="${s.color}"></label>
        <label class="field"><span>Ketebalan</span><select data-k="bold"><option value="0"${s.bold ? '' : ' selected'}>Normal</option><option value="1"${s.bold ? ' selected' : ''}>Tebal</option></select></label>
      </div>
      <div class="k" style="margin-top:10px">Posisi</div>
      <div class="pos-grid">${[0,1,2,3,4,5,6,7,8].map(i => `<button data-pos="${i}" data-r="${(i / 3) | 0}" data-c="${i % 3}" class="${s.pos === i ? 'on' : ''}"></button>`).join('')}</div>
    </div>
    <div class="card">
      <label class="row"><span>Background timestamp</span><input type="checkbox" class="sw" data-k="bg"${s.bg ? ' checked' : ''}></label>
      <div class="two">
        <label class="field"><span>Warna background</span><input type="color" data-k="bgColor" value="${s.bgColor}"></label>
        <label class="field"><span>Opasitas: <b data-v="bgOpacity">${s.bgOpacity}</b>%</span><input type="range" data-k="bgOpacity" min="0" max="100" step="1" value="${s.bgOpacity}"></label>
      </div>
    </div>
    <p class="note">Tanggal dan jam otomatis. Lihat hasilnya langsung di preview kamera.</p>
    <div class="by">by adrian</div>
  </div>`;
  $('.back', page).onclick = () => go('camera');
  $$('[data-k]', page).forEach(el => {
    const k = el.dataset.k;
    const h = () => {
      let v = el.type === 'checkbox' ? el.checked : el.value;
      if (k === 'size' || k === 'bgOpacity') { v = parseFloat(v); const lb = $('[data-v="' + k + '"]', page); if (lb) lb.textContent = v; }
      if (k === 'bold') v = v === '1';
      S[key][k] = v; save();
    };
    el.addEventListener(el.type === 'range' || el.tagName === 'TEXTAREA' || el.type === 'color' ? 'input' : 'change', h);
  });
  $$('[data-pos]', page).forEach(b => b.onclick = () => {
    S[key].pos = +b.dataset.pos; save();
    $$('[data-pos]', page).forEach(x => x.classList.toggle('on', x === b));
  });
}

/* ---------- preview stempel (algoritma sama dengan StampRenderer.java) ---------- */
let pv = 'a', extra = 0;
function stampLines(s, ms) {
  const L = [];
  let dt = '';
  if (s.showDate) dt = A.fmt(ms, s.dateFormat);
  if (s.showTime) {
    const sp = s.timeSep === ':' ? ':' : '.';
    dt += (dt ? ' ' : '') + A.fmt(ms, 'HH' + sp + 'mm' + sp + 'ss');
    if (s.showZone) dt += ' ' + A.zone(ms);
  }
  if (dt) L.push(dt);
  if (s.showGps) gpsLines().forEach(x => L.push(x));
  s.text.split('\n').forEach(l => { if (l.trim()) L.push(l); });
  return L;
}
function ratioDims() { // frame preview selalu portrait (UI terkunci portrait)
  const W = innerWidth, H = innerHeight;
  if (S.ratio === 'full') return [W, H];
  const [x, y] = S.ratio.split(':').map(Number);
  return [Math.min(x, y), Math.max(x, y)];
}
function frameRect() {
  const W = innerWidth, H = innerHeight, [rw, rh] = ratioDims(), k = Math.min(W / rw, H / rh), w = rw * k, h = rh * k;
  return { x: (W - w) / 2, y: (H - h) / 2, w, h };
}
function hexA(hex, op) {
  const n = parseInt(hex.slice(1), 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${op / 100})`;
}
function wrapLines(ctx, raw, limit) {
  const out = [];
  raw.forEach(l => {
    if (ctx.measureText(l).width <= limit) { out.push(l); return; }
    let cur = '';
    l.split(' ').forEach(wd => {
      if (!wd) return;
      const t = cur ? cur + ' ' + wd : wd;
      if (cur && ctx.measureText(t).width > limit) { out.push(cur); cur = wd; } else cur = t;
    });
    if (cur) out.push(cur);
  });
  return out;
}
function renderStamp() {
  if (cur !== 'camera') return;
  const r = frameRect(), c = $('#stamp'), f = $('#frame'), dpr = window.devicePixelRatio || 1;
  [c, f].forEach(e => { e.style.left = r.x + 'px'; e.style.top = r.y + 'px'; e.style.width = r.w + 'px'; e.style.height = r.h + 'px'; });
  c.width = Math.round(r.w * dpr); c.height = Math.round(r.h * dpr);
  const ctx = c.getContext('2d');
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  const s = S[pv], now = A.now(), ms = pv === 'a' ? now : now + S.delay * 1000, raw = stampLines(s, ms);
  if (!raw.length) return;
  const FW = r.w, FH = r.h;
  // foto hasil = frame diputar 'extra' derajat searah jarum jam; gambar di koordinat foto lalu putar balik
  if (extra === 90) ctx.transform(0, -1, 1, 0, 0, FH);
  else if (extra === 270) ctx.transform(0, 1, -1, 0, FW, 0);
  else if (extra === 180) ctx.transform(-1, 0, 0, -1, FW, FH);
  const W = extra % 180 ? FH : FW, H = extra % 180 ? FW : FH;
  const fam = cssFont(s.font);
  let ts = s.size / 100 * Math.min(W, H), margin, pad, bw, asc, desc, lines = raw;
  for (let i = 0; i < 3; i++) {
    ctx.font = (s.bold ? 'bold ' : '') + ts + 'px ' + fam;
    const m = ctx.measureText('Mg');
    asc = m.fontBoundingBoxAscent || ts * 0.93; desc = m.fontBoundingBoxDescent || ts * 0.24;
    margin = ts * 0.6; pad = s.bg ? ts * 0.4 : 0;
    lines = wrapLines(ctx, raw, (W - 2 * margin - 2 * pad) * 0.8);
    let mx = 0; lines.forEach(l => { mx = Math.max(mx, ctx.measureText(l).width); });
    bw = mx + 2 * pad;
    const av = W - 2 * margin;
    if (bw <= av) break;
    ts *= av / bw;
  }
  const lh = (asc + desc) * 1.1, bh = lines.length * lh + 2 * pad, col = s.pos % 3, row = (s.pos / 3) | 0;
  const x0 = col === 0 ? margin : col === 1 ? (W - bw) / 2 : W - margin - bw;
  const y0 = row === 0 ? margin : row === 1 ? (H - bh) / 2 : H - margin - bh;
  if (s.bg) {
    ctx.fillStyle = hexA(s.bgColor, s.bgOpacity);
    const rad = ts * 0.25; ctx.beginPath();
    if (ctx.roundRect) ctx.roundRect(x0, y0, bw, bh, rad); else ctx.rect(x0, y0, bw, bh);
    ctx.fill();
  } else {
    ctx.shadowColor = 'rgba(0,0,0,.8)'; ctx.shadowBlur = ts * 0.1; ctx.shadowOffsetX = ctx.shadowOffsetY = ts * 0.04;
  }
  ctx.fillStyle = s.color; ctx.textBaseline = 'alphabetic';
  ctx.textAlign = col === 0 ? 'left' : col === 1 ? 'center' : 'right';
  const tx = col === 0 ? x0 + pad : col === 1 ? x0 + bw / 2 : x0 + bw - pad;
  lines.forEach((l, i) => ctx.fillText(l, tx, y0 + pad + i * lh + asc + (lh - (asc + desc)) / 2));
}
$$('#pv-seg button').forEach(b => b.onclick = () => {
  pv = b.dataset.pv; $$('#pv-seg button').forEach(x => x.classList.toggle('on', x === b)); renderStamp();
});

/* ---------- GPS ---------- */
function gpsChip() {
  const c = $('#chip-gps');
  if (!S.gpsOn) { c.textContent = (S.manualAddr.trim() || manualCoords()) ? 'GPS manual' : 'GPS OFF'; return; }
  c.textContent = { ok: 'GPS ±' + loc.acc + ' m', searching: 'GPS mencari…', off: 'Lokasi mati', noperm: 'Izin lokasi' }[loc.state] || 'GPS';
}
function pollGps() {
  if (!S.gpsOn) { gpsChip(); return; }
  try { loc = JSON.parse(A.getLocation()); } catch (e) {}
  gpsChip();
  if (cur === 'gps') refreshGpsPage();
}
function refreshGpsPage() {
  $('#gps-on').checked = S.gpsOn;
  $('#gps-fmt').value = S.gpsFormat;
  $('#gps-live').classList.toggle('hidden', !S.gpsOn);
  $('#gps-manual').classList.toggle('hidden', S.gpsOn);
  const st = { ok: 'Terkunci (akurasi ±' + loc.acc + ' m)', searching: 'Mencari sinyal…', off: 'Layanan lokasi HP mati', noperm: 'Izin lokasi belum diberikan' }[loc.state] || '-';
  $('#gps-state').textContent = st;
  $('#gps-addr').textContent = loc.state === 'ok' ? (loc.addr || 'Belum tersedia (butuh internet)') : '-';
  $('#gps-coord').textContent = loc.state === 'ok' ? fmt5(loc.lat) + ' ' + fmt5(loc.lon) : '-';
  $('#gps-perm').classList.toggle('hidden', loc.state !== 'noperm');
  const hint = $('#m-hint');
  if (!S.manualLat && !S.manualLon) hint.textContent = 'Isi alamat dan/atau koordinat. Kosong = tidak ditampilkan.';
  else hint.textContent = manualCoords() ? 'Koordinat di foto: ' + manualCoords() : 'Koordinat tidak valid (lat -90..90, lon -180..180).';
}
$('#gps-on').onchange = e => { S.gpsOn = e.target.checked; A.setGps(S.gpsOn); save(); pollGps(); refreshGpsPage(); };
$('#gps-fmt').onchange = e => { S.gpsFormat = e.target.value; save(); };
$('#gps-perm').onclick = () => A.requestPerms();
$('#m-addr').oninput = e => { S.manualAddr = e.target.value; save(); gpsChip(); };
$('#m-lat').oninput = e => { S.manualLat = e.target.value; save(); refreshGpsPage(); };
$('#m-lon').oninput = e => { S.manualLon = e.target.value; save(); refreshGpsPage(); };

/* ---------- delay ---------- */
function setDelay(v) { v = Math.max(0, Math.min(3600, Math.round(+v) || 0)); S.delay = v; save(); refreshDelay(); }
function refreshDelay() {
  if (document.activeElement !== $('#d-val')) $('#d-val').value = S.delay;
  $$('#d-chips button').forEach(b => b.classList.toggle('on', +b.dataset.v === S.delay));
  const n = A.now(), t = ms => A.fmt(ms, 'HH:mm:ss');
  $('#d-ex').textContent = 'Foto A  ' + t(n) + '\nFoto B  ' + t(n + S.delay * 1000);
  $('#d-ex').style.whiteSpace = 'pre';
}
[0, 1, 3, 5, 10, 30, 60].forEach(v => {
  const b = document.createElement('button'); b.dataset.v = v; b.textContent = v + ' dtk'; b.onclick = () => setDelay(v); $('#d-chips').appendChild(b);
});
$('#d-minus').onclick = () => setDelay(S.delay - 1);
$('#d-plus').onclick = () => setDelay(S.delay + 1);
$('#d-val').oninput = e => { S.delay = Math.max(0, Math.min(3600, parseInt(e.target.value, 10) || 0)); save(); refreshDelay(); };
$('#d-val').onblur = () => setDelay(S.delay);

/* ---------- rasio & resolusi ---------- */
const RATIOS = ['4:3', '16:9', '3:2', '1:1', '9:16', '18:9', '20:9', 'full'];
const RES = [['max', 'Maksimal (sesuai kemampuan kamera HP)'], ['mid', 'Sedang (±8 MP)'], ['std', 'Standar (1440×1080 / 1920×1080)']];
function buildRatio() {
  const g = $('#r-grid'); g.innerHTML = '';
  RATIOS.forEach(r => {
    const b = document.createElement('button'); b.dataset.r = r;
    let w = 22, h = 22;
    if (r !== 'full' && r !== '1:1') { const [x, y] = r.split(':').map(Number), k = 30 / Math.max(x, y); w = x * k; h = y * k; }
    if (r === 'full') { w = 18; h = 32; }
    b.innerHTML = `<i style="width:${w}px;height:${h}px"></i><span>${r === 'full' ? 'Full' : r}</span>`;
    b.onclick = () => { S.ratio = r; save(); pushConfig(); markRatio(); };
    g.appendChild(b);
  });
  const o = $('#r-res'); o.innerHTML = '';
  RES.forEach(r => {
    const b = document.createElement('button'); b.dataset.r = r[0]; b.textContent = r[1];
    b.onclick = () => { S.res = r[0]; save(); pushConfig(); markRatio(); };
    o.appendChild(b);
  });
  $('#q-rng').oninput = e => { S.quality = +e.target.value; $('#q-val').textContent = S.quality; save(); };
}
function markRatio() {
  $$('#r-grid button').forEach(b => b.classList.toggle('on', b.dataset.r === S.ratio));
  $$('#r-res button').forEach(b => b.classList.toggle('on', b.dataset.r === S.res));
  $('#q-rng').value = S.quality; $('#q-val').textContent = S.quality;
  $('#btn-ratio').textContent = S.ratio === 'full' ? 'Full' : S.ratio;
  $('#r-info').textContent = S.res === 'std'
    ? 'Sisi pendek 1080 px (4:3 = 1440×1080, 16:9 = 1920×1080).'
    : 'Hasil dipotong ke rasio terpilih dari resolusi tertinggi yang tersedia.';
  renderStamp();
}

/* ---------- tap untuk fokus ---------- */
$('#p-camera').addEventListener('click', e => {
  if (e.target.closest('.top,.bottom,.perm') || drawerOpen) return;
  const r = frameRect();
  if (e.clientX < r.x || e.clientX > r.x + r.w || e.clientY < r.y || e.clientY > r.y + r.h) return;
  const f = $('#focus');
  f.style.left = e.clientX + 'px'; f.style.top = e.clientY + 'px';
  f.classList.remove('show'); void f.offsetWidth; f.classList.add('show');
  A.focus(e.clientX / innerWidth, e.clientY / innerHeight);
});

/* ---------- shutter ---------- */
let busy = false;
function shoot() {
  if (busy || cur !== 'camera' || drawerOpen) return;
  busy = true; $('#shutter').classList.add('busy');
  const manualLines = compose(S.gpsFormat, S.manualAddr.trim(), manualCoords());
  A.capture(JSON.stringify({ a: S.a, b: S.b, delay: S.delay, gpsOn: S.gpsOn, gpsFormat: S.gpsFormat, manualLines, quality: S.quality }));
  setTimeout(() => { if (busy) { busy = false; $('#shutter').classList.remove('busy'); } }, 25000);
}
window.shoot = shoot;
$('#shutter').onclick = shoot;
window.onSaved = (ok, msg) => { busy = false; $('#shutter').classList.remove('busy'); toast(msg); };
window.onPerm = () => { checkPerm(); pollGps(); };
function checkPerm() {
  let p = {}; try { p = JSON.parse(A.perms()); } catch (e) {}
  $('#perm-card').classList.toggle('hidden', cur !== 'camera' || p.camera !== false);
}
$('#btn-perm').onclick = () => A.requestPerms();

/* ---------- init ---------- */
function renderAll() { renderStamp(); gpsChip(); }
buildForm('a'); buildForm('b'); buildRatio(); loadNoto();
$('#gps-on').checked = S.gpsOn; $('#gps-fmt').value = S.gpsFormat;
$('#m-addr').value = S.manualAddr; $('#m-lat').value = S.manualLat; $('#m-lon').value = S.manualLon;
markRatio(); pushConfig(); A.setGps(S.gpsOn);
addEventListener('resize', () => setTimeout(renderAll, 60));
setInterval(() => { renderStamp(); if (cur === 'delay') refreshDelay(); }, 500);
setInterval(() => { const e = A.getExtra(); if (e !== extra) { extra = e; renderStamp(); } }, 200);
setInterval(pollGps, 1500);
go('camera'); pollGps();
})();
