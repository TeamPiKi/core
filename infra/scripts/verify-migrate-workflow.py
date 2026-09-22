#!/usr/bin/env python3
"""migrate.yml 정적 검증 — 이번 세션에서 실제로 났던 실패 부류를 전부 기계로 잡는다.

잡는 것:
 1) YAML 파싱
 2) needs.<job>.outputs.<key> 가 그 job 의 outputs 에 실제로 있는가   (job outputs 오타)
 3) needs.<job> 이 그 job 의 needs 목록에 선언돼 있는가              (needs 누락 → 항상 빈 값)
 4) steps.<id>.outputs.<key> 의 <id> 가 같은 job 안에 있는가          (step id 오타)
 5) run 블록 bash -n + shellcheck SC2154(미할당 참조)                 ('names: unbound variable')
 6) terraform variables.tf 의 validation 을 config 출력값에 실제 적용  ('image_bucket_name 형식')
 7) 모든 job 이름이 jobs 에 존재하는가
"""
import yaml, re, subprocess, tempfile, os, sys, json

WF = '.github/workflows/migrate.yml'
raw = open(WF).read()
d = yaml.safe_load(raw)
jobs = d['jobs']
errs, warns = [], []

def job_outputs(name):
    return set((jobs.get(name, {}).get('outputs') or {}).keys())

def job_needs(name):
    n = jobs.get(name, {}).get('needs') or []
    return set([n] if isinstance(n, str) else n)

def job_text(name):
    return yaml.safe_dump(jobs[name], allow_unicode=True, default_flow_style=False)

# ---- 2,3) needs.<job>.outputs.<key> ----
for jn in jobs:
    txt = job_text(jn)
    for m in re.finditer(r'needs\.([A-Za-z_][A-Za-z0-9_-]*)\.outputs\.([A-Za-z0-9_-]+)', txt):
        dep, key = m.group(1), m.group(2)
        if dep not in jobs:
            errs.append(f"[{jn}] needs.{dep} — 그런 job 이 없음")
            continue
        if dep not in job_needs(jn):
            errs.append(f"[{jn}] needs.{dep}.outputs.{key} 를 쓰는데 needs 목록에 {dep} 없음 (항상 빈 값)")
        if key not in job_outputs(dep):
            errs.append(f"[{jn}] needs.{dep}.outputs.{key} — {dep} 의 outputs 에 {key} 없음 "
                        f"(있는 것: {sorted(job_outputs(dep)) or '없음'})")
    # needs.<job>.result 도 needs 선언 필요
    for m in re.finditer(r'needs\.([A-Za-z_][A-Za-z0-9_-]*)\.result', txt):
        dep = m.group(1)
        if dep not in jobs:
            errs.append(f"[{jn}] needs.{dep}.result — 그런 job 이 없음")
        elif dep not in job_needs(jn):
            errs.append(f"[{jn}] needs.{dep}.result 를 쓰는데 needs 목록에 {dep} 없음")
    # needs 로 선언한 job 이 실재하는가
    for dep in job_needs(jn):
        if dep not in jobs:
            errs.append(f"[{jn}] needs 에 없는 job {dep}")

# ---- 4) steps.<id>.outputs ----
for jn, j in jobs.items():
    ids = set(s.get('id') for s in (j.get('steps') or []) if s.get('id'))
    for m in re.finditer(r'steps\.([A-Za-z_][A-Za-z0-9_-]*)\.outputs\.', job_text(jn)):
        if m.group(1) not in ids:
            errs.append(f"[{jn}] steps.{m.group(1)}.outputs — 그런 step id 없음 (있는 것: {sorted(ids)})")

# ---- 5) run 블록 셸 검사 ----
for jn, j in jobs.items():
    for i, s in enumerate(j.get('steps') or []):
        r = s.get('run')
        if not r:
            continue
        src = re.sub(r'\$\{\{[^}]*\}\}', 'PLACEHOLDER', r)
        f = tempfile.NamedTemporaryFile('w', suffix='.sh', delete=False)
        f.write("#!/usr/bin/env bash\n" + src)
        f.close()
        rc = subprocess.run(['bash', '-n', f.name], capture_output=True, text=True)
        if rc.returncode:
            errs.append(f"[{jn}/{s.get('name','step%d'%i)}] bash 문법:\n{rc.stderr.strip()}")
        sc = subprocess.run(['shellcheck', '-s', 'bash', '-i', 'SC2154', '-f', 'json', f.name],
                            capture_output=True, text=True)
        try:
            for issue in json.loads(sc.stdout or '[]'):
                msg = f"[{jn}/{s.get('name','step%d'%i)}] SC{issue['code']} L{issue['line']}: {issue['message']}"
                # trap 'rc=$?; ...' 는 작은따옴표 안에서 할당돼 shellcheck 가 별도 스코프로 본다.
                # 실패에서 롤백·성공에서 no-op 하는 동작은 실측으로 확인했으므로 이것만 예외로 둔다.
                if re.match(r"^rc is referenced", issue['message']) and "trap 'rc=$?" in r:
                    warns.append(msg + "  (trap 안 할당 — 알려진 오탐)")
                else:
                    errs.append(msg)
        except json.JSONDecodeError:
            pass
        os.unlink(f.name)

# ---- 6) terraform validation 을 config 출력값에 적용 ----
tfvars = 'terraform/variables.tf'
if os.path.exists(tfvars):
    tf = open(tfvars).read()
    # variable "x" { ... validation { condition = can(regex("RE", var.x)) ... } }
    for vm in re.finditer(r'variable\s+"([a-z_]+)"\s*\{(.*?)\n\}', tf, re.S):
        vname, body = vm.group(1), vm.group(2)
        rm = re.search(r'regex\(\s*"((?:[^"\\]|\\.)*)"\s*,\s*var\.' + vname, body)
        if not rm:
            continue
        pattern = rm.group(1).replace('\\\\', '\\')
        # 워크플로가 이 변수에 무엇을 넣는지 찾는다
        for am in re.finditer(r'TF_VAR_' + vname + r':\s*\$\{\{\s*needs\.config\.outputs\.([a-z_]+)\s*\}\}', raw):
            out = am.group(1)
            # config 의 map 스텝에서 그 출력의 우변을 뽑는다
            vals = re.findall(r'echo\s+"' + out + r'=([^"]*)"', raw)
            for v in vals:
                sample = (v.replace('$ACCOUNT', '731770491043')
                           .replace('$OLD_ACCOUNT_ID', '996918499382')
                           .replace('${IMAGE_PREFIX}', 'dev-'))
                if not re.search(pattern, sample):
                    errs.append(f"[terraform] TF_VAR_{vname} <- config.{out} = '{sample}' 가 "
                                f"validation /{pattern}/ 을 통과하지 못함")
                # prefix 없는 경우(prod)도 확인
                sample2 = sample.replace('dev-', '', 1)
                if sample2 != sample and not re.search(pattern, sample2):
                    errs.append(f"[terraform] TF_VAR_{vname} <- config.{out} = '{sample2}' 가 "
                                f"validation /{pattern}/ 을 통과하지 못함")

print(f"job {len(jobs)}개 검사")
if warns:
    print("\n--- 경고 ---")
    for w in warns: print(" ", w)
if errs:
    print("\n--- 오류 ---")
    for e in errs: print(" ", e)
    sys.exit(1)
print("\n오류 0건")
