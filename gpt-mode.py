#!/usr/bin/env python3
"""Reversible project-local GPT routing: python3 gpt-mode.py on|status|off [--project DIR].

Python 3.9+, Node, Git, Sidequest 5.6.x and an authenticated Model Gateway.
Uses Sidequest's store API, not direct database edits. No model requests or installs.
"""
import argparse
import base64
import copy
import fcntl
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
from urllib.parse import urlparse

ADAPTER = r'''
const path = require('node:path');
const fs = require('node:fs');
const req = JSON.parse(fs.readFileSync(0, 'utf8'));
const root = path.dirname(path.dirname(req.cli));
const store = require(path.join(root, 'lib/store.js'));
const discovery = require(path.join(root, 'lib/discovery.js'));
const stable = x => JSON.stringify(canon(x));
function canon(x) {
  if (Array.isArray(x)) return x.map(canon);
  if (x && typeof x === 'object') return Object.fromEntries(Object.keys(x).sort().map(k => [k, canon(x[k])]));
  return x;
}
const same = (a,b) => stable(a) === stable(b);
const rowValue = r => r ? {kind:r.kind, data:r.data} : null;
const board = store.findProject(req.project);
if (!board.ok) throw Error('No registered Sidequest board here. Run the kit /bootstrap first.');
const slug = board.slug;
const profile = store.projectRoutingProfile(slug).profile.id;
const categories = store.getCategories({project:slug, withState:true});
const local = store.getProjectCategories(slug).rows;
const tickets = store.listTickets(slug);
const busy = tickets.filter(t => t.claim?.by || t.dispatch && !t.dispatch.terminalAt && !['done','cancelled','canceled'].includes(t.status));
function assertIdle() {
  if (busy.length) throw Error('Release/finish active claims and dispatches first: '+busy.map(t=>t.ref).join(', '));
}
function selectRoute(c, available) {
  let effort = c.route?.effort || 'high';
  let model = 'codex-gpt-6-1-sol';
  const mechanical = ['coding.easy','implementation-explanation','test-execution','source-lookup','ui.tweak'].includes(c.id);
  if (mechanical) {
    model = ['codex-gpt-6-luna','codex-gpt-5-6-luna'].find(m=>available.includes(m)) || model;
    effort = 'medium';
  } else if (c.id.includes('frontier') && available.includes('codex-gpt-6-astra')) {
    model = 'codex-gpt-6-astra'; effort = c.id === 'frontier' ? 'xhigh' : 'high';
  } else if (['escalation','coding.hard','experiment'].includes(c.id)) effort = 'xhigh';
  else if (['evidence-research','visual-evaluation'].includes(c.id)) effort = 'medium';
  else effort = 'high';
  return {model, effort};
}
function gptText(text) {
  return String(text||'').replace(/Claude-only/gi,'GPT-only during temporary mode')
    .replace(/UI never routes to a GPT category/gi,'UI stays in its UI category, now routed to GPT')
    .replace(/which stays on Claude/g,'which now uses its configured GPT route')
    .replace(/a Claude UI ticket/g,'a GPT UI ticket')
    .replace(/Android: runs on Claude, so code written on a GPT route gets a review from the other model family\./g,'Android: independent GPT review of the submitted candidate.')
    .replace(/\bOpus\b/g,'GPT-6.1 Sol').replace(/\bSonnet\b/g,'GPT-6.1 Sol')
    .replace(/\bFable\b/g,'the GPT frontier route');
}
let out;
if (req.op === 'plan') {
  assertIdle();
  const catalog = store.modelsPayload({project:slug, full:true});
  const ready = discovery.providerReadiness('codex');
  const available = catalog.models;
  if (!ready?.ready || !available.includes('codex-gpt-6-1-sol'))
    throw Error('GPT-6.1 Sol is not ready in Model Gateway. Run gateway login, setup/ensure and catalog --refresh first.');
  const sol = catalog.discovered.find(m=>m.slug==='codex-gpt-6-1-sol');
  if (!sol?.id || !/^claude-(?:codex-)?gpt-/.test(sol.id)) throw Error('Cannot resolve the main GPT gateway model id.');
  const changes = categories.map(c => {
    const before = rowValue(local.find(r=>r.id===c.id));
    const data = {};
    for (const k of ['id','name','description','contract','route','fallback','readonly','enabled','artifactRoots','deniedTools'])
      if (Object.hasOwn(c,k)) data[k] = c[k];
    data.route = selectRoute(c, available);
    data.fallback = data.route.model === 'codex-gpt-6-1-sol' ? null : {model:'codex-gpt-6-1-sol',effort:data.route.effort};
    data.name = gptText(data.name);
    data.description = gptText(data.description);
    data.contract = (gptText(data.contract) + '\nTemporary GPT-only mode: use the configured GPT route; do not request a Claude fallback. Auth/SDK/network failures are environment blockers. For image work, inspect the actual image or report that visual verification is blocked.').trim();
    const after = {kind:before?.kind==='ADD' ? 'ADD' : 'DETACH',data};
    return {id:c.id,before,after};
  });
  const overrides = tickets.filter(t=>t.route && !['done','cancelled','canceled'].includes(t.status)).map(t => {
    const c = categories.find(c=>c.id===(t.categoryId || (typeof t.category==='string' ? t.category : t.category?.id))) || {id:'general'};
    return {id:t.id,ref:t.ref,before:t.route,after:selectRoute(c,available)};
  });
  out = {slug,profile,main:sol.id,categories:changes,tickets:overrides};
} else if (req.op === 'apply' || req.op === 'restore') {
  assertIdle();
  if (req.plan.slug!==slug || req.plan.profile!==profile) throw Error('Board/profile changed since the snapshot; restore the original board selection first.');
  const restore = req.op==='restore';
  const conflicts=[];
  for (const change of req.plan.categories) {
    const current = rowValue(store.getProjectCategories(slug).rows.find(r=>r.id===change.id));
    const expected = restore ? change.after : change.before;
    const target = restore ? change.before : change.after;
    if (same(current,target)) continue;
    if (!same(current,expected) && !req.force) { conflicts.push('category '+change.id); continue; }
    if (target) store.setProjectCategory(slug,change.id,target.kind,target.data);
    else store.removeProjectCategory(slug,change.id);
  }
  for (const change of req.plan.tickets) {
    const t = store.getTicket(slug,change.id);
    if (!t) { if (!restore) conflicts.push('missing ticket '+change.ref); continue; }
    const current=t.route || null, expected=restore?change.after:change.before, target=restore?change.before:change.after;
    if (same(current,target)) continue;
    if (!same(current,expected) && !req.force) { conflicts.push('ticket '+change.ref); continue; }
    store.updateTicket(slug,change.id,{route:target});
  }
  out={conflicts};
} else if (req.op==='status') {
  const catalog=store.modelsPayload({project:slug,full:true});
  const invalid = catalog.categories.filter(c => !/^codex-gpt-/.test(c.route.model) || c.fallback && !/^codex-gpt-/.test(c.fallback.model)).map(c=>c.id);
  const ready=discovery.providerReadiness('codex');
  const pendingClaude=tickets.filter(t=>!['done','cancelled','canceled'].includes(t.status)&&t.route&&!/^codex-gpt-/.test(t.route.model)).map(t=>t.ref);
  const unusable=catalog.categories.filter(c=>c.enabled!==false && (!c.resolved.exec || !/^codex-gpt-/.test(c.resolved.model||''))).map(c=>c.id);
  out={profile,ready:ready?.ready===true,busy:busy.map(t=>t.ref),invalid,pendingClaude,unusable,
    routes:catalog.categories.map(c=>({id:c.id,configured:c.route,resolved:c.resolved,enabled:c.enabled}))};
} else throw Error('Unknown adapter operation.');
process.stdout.write(JSON.stringify(out));
'''

NOTE = """<!-- android-kit:gpt-mode:begin -->
Temporary GPT-only mode is active. This overrides the kit's normal Claude-only UI,
review, research and escalation choices, including older kit command text.
Use the current Sidequest category routes; all of them and their fallbacks are GPT.
Keep UI tickets in their UI categories. Escalation still needs prior failed approaches.
Use Sidequest's dispatched executor, without an Agent model argument that overrides it.
Do not create Claude route overrides. Never claim screenshot verification without viewing
the actual image. Auth, network and SDK errors are environment blockers.
Restore with: python3 .claude/kit/gpt-mode.py off
<!-- android-kit:gpt-mode:end -->

"""


def run(args, **kw):
    p = subprocess.run([str(x) for x in args], capture_output=True, text=True, timeout=60, **kw)
    if p.returncode:
        raise RuntimeError(f"Command failed (exit {p.returncode}): " + (p.stderr or p.stdout).strip()[-1600:])
    return p.stdout


def safe(root, relative):
    p = root / relative
    if p.is_absolute() and root not in p.parents:
        raise RuntimeError('Unsafe snapshot path: ' + str(relative))
    if '..' in Path(relative).parts:
        raise RuntimeError('Unsafe snapshot path: ' + str(relative))
    for q in (p, *p.parents):
        if q == root:
            break
        if q.is_symlink():
            raise RuntimeError('Symlink in managed path: ' + str(q))
    return p


def atomic(p, data, mode=0o600):
    p.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(prefix='.'+p.name+'.', dir=p.parent)
    try:
        with os.fdopen(fd, 'wb') as f:
            f.write(data); f.flush(); os.fsync(f.fileno())
        os.chmod(name, mode)
        os.replace(name, p)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def encoded(data):
    return None if data is None else base64.b64encode(data).decode()


def decoded(value):
    return None if value is None else base64.b64decode(value, validate=True)


def discover_cli(explicit=None, project=None):
    if explicit:
        p=Path(explicit).expanduser().resolve()
    else:
        entries=json.loads(run(['claude','plugin','list','--json'],cwd=project))
        if isinstance(entries, dict):
            entries=entries.get('plugins',[])
        installs=[e for e in entries if str(e.get('id','')).split('@')[0]=='sidequest' and e.get('installPath')]
        if not installs:
            raise RuntimeError('Sidequest is not installed. Pass --sidequest /path/to/sidequest/bin/sidequest.js if needed.')
        installs.sort(key=lambda e: (e.get('scope')!='local',e.get('scope')!='project'))
        p=Path(installs[0]['installPath'])/'bin/sidequest.js'
    if not p.is_file():
        raise RuntimeError('Sidequest CLI not found: '+str(p))
    return str(p.resolve())


def adapter(root, cli, op, **kw):
    return json.loads(run(['node','-e',ADAPTER], cwd=root,
                          input=json.dumps(dict(project=str(root),cli=cli,op=op,**kw))))


def settings_plan(root, main):
    p=safe(root,'.claude/settings.local.json')
    current=json.loads(p.read_text()) if p.exists() else {}
    if not isinstance(current,dict) or not isinstance(current.get('env',{}),dict):
        raise RuntimeError('Local settings and env must be JSON objects.')
    gateway=current.get('env',{}).get('ANTHROPIC_BASE_URL') or os.environ.get('ANTHROPIC_BASE_URL')
    parsed=urlparse(gateway or '')
    if parsed.scheme not in ('http','https') or parsed.hostname not in ('localhost','127.0.0.1','::1'):
        raise RuntimeError('Project is not wired to normal Model Gateway transport. Run model-gateway env --write-project first (RC-compat is not supported here).')
    changes=[]
    desired={'model':main,'effortLevel':'high','env.ANTHROPIC_MODEL':main,
             'env.ANTHROPIC_DEFAULT_MODEL':main,'env.CLAUDE_CODE_SUBAGENT_MODEL':''}
    for name in ('OPUS','SONNET','HAIKU','FABLE'):
        desired['env.ANTHROPIC_DEFAULT_'+name+'_MODEL']=main
    # Disable the hook itself, too: an ignored marker need not exist in an executor worktree.
    hooks=current.get('hooks',{})
    if not isinstance(hooks,dict):
        raise RuntimeError('Local settings hooks must be an object.')
    before=hooks.get('PreToolUse',[])
    after=[]
    for group in before:
        row=copy.deepcopy(group)
        row['hooks']=[h for h in row.get('hooks',[]) if not (
            h.get('type')=='command' and 'ui-guard.py' in h.get('command',''))]
        if row['hooks']: after.append(row)
    if before!=after:
        desired['hooks.PreToolUse']=after
    for key,value in desired.items():
        parent=key.split('.')[0] if '.' in key else None
        obj=current.get(parent,{}) if parent else current
        k=key.split('.')[-1]
        changes.append({'key':key,'existed':k in obj,'before':obj.get(k),'after':value})
    return {'existed':p.exists(),'envExisted':'env' in current,'changes':changes,
            'mode':p.stat().st_mode & 0o777 if p.exists() else 0o600}


def change_settings(root, plan, restore=False, force=False):
    p=safe(root,'.claude/settings.local.json')
    obj=json.loads(p.read_text()) if p.exists() else {}
    conflicts=[]
    for c in plan['changes']:
        parent=c['key'].split('.')[0] if '.' in c['key'] else None
        k=c['key'].split('.')[-1]
        target=obj.setdefault(parent,{}) if parent else obj
        if not isinstance(target,dict):
            raise RuntimeError('Local settings '+str(parent)+' must be an object.')
        want=c['before'] if restore else c['after']
        want_exists=c['existed'] if restore else True
        if (k in target)==want_exists and target.get(k)==want:
            continue
        expect=c['after'] if restore else c['before']
        expect_exists=True if restore else c['existed']
        if ((k in target)!=expect_exists or target.get(k)!=expect) and not force:
            conflicts.append('setting '+c['key']); continue
        if want_exists: target[k]=want
        else: target.pop(k,None)
    if restore and not plan['envExisted'] and obj.get('env')=={}:
        obj.pop('env',None)
    if restore and not plan['existed'] and obj=={}:
        if p.exists(): p.unlink()
    else:
        atomic(p,(json.dumps(obj,indent=2,ensure_ascii=False)+'\n').encode(),plan['mode'])
    return conflicts


def file_plan(root, main):
    changes=[]
    def add(rel, new):
        p=safe(root,rel)
        old=p.read_bytes() if p.exists() else None
        if old!=new:
            changes.append({'path':rel,'before':encoded(old),'after':encoded(new),
                            'mode':p.stat().st_mode & 0o777 if p.exists() else 0o600})
    add('.claude/kit/ui-guard-off', b'android-kit temporary GPT-only mode\n')
    p=safe(root,'CLAUDE.md')
    text=p.read_text() if p.exists() else ''
    command='python3 '+shlex.quote(str(Path(__file__).resolve()))+' off --project '+shlex.quote(str(root))
    add('CLAUDE.md',(NOTE.replace('python3 .claude/kit/gpt-mode.py off',command)+text).encode())
    rel='.claude/live-rules/rules/android-orchestration.md'
    p=safe(root,rel)
    if p.exists():
        text=p.read_text().replace('**UI is Claude-only.**','**UI uses the GPT UI categories during temporary GPT-only mode.**')
        text=text.replace('those route to GPT.','keep the UI category and its verification requirements.')
        text=text.replace('(Opus)','(GPT-6.1 Sol)').replace('(Fable)','(GPT frontier route)')
        add(rel,text.encode())
    for folder in ('.claude/agents','.claude/commands','.claude/skills'):
        agents=safe(root,folder)
        if agents.exists():
          for p in sorted(agents.rglob('*.md')):
            rel=str(p.relative_to(root)); safe(root,rel)
            text=p.read_text()
            match=re.match(r'\A(---\r?\n)(.*?)(\r?\n---(?:\r?\n|$))',text,re.S)
            if not match:
                if folder=='.claude/agents':
                    raise RuntimeError('Cannot safely parse agent frontmatter: '+rel)
                continue
            header=match[2]
            model=re.search(r'^model:\s*(.*?)\s*$',header,re.M)
            # Sidequest dispatch markers must survive; they select the ticket-specific GPT model.
            if model and 'claude-codex-auto' in model[1]:
                continue
            if not model and folder!='.claude/agents':
                continue
            if model:
                header=header[:model.start()]+f'model: {main}'+header[model.end():]
            else:
                header+='\nmodel: '+main
            add(rel,(match[1]+header+match[3]+text[match.end():]).encode())
    return changes


def change_files(root, changes, restore=False, force=False):
    conflicts=[]
    for c in changes:
        p=safe(root,c['path']); current=p.read_bytes() if p.exists() else None
        want=decoded(c['before'] if restore else c['after'])
        expected=decoded(c['after'] if restore else c['before'])
        if current==want: continue
        if current!=expected and not force:
            conflicts.append('file '+c['path']); continue
        if want is None:
            if p.exists(): p.unlink()
        else: atomic(p,want,c['mode'])
    return conflicts


def exclude_state(root):
    raw=run(['git','rev-parse','--git-path','info/exclude'],cwd=root).strip()
    p=Path(raw)
    if not p.is_absolute(): p=root/p
    if p.is_symlink(): raise RuntimeError('Git exclude is a symlink.')
    text=p.read_text() if p.exists() else ''
    for line in ('/.claude/kit/gpt-mode-state.json','/.claude/kit/gpt-mode.lock'):
        if line not in text.splitlines(): text+='\n'+line+'\n'
    atomic(p,text.encode(),p.stat().st_mode & 0o777 if p.exists() else 0o600)


def save(p,state):
    atomic(p,(json.dumps(state,indent=2,ensure_ascii=False)+'\n').encode())


def restore(root, cli, state, force=False):
    # Sidequest checks idle BEFORE any file/setting restoration.
    result=adapter(root,cli,'restore',plan=state['routing'],force=force)
    conflicts=result['conflicts']
    conflicts+=change_settings(root,state['settings'],restore=True,force=force)
    conflicts+=change_files(root,state['files'],restore=True,force=force)
    return conflicts


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('action',choices=['on','status','off'])
    ap.add_argument('--project',default='.')
    ap.add_argument('--sidequest',help='Explicit path to sidequest/bin/sidequest.js')
    ap.add_argument('--dry-run',action='store_true',help='Preview on; do not change project files or routing')
    ap.add_argument('--force',action='store_true',help='With off only: restore saved values over conflicting later edits')
    args=ap.parse_args()
    if args.force and args.action!='off': ap.error('--force is for off only')
    if args.dry_run and args.action!='on': ap.error('--dry-run is for on only')
    root=Path(args.project).expanduser().resolve()
    actual=Path(run(['git','rev-parse','--show-toplevel'],cwd=root).strip()).resolve()
    if actual!=root: raise RuntimeError('Run from the Git project root, or pass --project ROOT.')
    statepath=safe(root,'.claude/kit/gpt-mode-state.json')
    state=json.loads(statepath.read_text()) if statepath.exists() else None
    if state and (state.get('schema')!=1 or state.get('project')!=str(root)):
        raise RuntimeError('Snapshot belongs to a different project or format.')
    saved_cli=(state or {}).get('cli')
    # A toolshed update may have removed the old versioned cache directory.
    if saved_cli and not Path(saved_cli).is_file(): saved_cli=None
    cli=discover_cli(args.sidequest or saved_cli,root)
    if args.action=='status':
        live=adapter(root,cli,'status')
        print('GPT mode: '+((state or {}).get('status','off')))
        print('Gateway readiness: '+('ready' if live['ready'] else 'not ready'))
        for c in live['routes']:
            r=c['configured']; print(f"  {c['id']}: {r['model']} / {r['effort']}" + (' [disabled]' if c['enabled'] is False else ''))
        if live['invalid'] or live['pendingClaude']:
            print('Non-GPT selections: '+', '.join(live['invalid']+live['pendingClaude']))
        if live['unusable']:
            print('Unavailable GPT routes: '+', '.join(live['unusable']))
        if state:
            print('Snapshot: '+str(statepath))
        settings=safe(root,'.claude/settings.local.json')
        if settings.exists():
            print('Main session setting: '+str(json.loads(settings.read_text()).get('model','account default')))
        return
    if args.action=='off' and not state:
        print('GPT mode is already off; no snapshot to restore.'); return
    if args.action=='on' and state:
        raise RuntimeError('A saved GPT-mode snapshot already exists. Use status or off first; it will not be overwritten.')
    if args.action=='on':
        plan=adapter(root,cli,'plan')
        state={'schema':1,'project':str(root),'cli':cli,'status':'applying','routing':plan,
               'settings':settings_plan(root,plan['main']),'files':file_plan(root,plan['main'])}
        print(f"Main: {plan['main']} / high; {len(plan['categories'])} categories; {len(plan['tickets'])} existing ticket overrides.")
        if args.dry_run:
            print('Preview only. No project files or routing changed. Sidequest discovery may refresh its own catalog.'); return
    exclude_state(root)
    lockpath=safe(root,'.claude/kit/gpt-mode.lock'); lockpath.parent.mkdir(parents=True,exist_ok=True)
    with open(lockpath,'a') as lock:
        os.chmod(lockpath,0o600)
        fcntl.flock(lock,fcntl.LOCK_EX)
        # Re-read after the lock: never replace another invocation's restore point.
        if args.action=='on' and statepath.exists(): raise RuntimeError('Another invocation created a snapshot; use status/off.')
        if args.action=='off':
            if not statepath.exists(): print('GPT mode is already off.'); return
            state=json.loads(statepath.read_text())
            conflicts=restore(root,cli,state,args.force)
            if conflicts:
                state['status']='restore-conflicts'; save(statepath,state)
                raise RuntimeError('Later edits preserved; snapshot kept. Resolve conflicts or deliberately use off --force:\n  '+'\n  '.join(conflicts))
            statepath.unlink()
            print('Restored original category/ticket routes, model settings, agents and UI guard. Restart Claude Code.'); return
        save(statepath,state)
        try:
            conflicts=adapter(root,cli,'apply',plan=state['routing'])['conflicts']
            conflicts+=change_settings(root,state['settings'])
            conflicts+=change_files(root,state['files'])
            if conflicts: raise RuntimeError('Concurrent edits: '+', '.join(conflicts))
            live=adapter(root,cli,'status')
            if live['invalid'] or live['pendingClaude'] or live['unusable'] or not live['ready']:
                raise RuntimeError('GPT-only verification failed or gateway readiness changed.')
            state['status']='on'; save(statepath,state)
        except BaseException:
            try:
                conflicts=restore(root,cli,state)
                if conflicts: state['status']='restore-conflicts'; save(statepath,state)
                else: statepath.unlink()
            except Exception:
                state['status']='interrupted'; save(statepath,state)
            raise
    print('GPT mode ON. Restart Claude Code; launch with:')
    print('  claude --model '+shlex.quote(plan['main']))
    print('Restore: python3 '+shlex.quote(str(Path(__file__).resolve()))+' off --project '+shlex.quote(str(root)))
    print('User/machine profiles and plugin caches were not edited. Running sessions are unchanged.')


if __name__=='__main__':
    try: main()
    except (Exception,KeyboardInterrupt) as error:
        print('gpt-mode: '+str(error),file=sys.stderr); sys.exit(1)
