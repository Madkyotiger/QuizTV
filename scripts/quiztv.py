"""tvctl.py / add_questions.py 共用：读设备设置、调 ADB、改完自动记一笔 git。

设备设置在 config/device.env（KEY=VALUE，每台机器自己一份，不进公开仓库；样例 config/device.example.env）：
  TV=192.168.1.20:5555        电视的 ADB 地址
  ADB=adb                     adb 程序（默认在 PATH 里找）
  ADB_HOME=                   adb 钥匙所在的 HOME（电视只认第一次授权的那把钥匙；留空用当前 HOME）
  PYTHON=python3              跑脚本用的 Python
  AUTHORS=default:Dad,mom-agent:Mom   git 记录里怎么称呼各个 Hermes Profile（可选）
也可以用同名环境变量覆盖（前面加 QUIZTV_，如 QUIZTV_TV）。
"""
import os, pathlib, shutil, subprocess, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
PKG = "com.ts.launcher"
DIR = f"/sdcard/Android/data/{PKG}/files"


def _device():
    vals = {"TV": "", "ADB": "", "ADB_HOME": "", "PYTHON": "python3", "AUTHORS": ""}
    f = ROOT / "config" / "device.env"
    if f.exists():
        for line in f.read_text(encoding="utf-8").splitlines():
            k, sep, v = line.strip().partition("=")
            if sep and not k.startswith("#"):
                vals[k.strip()] = v.strip().strip('"')
    for k in vals:
        vals[k] = os.environ.get("QUIZTV_" + k, vals[k])
    vals["ADB"] = vals["ADB"] or shutil.which("adb") or "adb"
    return vals


DEV = _device()
TV = DEV["TV"]
ADB = DEV["ADB"]
ADB_ENV = {**os.environ, **({"HOME": DEV["ADB_HOME"]} if DEV["ADB_HOME"] else {})}


def adb(*args, check=True, timeout=120):
    if not TV:
        sys.exit("还没设电视地址：在 config/device.env 写 TV=电视IP:5555（样例见 config/device.example.env）")
    r = subprocess.run([ADB, "-s", TV, *args], capture_output=True, text=True, timeout=timeout, env=ADB_ENV)
    if check and r.returncode != 0:
        sys.exit(f"ADB 失败：{' '.join(args)}：{r.stderr.strip() or r.stdout.strip()}")
    return r


def connect():
    if not TV:
        adb("shell", "true")
    subprocess.run([ADB, "connect", TV], capture_output=True, text=True, timeout=30, env=ADB_ENV)
    r = subprocess.run([ADB, "-s", TV, "shell", "true"], capture_output=True, text=True, timeout=30, env=ADB_ENV)
    if r.returncode != 0:
        sys.exit("连不上电视：电视要开着，并且打开了 ADB 调试（小米：设置 → 账号与安全 → ADB 调试；重启后会被自动关掉）。")


def _author():
    """谁在改：按运行它的 Hermes Profile 区分，git log 里就能看出是哪个 Agent 改的。"""
    if os.environ.get("QUIZTV_AUTHOR"):
        return os.environ["QUIZTV_AUTHOR"]
    home = os.environ.get("HERMES_HOME", "")
    if not home:
        return ""
    profile = pathlib.Path(home).name
    profile = "default" if profile == ".hermes" else profile
    names = dict(x.split(":", 1) for x in DEV["AUTHORS"].split(",") if ":" in x)
    return f"{names.get(profile, profile)} <{profile}@hermes.local>"


def record(message, *paths):
    """推送成功后把改动记进本地 git；出错也不影响已经生效的改动。"""
    if not (ROOT / ".git").exists():
        return
    try:
        rel = [str(pathlib.Path(p).resolve().relative_to(ROOT)) for p in paths] or ["."]
        subprocess.run(["git", "-C", str(ROOT), "add", "--", *rel], capture_output=True, timeout=60)
        if subprocess.run(["git", "-C", str(ROOT), "diff", "--cached", "--quiet"], timeout=60).returncode == 0:
            return
        cmd = ["git", "-C", str(ROOT), "-c", "user.name=quiztv", "-c", "user.email=quiztv@family.local", "commit", "-q", "-m", message]
        if _author():
            cmd += ["--author", _author()]
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
        if r.returncode == 0:
            print("已记入 git：" + message)
    except Exception as e:  # noqa: BLE001
        print("（改动已生效，但没记进 git：%s）" % e)
