"""答题看电视：家里自己加的题目 → 推到电视，不用重新编译程序。

题目写在 ../custom-questions/questions.yaml（格式见该文件开头的说明）。
用法：python3 -I add_questions.py check   只检查格式，列出有问题的题和太长的题
      python3 -I add_questions.py push    检查 → 推送 custom-bank.json → 读回
电视上的题目只显示文字、不朗读，所以不生成语音（以前的 --no-audio 仍然接受，效果一样）。
同一道题的编号由题目文字决定（K+6 位），改了题目文字会变成新题。
新科目（比如语文）要出现在答题里，还要用 tvctl.py set mix="数学:2,英语:2,语文:1" 打开。
"""
import hashlib, json, pathlib, random, sys

import yaml

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))  # -I 模式不会自动加脚本目录
from quiztv import DIR, ROOT, connect, record
from quiztv import adb as _adb

SRC = ROOT / "custom-questions" / "questions.yaml"
OUT = ROOT / "custom-questions" / "build"


def width(text):
    """大约占几个汉字宽：汉字和全角符号算 1，字母数字算半个。"""
    return sum(1 if ord(ch) > 0x2E80 else 0.5 for ch in text)


# 电视答题页放得下的长度（汉字宽）：题目最多 4 行、答案最多 2 行、讲解 3 行；超过会被截掉
LIMITS = (("question", "题目", 80), ("options", "答案", 40), ("explanation", "讲解", 75))


def too_long(item):
    out = []
    for key, name, limit in LIMITS:
        for text in (item[key] if key == "options" else [item[key]]):
            if width(text) > limit:
                out.append(f"{name}太长（约 {width(text):.0f} 个字宽，电视上最多放 {limit}）：{text[:24]}…")
    return out


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
        problems += [f"{where}「{text[:20]}」：{p}" for p in too_long(items[-1])]
    return items, problems


def adb(*a):
    return _adb(*a, check=False, timeout=300)


def main(mode):
    items, problems = load_items()
    for p in problems:
        print("有问题：", p)
    from collections import Counter
    print(f"可用 {len(items)} 题：", dict(Counter(i["subject"] for i in items)))
    if mode != "push":
        sys.exit(1 if problems else 0)
    if problems:
        sys.exit("先把上面有问题的题改好再推送。")
    OUT.mkdir(parents=True, exist_ok=True)
    bank = OUT / "custom-bank.json"
    bank.write_text(json.dumps({"version": "custom", "items": items}, ensure_ascii=False), encoding="utf-8")
    connect()  # 连不上会退出；题目已在本地生成好，连上后再运行一次 push 即可
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
    main(sys.argv[1] if len(sys.argv) > 1 else "check")
