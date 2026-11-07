"""答题看电视：家里自己加的题目 → 生成朗读音频 → 推到电视，不用重新编译程序。

题目写在 ../custom-questions/questions.yaml（格式见该文件开头的说明）。
用法：python3 -I add_questions.py check   只检查格式，列出有问题的题
      python3 -I add_questions.py push    检查 → 生成音频 → 推送 custom-bank.json 和音频 → 读回
      python3 -I add_questions.py push --no-audio  只推文字题库并读回，不生成或上传语音
同一道题的编号由题目文字决定（K+6 位），改了题目文字会变成新题。
新科目（比如语文）要出现在答题里，还要用 tvctl.py set mix="数学:2,英语:2,语文:1" 打开。
"""
import asyncio, hashlib, json, pathlib, random, sys

import yaml

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))  # -I 模式不会自动加脚本目录
from quiztv import DIR, ROOT, connect, record
from quiztv import adb as _adb

SRC = ROOT / "custom-questions" / "questions.yaml"
OUT = ROOT / "custom-questions" / "build"


def load_items():
    data = yaml.safe_load(SRC.read_text(encoding="utf-8")) or {}
    qs = data.get("questions") or []
    items, problems, seen = [], [], set()
    for n, q in enumerate(qs, 1):
        where = f"第 {n} 题"
        try:
            subject = str(q["subject"]).strip(); kp = str(q.get("kp") or subject).strip()
            text = str(q["question"]).strip(); ans = str(q["answer"]).strip()
            wrong = [str(w).strip() for w in q["wrong"]]
        except Exception as e:
            problems.append(f"{where}：缺少 subject / question / answer / wrong（{e}）"); continue
        opts = [ans] + wrong
        if len(wrong) != 2:
            problems.append(f"{where}「{text}」：wrong 要正好两个错误选项"); continue
        if len(set(opts)) != 3 or any(not o for o in opts):
            problems.append(f"{where}「{text}」：三个选项不能重复或为空"); continue
        if text in seen:
            problems.append(f"{where}「{text}」：和前面的题重复"); continue
        seen.add(text)
        qid = "K" + hashlib.sha1(text.encode("utf-8")).hexdigest()[:6]
        rng = random.Random(qid)
        rng.shuffle(opts)
        items.append(dict(id=qid, subject=subject, kp=kp, mistake_ref=str(q.get("source") or "家庭自出题"),
                          unit=str(q.get("unit") or ""), question=text, options=opts, answer_index=opts.index(ans),
                          explanation=str(q.get("explanation") or f"正确答案是 {ans}。"),
                          tts=str(q.get("read_aloud") or text.replace("___", "空格"))))
    return items, problems


async def make_audio(items):
    import edge_tts
    adir = OUT / "audio"; adir.mkdir(parents=True, exist_ok=True)
    sem = asyncio.Semaphore(4)

    async def one(it):
        out = adir / f"{it['id']}.mp3"
        if out.exists() and out.stat().st_size > 1000:
            return
        voice = "en-US-AvaMultilingualNeural" if it["subject"] == "英语" else "zh-CN-XiaoyiNeural"
        opts = "；".join(f"{'ABC'[k]}，{o}" for k, o in enumerate(it["options"]))
        text = f"{it['tts']}。{opts}。".replace("＝", "等于").replace("×", "乘").replace("□", "方框")
        async with sem:
            for _ in range(3):
                try:
                    await edge_tts.Communicate(text, voice, rate="-10%").save(str(out)); return
                except Exception:
                    await asyncio.sleep(2)
        print("没生成出音频（这题在电视上不会读，能正常答）：", it["question"])

    await asyncio.gather(*(one(it) for it in items))


def adb(*a):
    return _adb(*a, check=False, timeout=300)


def main(mode, audio=True):
    items, problems = load_items()
    for p in problems:
        print("有问题：", p)
    from collections import Counter
    print(f"可用 {len(items)} 题：", dict(Counter(i["subject"] for i in items)))
    if mode != "push":
        return
    if problems:
        sys.exit("先把上面有问题的题改好再推送。")
    OUT.mkdir(parents=True, exist_ok=True)
    bank = OUT / "custom-bank.json"
    bank.write_text(json.dumps({"version": "custom", "items": items}, ensure_ascii=False), encoding="utf-8")
    if audio:
        asyncio.run(make_audio(items))
    connect()  # 连不上会退出；题目已在本地生成好，连上后再运行一次 push 即可
    if audio:
        adb("shell", f"mkdir -p {DIR}/audio")
    for f in (sorted((OUT / "audio").glob("K*.mp3")) if audio else []):
        if f.stem in {i["id"] for i in items}:
            r = adb("push", str(f), f"{DIR}/audio/{f.name}")
            if r.returncode != 0:
                print("音频推送失败：", f.name)
    r = adb("push", str(bank), f"{DIR}/custom-bank.json")
    if r.returncode != 0:
        sys.exit("题目推送失败：" + r.stderr)
    got = json.loads(adb("shell", "cat", f"{DIR}/custom-bank.json").stdout)
    if got != json.loads(bank.read_text(encoding="utf-8")):
        sys.exit("推送后读回不一致，题目没有更新成功。")
    print(f"已推送并读回一致：{len(items)} 道家庭题目。下一次开始答题时生效。")
    from collections import Counter as _C
    record("题目：家庭题库 %d 道 %s" % (len(items), dict(_C(i["subject"] for i in items))), SRC)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "check", audio="--no-audio" not in sys.argv[2:])
