// Citizen Construction — extension de la carte 3D : sélection de zone, demandes à Claude, plans, construction en direct
export default function (CM) {
  const { THREE, escapeHtml } = CM;
  const body = CM.addPanel('Constructions', { open: true, order: 20 });
  const STATUTS = {
    en_attente: ['En attente du plan', '#c9a24a'], plan_recu: ['Plan prêt', '#6fbf5a'], en_construction: ['En construction', '#4aa3df'],
    terminee: ['Terminée', '#6fbf5a'], interrompue: ['Interrompue', '#e0913a'], annulation: ['Annulation…', '#4aa3df'],
    annulee: ['Annulée', '#8a8070'], erreur: ['Erreur', '#d0503a'],
  };

  let state = { demandes: [], travail: null, github: null, config: { tailleMax: 256, hauteurMax: 160 } };
  let selecting = false, sel = null, drag = null, previewFor = null;
  let selBox = null, selEdges = null, previewMesh = null, focusBox = null;

  // ------------------------------------------------------------------ boîte de sélection 3D
  const selMat = new THREE.MeshBasicMaterial({ color: 0xc9a24a, transparent: true, opacity: 0.18, depthWrite: false, side: THREE.DoubleSide });
  const edgeMat = new THREE.LineBasicMaterial({ color: 0xe6c97a });
  const focusMat = new THREE.LineBasicMaterial({ color: 0x7ab8e6 });

  function areaHeights(a) {
    let min = Infinity, max = -Infinity;
    const step = Math.max(1, Math.floor(Math.max(a.x1 - a.x0, a.z1 - a.z0) / 48));
    for (let x = a.x0; x <= a.x1; x += step) for (let z = a.z0; z <= a.z1; z += step) {
      const h = CM.heightAt(x, z);
      if (h == null) continue;
      min = Math.min(min, h); max = Math.max(max, h);
    }
    if (!Number.isFinite(min)) { min = 60; max = 70; }
    return { min, max };
  }

  function drawSelection() {
    if (selBox) { CM.overlays.remove(selBox); selBox.geometry.dispose(); selBox = null; }
    if (selEdges) { CM.overlays.remove(selEdges); selEdges.geometry.dispose(); selEdges = null; }
    if (!sel) return;
    const hauteur = parseInt(body.querySelector('#cHauteur')?.value || sel.hauteur || 24, 10);
    const { min, max } = areaHeights(sel);
    const w = sel.x1 - sel.x0 + 1, l = sel.z1 - sel.z0 + 1, y0 = min - 1, y1 = max + hauteur + 1;
    const g = new THREE.BoxGeometry(w, y1 - y0, l);
    selBox = new THREE.Mesh(g, selMat);
    selBox.position.set(sel.x0 + w / 2, (y0 + y1) / 2, sel.z0 + l / 2);
    selBox.renderOrder = 5;
    selEdges = new THREE.LineSegments(new THREE.EdgesGeometry(g), edgeMat);
    selEdges.position.copy(selBox.position);
    CM.overlays.add(selBox, selEdges);
  }

  function showFocus(d) {
    if (focusBox) { CM.overlays.remove(focusBox); focusBox.geometry.dispose(); focusBox = null; }
    if (!d) return;
    const w = d.x1 - d.x0 + 1, l = d.z1 - d.z0 + 1, h = d.yMax - d.yMin + 1;
    const g = new THREE.BoxGeometry(w, h, l);
    focusBox = new THREE.LineSegments(new THREE.EdgesGeometry(g), focusMat);
    focusBox.position.set(d.x0 + w / 2, d.yMin + h / 2, d.z0 + l / 2);
    CM.overlays.add(focusBox);
  }

  function norm(a, b) {
    return { x0: Math.min(a.x, b.x), z0: Math.min(a.z, b.z), x1: Math.max(a.x, b.x), z1: Math.max(a.z, b.z) };
  }

  const pointer = {
    handle(type, ev, p) {
      if (!selecting) return false;
      if (type === 'pointerdown') {
        if (!p) return true;
        drag = { a: p, b: p };
        sel = Object.assign(norm(p, p), { hauteur: sel?.hauteur || 24 });
        drawSelection();
        return true;
      }
      if (type === 'pointermove' && drag) {
        if (p) {
          drag.b = p;
          const n = norm(drag.a, p), max = state.config.tailleMax || 256;
          n.x1 = Math.min(n.x1, n.x0 + max - 1); n.z1 = Math.min(n.z1, n.z0 + max - 1);
          sel = Object.assign(n, { hauteur: sel.hauteur });
          drawSelection();
          fillForm();
        }
        return true;
      }
      if (type === 'pointerup' && drag) {
        drag = null;
        stopSelecting();
        render();
        return true;
      }
      return false;
    },
  };

  function startSelecting() {
    selecting = true;
    CM.setPointerHandler(pointer);
    CM.toast('Clique et fais glisser sur la carte pour tracer la zone. Clic droit = tourner la vue.');
    render();
  }
  function stopSelecting() { selecting = false; CM.setPointerHandler(null); }

  // ------------------------------------------------------------------ aperçu fantôme
  async function togglePreview(id) {
    if (previewMesh) { CM.overlays.remove(previewMesh); previewMesh.geometry.dispose(); previewMesh.material.dispose(); previewMesh = null; }
    if (previewFor === id) { previewFor = null; render(); return; }
    previewFor = id;
    render();
    try {
      const r = await CM.api('/construction/api/apercu?id=' + encodeURIComponent(id));
      if (!r.ok) throw new Error((await r.json()).erreur || 'Erreur ' + r.status);
      const dv = new DataView(await r.arrayBuffer());
      if (dv.getInt32(0) !== 0x43505631) throw new Error('aperçu invalide');
      const ox = dv.getInt32(4), oy = dv.getInt32(8), oz = dv.getInt32(12), n = dv.getInt32(16);
      const geo = new THREE.BoxGeometry(1, 1, 1);
      const mat = new THREE.MeshBasicMaterial({ transparent: true, opacity: 0.72 });
      const mesh = new THREE.InstancedMesh(geo, mat, Math.max(1, n));
      const m = new THREE.Matrix4(), c = new THREE.Color();
      let o = 20;
      for (let i = 0; i < n; i++) {
        const x = ox + dv.getInt16(o), y = oy + dv.getInt16(o + 2), z = oz + dv.getInt16(o + 4);
        m.makeTranslation(x + 0.5, y + 0.5, z + 0.5);
        mesh.setMatrixAt(i, m);
        const shade = 0.85 + ((x + y * 3 + z) % 3) * 0.07;
        c.setRGB(dv.getUint8(o + 6) / 255 * shade, dv.getUint8(o + 7) / 255 * shade, dv.getUint8(o + 8) / 255 * shade, THREE.SRGBColorSpace);
        mesh.setColorAt(i, c);
        o += 9;
      }
      mesh.count = n;
      mesh.renderOrder = 6;
      if (previewFor !== id) { geo.dispose(); mat.dispose(); return; }
      previewMesh = mesh;
      CM.overlays.add(mesh);
      CM.toast(`Aperçu : ${n.toLocaleString('fr-FR')} blocs visibles`);
    } catch (e) {
      previewFor = null;
      CM.toast('Aperçu impossible : ' + e.message, 'erreur');
      render();
    }
  }

  // ------------------------------------------------------------------ actions
  async function post(action, id, extra) {
    try {
      const d = await CM.apiJson(`/construction/api/${action}` + (id ? '?id=' + encodeURIComponent(id) : ''), Object.assign({ method: 'POST' }, extra || {}));
      if (d && d.message) CM.toast(d.message);
      await refresh();
      return d;
    } catch (e) { CM.toast(e.message, 'erreur'); return null; }
  }

  async function sendRequest(ev) {
    ev.preventDefault();
    const f = ev.target;
    const data = {
      titre: f.titre.value.trim(), description: f.description.value.trim(),
      x0: +f.x0.value, z0: +f.z0.value, x1: +f.x1.value, z1: +f.z1.value, hauteur: +f.hauteur.value,
      monde: CM.monde ? CM.monde() : 'minecraft:overworld',
    };
    if (!data.description) { CM.toast('Décris ce que tu veux construire', 'erreur'); return; }
    f.querySelector('button[type=submit]').disabled = true;
    try {
      const d = await CM.apiJson('/construction/api/demande', { method: 'POST', json: data });
      CM.toast(`Demande ${d.id} envoyée`);
      sel = null; drawSelection();
      await refresh();
      if (!state.github?.actif) copyForClaude(d.id);
    } catch (e) { CM.toast(e.message, 'erreur'); }
    finally { const b = f.querySelector('button[type=submit]'); if (b) b.disabled = false; }
  }

  async function copyForClaude(id) {
    try {
      const r = await CM.api('/construction/api/texte?id=' + encodeURIComponent(id));
      const t = await r.text();
      await navigator.clipboard.writeText(t);
      CM.toast('Demande copiée : colle-la dans ta discussion avec Claude');
    } catch (e) {
      const r = await CM.api('/construction/api/texte?id=' + encodeURIComponent(id));
      const w = window.open('', '_blank');
      if (w) { w.document.body.innerHTML = '<pre style="white-space:pre-wrap"></pre>'; w.document.querySelector('pre').textContent = await r.text(); }
    }
  }

  function importPlan(id) {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = '.json,application/json';
    input.onchange = async () => {
      const file = input.files[0];
      if (!file) return;
      const text = await file.text();
      await post('plan', id, { body: text, headers: { 'Content-Type': 'application/json' } });
    };
    input.click();
  }

  // ------------------------------------------------------------------ affichage
  function fillForm() {
    const f = body.querySelector('#fDemande');
    if (!f || !sel) return;
    f.x0.value = sel.x0; f.z0.value = sel.z0; f.x1.value = sel.x1; f.z1.value = sel.z1;
    const t = body.querySelector('#taille');
    if (t) t.textContent = `${sel.x1 - sel.x0 + 1} × ${sel.z1 - sel.z0 + 1} blocs`;
  }

  function render() {
    if (!CM.isAdmin()) {
      body.innerHTML = '<div class="vide">Connecte-toi dans le panneau « Administration » pour sélectionner une zone et lancer des constructions.</div>';
      return;
    }
    const j = state.travail;
    let html = '';
    if (j) {
      const pct = j.total ? Math.floor(j.progression / j.total * 100) : 0;
      html += `<div><div class="ligne"><b>${j.annulation ? 'Annulation' : 'Construction'} ${escapeHtml(j.id)}</b><span class="muted">${j.progression.toLocaleString('fr-FR')} / ${j.total.toLocaleString('fr-FR')}</span></div>
        <div class="vie" style="height:8px;background:#2a2118;border-radius:4px"><div style="width:${pct}%;height:100%;background:#c9a24a;border-radius:4px"></div></div>
        <div class="ligne" style="margin-top:4px"><button class="rouge" data-a="stop">Arrêter</button></div></div>`;
    }
    html += `<div class="ligne"><button id="btnSel" class="${selecting ? 'actif' : ''}">${selecting ? 'Trace la zone sur la carte…' : '⬚ Sélectionner une zone'}</button>
      ${sel ? '<button id="btnEffacer">Effacer</button>' : ''}</div>`;
    if (sel) {
      html += `<form id="fDemande" class="corps" style="padding:0;gap:5px">
        <div class="ligne muted">Monde : ${escapeHtml(CM.mondeNom ? CM.mondeNom(CM.monde()) : '')} · Zone : <span id="taille"></span></div>
        <div class="ligne"><label>X <input name="x0" type="number" style="width:70px"></label><label>Z <input name="z0" type="number" style="width:70px"></label></div>
        <div class="ligne"><label>X <input name="x1" type="number" style="width:70px"></label><label>Z <input name="z1" type="number" style="width:70px"></label></div>
        <label class="ligne">Hauteur max au-dessus du sol <input id="cHauteur" name="hauteur" type="number" min="4" max="${state.config.hauteurMax}" value="${sel.hauteur || 24}" style="width:70px"></label>
        <input name="titre" placeholder="Titre (ex : Taverne du port)" maxlength="120">
        <textarea name="description" rows="5" placeholder="Décris ce que tu veux que Claude construise ici : style, matériaux, étages, intérieur…"></textarea>
        <button type="submit">Envoyer la demande à Claude</button>
        <div class="muted">${state.github?.actif ? 'Envoi automatique sur GitHub : demande-moi « regarde mes demandes de construction ».' : 'Le texte sera copié : colle-le dans ta discussion avec Claude.'}</div>
      </form>`;
    }
    const gh = state.github;
    html += `<div class="muted">GitHub : ${escapeHtml(gh ? gh.etat : '…')} ${gh?.actif ? '<a href="#" id="lnkGh" style="color:#e6c97a">relever</a>' : ''}</div>`;
    if (!state.demandes.length) html += '<div class="vide">Aucune demande pour l\'instant.</div>';
    for (const d of state.demandes) {
      const [label, color] = STATUTS[d.statut] || [d.statut, '#8a8070'];
      const busy = j && j.id === d.id;
      html += `<div class="joueur" style="flex-direction:column;align-items:stretch;border-color:#4a3c22" data-id="${escapeHtml(d.id)}">
        <div class="ligne" style="justify-content:space-between"><span class="nom">${escapeHtml(d.id)} · ${escapeHtml(d.titre || 'Sans titre')}</span>
          <span style="color:${color};font-size:12px">● ${label}</span></div>
        <div class="pos">${CM.mondeNom ? escapeHtml(CM.mondeNom(d.monde || 'minecraft:overworld')) + ' · ' : ''}X ${d.x0}→${d.x1} · Z ${d.z0}→${d.z1} · Y ${d.yMin}→${d.yMax}${d.planBlocs ? ` · ${d.planBlocs.toLocaleString('fr-FR')} blocs` : ''}</div>
        ${d.message ? `<div class="muted">${escapeHtml(d.message)}</div>` : ''}
        <div class="ligne">
          <button data-a="voir">Voir</button>
          <button data-a="copier" title="Copier la demande pour Claude">Copier pour Claude</button>
          <button data-a="importer" ${busy ? 'disabled' : ''} title="Importer un plan .json">Importer un plan</button>
          ${d.planBlocs ? `<button data-a="apercu" class="${previewFor === d.id ? 'actif' : ''}">Aperçu</button>` : ''}
          ${d.planBlocs && !busy && ['plan_recu', 'interrompue', 'annulee', 'erreur', 'terminee'].includes(d.statut) ? `<button data-a="construire">${d.statut === 'interrompue' ? 'Reprendre' : d.statut === 'terminee' ? 'Reconstruire' : 'Construire'}</button>` : ''}
          ${d.annulable && !busy ? '<button data-a="annuler" class="rouge" title="Remettre le terrain comme avant">Annuler</button>' : ''}
          ${!busy ? '<button data-a="supprimer" class="rouge" title="Supprimer la demande">✕</button>' : ''}
        </div></div>`;
    }
    body.innerHTML = html;
    body.querySelector('#btnSel').onclick = () => (selecting ? (stopSelecting(), render()) : startSelecting());
    const be = body.querySelector('#btnEffacer');
    if (be) be.onclick = () => { sel = null; drawSelection(); render(); };
    const f = body.querySelector('#fDemande');
    if (f) {
      fillForm();
      f.onsubmit = sendRequest;
      for (const n of ['x0', 'z0', 'x1', 'z1', 'hauteur']) f[n].oninput = () => {
        sel = Object.assign(norm({ x: +f.x0.value, z: +f.z0.value }, { x: +f.x1.value, z: +f.z1.value }), { hauteur: +f.hauteur.value });
        drawSelection();
        const t = body.querySelector('#taille'); if (t) t.textContent = `${sel.x1 - sel.x0 + 1} × ${sel.z1 - sel.z0 + 1} blocs`;
      };
    }
    const lg = body.querySelector('#lnkGh');
    if (lg) lg.onclick = (e) => { e.preventDefault(); post('github'); };
    body.querySelectorAll('[data-a=stop]').forEach(b => b.onclick = () => post('stop'));
    body.querySelectorAll('[data-id]').forEach(el => {
      const id = el.dataset.id, d = state.demandes.find(x => x.id === id);
      el.querySelectorAll('button[data-a]').forEach(b => b.onclick = async () => {
        switch (b.dataset.a) {
          case 'voir':
            if (CM.setMonde && d.monde && d.monde !== CM.monde()) CM.setMonde(d.monde, { x: (d.x0 + d.x1) / 2, y: d.solMoyen, z: (d.z0 + d.z1) / 2 });
            showFocus(d);
            CM.goto((d.x0 + d.x1) / 2, (d.z0 + d.z1) / 2, d.solMoyen, Math.max(60, (d.x1 - d.x0) * 1.6));
            break;
          case 'copier': copyForClaude(id); break;
          case 'importer': importPlan(id); break;
          case 'apercu':
            if (CM.setMonde && d.monde && d.monde !== CM.monde()) CM.setMonde(d.monde, { x: (d.x0 + d.x1) / 2, y: d.solMoyen, z: (d.z0 + d.z1) / 2 });
            togglePreview(id);
            break;
          case 'construire': if (previewFor === id) togglePreview(id); post('construire', id); break;
          case 'annuler': if (confirm(`Remettre le terrain de ${id} exactement comme avant la construction ?`)) post('annuler', id); break;
          case 'supprimer': if (confirm(`Supprimer la demande ${id} ? (le bâtiment reste en place)`)) post('supprimer', id); break;
        }
      });
    });
  }

  let lastSig = '';
  async function refresh() {
    if (!CM.isAdmin()) { render(); return; }
    try {
      const d = await CM.apiJson('/construction/api/demandes');
      const prevJob = state.travail;
      state = d;
      // pendant une construction : on recharge la carte en direct
      if (prevJob || d.travail) CM.refreshArea();
      const sig = JSON.stringify([d.demandes.map(x => [x.id, x.statut, x.message, x.planBlocs, x.annulable]), d.travail, d.github?.etat]);
      const editing = document.activeElement && body.contains(document.activeElement) && /INPUT|TEXTAREA/.test(document.activeElement.tagName);
      if (sig !== lastSig && !editing) { lastSig = sig; render(); }
      else if (d.travail) {
        const bar = body.querySelector('.vie > div');
        if (bar && d.travail.total) bar.style.width = Math.floor(d.travail.progression / d.travail.total * 100) + '%';
      }
    } catch (e) { /* hors ligne */ }
  }

  CM.on('login', () => { lastSig = ''; refresh(); });
  // changement de monde : on efface ce qui appartient à l'ancien monde
  CM.on('monde', () => {
    if (selecting) stopSelecting();
    sel = null; drag = null; drawSelection(); showFocus(null);
    if (previewMesh) { CM.overlays.remove(previewMesh); previewMesh.geometry.dispose(); previewMesh.material.dispose(); previewMesh = null; previewFor = null; }
    lastSig = ''; render();
  });
  render();
  refresh();
  setInterval(refresh, 1500);
}
