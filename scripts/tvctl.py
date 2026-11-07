"""答题看电视：改规则、推送、读回、查看状态。

用法（python3 -I scripts/tvctl.py <命令> ...）：
  show                         显示当前规则（不显示密码）和电视上的实际配置是否一致
  set KEY=VALUE [KEY=VALUE..]  改规则并推送到电视，读回一致才算成功。可改：
                               session_min（连续看几分钟） rest_min（休息几分钟）
                               weekday_cap_min（周一到周五每天上限） weekend_cap_min（周六日每天上限）
                               gap_reset_min（关机多久算新的一段） windows（生效时段，如 "FRI 16:00-SUN 19:00;SAT 08:00-SAT 12:00"）
                               mix（每次各科几道，如 "数学:2,英语:2,语文:1"） enabled（true/false） test_until（"YYYY-MM-DD HH:MM"，到点前一直生效）
                               child_name（界面上怎么称呼孩子） pin（四位家长密码，只写不回显）
  reset                        清零计时和“最近答错”记录（测试后用），答题记录另存
  push                         把当前规则原样推送并读回
  log [N]                      看电视上最近 N 条答题／解锁／休息记录（默认 30）
规则：config/gate.json（当前源，不含密码）；家长密码：config/secret.json（不进 git）。
电视上的副本是两者合并：/sdcard/Android/data/com.ts.launcher/files/gate.json
"""
import json, pathlib, sys, tempfile, time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))  # -I 模式不会自动加脚本目录
from quiztv import DIR, ROOT, adb, connect, record

CFG = ROOT / "config" / "gate.json"
SECRET = ROOT / "config" / "secret.json"
INT_KEYS = {"session_min", "rest_min", "weekday_cap_min", "weekend_cap_min", "gap_reset_min", "slot_min"}


def load():
    if not CFG.exists():
        sys.exit("还没有 config/gate.json：先 cp config/gate.example.json config/gate.json（密码另放 config/secret.json）")
    return json.loads(CFG.read_text(encoding="utf-8"))


def save(c):
    c = {k: v for k, v in c.items() if k != "pin"}
    CFG.write_text(json.dumps(c, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")


def merged():
    c = load()
    if SECRET.exists():
        c.update(json.loads(SECRET.read_text(encoding="utf-8")))
    return c


def public(c):
    return {k: ("****" if k == "pin" else v) for k, v in c.items()}


def push():
    connect()
    want = merged()
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as t:
        json.dump(want, t, ensure_ascii=False, indent=1)
    adb("push", t.name, f"{DIR}/gate.json")
    got = json.loads(adb("shell", "cat", f"{DIR}/gate.json").stdout)
    if got != want:
        sys.exit("推送后读回不一致，电视上的规则没有更新成功。")
    print("已推送并读回一致；电视 1 秒内按新规则执行。")


def validate(c):
    for k in INT_KEYS:
        if k in c and not (1 <= int(c[k]) <= 600):
            sys.exit(f"{k} 要在 1–600 分钟之间")
    if "pin" in c and not (len(str(c["pin"])) == 4 and str(c["pin"]).isdigit()):
        sys.exit("家长密码要是四位数字")
    for w in c.get("windows", []) + c.get("slots", []):
        a, b = [x.strip().split() for x in w.split("-")]
        for d, hm in (a, b):
            assert d.upper() in ("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"), w
            h, m = hm.split(":"); assert 0 <= int(h) < 24 and 0 <= int(m) < 60, w
    if "mix" in c and (not c["mix"] or sum(c["mix"].values()) > 10):
        sys.exit("mix 至少一科、合计不超过 10 道")


def cmd_set(pairs):
    c, pin = load(), None
    for p in pairs:
        k, _, v = p.partition("=")
        if k in INT_KEYS:
            c[k] = int(v)
        elif k in ("windows", "slots"):
            c[k] = [w.strip() for w in v.split(";") if w.strip()]
            if not c[k]:
                c.pop(k)
        elif k == "mix":
            c[k] = {s.split(":")[0].strip(): int(s.split(":")[1]) for s in v.split(",") if s.strip()}
        elif k == "enabled":
            c[k] = v.lower() in ("1", "true", "yes", "on")
        elif k in ("test_until", "enable_after"):
            if v:
                time.strptime(v, "%Y-%m-%d %H:%M"); c[k] = v
            else:
                c.pop(k, None)
        elif k == "child_name":
            c[k] = v.strip()
        elif k == "pin":
            pin = v
        else:
            sys.exit(f"不认识的规则：{k}")
    validate({**c, **({"pin": pin} if pin else {})})
    save(c)
    if pin:
        SECRET.write_text(json.dumps({"pin": pin}) + "\n", encoding="utf-8")
    push()
    changed = ", ".join(p if not p.startswith("pin=") else "pin=****" for p in pairs)
    record("规则：" + changed, CFG)
    print(json.dumps(public(merged()), ensure_ascii=False))


def cmd_show():
    c = merged()
    print("本地规则：", json.dumps(public(c), ensure_ascii=False))
    connect()
    try:
        got = json.loads(adb("shell", "cat", f"{DIR}/gate.json").stdout)
        print("电视上一致" if got == c else "电视上的规则和本地不一致：" + json.dumps(public(got), ensure_ascii=False))
    except Exception:
        print("电视上没有读到 gate.json")
    out = adb("shell", "dumpsys", "activity", "services", "com.ts.launcher").stdout
    print("守门服务在运行" if "gate.GateService" in out else "守门服务没在运行")


def cmd_reset():
    c = load()
    c["reset_token"] = "reset-" + time.strftime("%Y%m%d-%H%M%S")
    save(c)
    connect()
    stamp = time.strftime("%Y%m%d-%H%M%S")
    adb("shell", f"[ -f {DIR}/gate-log.jsonl ] && mv {DIR}/gate-log.jsonl {DIR}/gate-log-before-reset-{stamp}.jsonl; true", check=False)
    push()
    record("清零计时和最近答错记录", CFG)
    print("计时和“最近答错”记录已清零，旧答题记录另存为 gate-log-before-reset-%s.jsonl" % stamp)


def cmd_log(n):
    connect()
    print(adb("shell", f"tail -n {int(n)} {DIR}/gate-log.jsonl 2>/dev/null", check=False).stdout or "还没有记录")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__); sys.exit(0)
    cmd, rest = sys.argv[1], sys.argv[2:]
    {"set": lambda: cmd_set(rest), "show": cmd_show, "reset": cmd_reset, "push": push,
     "log": lambda: cmd_log(rest[0] if rest else 30)}.get(cmd, lambda: print(__doc__))()
