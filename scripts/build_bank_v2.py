"""内置题库 v2（上海二年级上）：数学按题型模板批量出题，英语按句型扩充。

用法：python3 -I build_bank_v2.py <项目目录> <电视程序 assets/quiz 目录>
输出：<项目目录>/question-bank-v2.json（全部题目）；assets/quiz/bank.json（电视读）。题目只显示文字，不生成语音。
出题规则：三选一；干扰项取孩子常见错法；mistake_ref 记这类题对应的错题编号（家里自己的错题本，— 表示教材巩固）。
"""
import json, pathlib, random, sys

SEED = 20261104
rng = random.Random(SEED)
NAMES_G = ["小红", "小丽", "朵朵", "安安", "甜甜"]
NAMES_B = ["小明", "小刚", "乐乐", "豆豆", "天天"]
CN = "零一二三四五六七八九"


def cn_num(n):
    if n < 10:
        return CN[n]
    t, u = divmod(n, 10)
    s = ("" if t == 1 else CN[t]) + "十"
    return s + (CN[u] if u else "")


items, seen = [], set()


def add(subject, kp, ref, unit, q, ans, distract, expl, tts=None):
    opts = [str(ans)] + [str(d) for d in distract]
    if len(set(opts)) != 3 or q in seen:
        return False
    seen.add(q)
    rng.shuffle(opts)
    items.append(dict(subject=subject, kp=kp, mistake_ref=ref, unit=unit, question=q, options=opts,
                      answer_index=opts.index(str(ans)), explanation=expl, tts=tts or q))
    return True


def gen(n, fn):
    made = tries = 0
    while made < n and tries < n * 40:
        tries += 1
        made += 1 if fn() else 0


U1, U2, U4 = "1 100以内数的加减法（二）", "2 欢乐购物街", "4 表内乘法"
M = "数学"


def borrow_sub():
    a = rng.randint(31, 98); b = rng.randint(12, a - 10)
    if a % 10 >= b % 10 or b % 10 == 0:
        return False
    ans = a - b
    wrong = (a // 10 - b // 10) * 10 + abs(a % 10 - b % 10)
    return add(M, "退位减法", "M004", U1, f"{a}－{b}＝？", ans, [wrong, ans + 10 if ans + 10 != wrong else ans - 10],
               f"个位{a%10}减{b%10}不够，从十位换1个十：{a%10+10}－{b%10}＝{a%10+10-b%10}，十位{a//10-1}－{b//10}＝{a//10-1-b//10}，得{ans}。")


def exchange_ten():
    a = rng.randint(31, 98); b = rng.randint(12, a - 10)
    if a % 10 >= b % 10:
        return False
    return add(M, "换十", "M004", U1, f"算{a}－{b}时，先把1个十换成10个一，个位变成几个一？", a % 10 + 10, [a % 10, 10],
               f"{a%10}个一加上换来的10个一，是{a%10+10}个一。")


def round_tens_sub():
    a = 10 * rng.randint(3, 9); b = rng.randint(2, 9)
    ans = a - b
    return add(M, "整十数减一位数", "M005", U1, f"有{a}瓶水，卖掉{b}瓶，还剩几瓶？", ans, [ans + 10, a - 10 + b],
               f"把{a}分成{a-10}和10，10－{b}＝{10-b}，{a-10}＋{10-b}＝{ans}。")


def place_value():
    k = rng.randint(2, 9); u = rng.randint(1, 9)
    return add(M, "数位的意义", "M005", U1, f"每盒10支笔。有{k}盒，还有{u}支散的，一共几支？", 10 * k + u, [k + u, int(f"{k}0{u}")],
               f"{k}盒是{k}个十＝{10*k}支，再加{u}支，是{10*k+u}支。")


def less_find_more():
    a = rng.randint(20, 60); d = rng.randint(3, 19); g, b = rng.choice(NAMES_G), rng.choice(NAMES_B)
    ans = a + d
    return add(M, "比少，求多", "M002", U1, f"{g}有{a}张贴纸，比{b}少{d}张。{b}有几张？", ans, [a - d, ans - 1],
               f"{g}比{b}少{d}张，就是{b}比{g}多{d}张：{a}＋{d}＝{ans}。")


def more_find_less():
    a = rng.randint(25, 80); d = rng.randint(3, 19); g, b = rng.choice(NAMES_G), rng.choice(NAMES_B)
    ans = a - d
    return add(M, "比多，求少", "M002", U1, f"{g}跳绳{a}下，比{b}多{d}下。{b}跳了几下？", ans, [a + d, ans + 10],
               f"{g}多，{b}少：{a}－{d}＝{ans}。")


def line_diagram():
    x, y = rng.sample(["苹果", "梨", "桃子", "橘子", "香蕉"], 2); d = rng.randint(2, 9)
    return add(M, "线段图", "M002", U1, f"{x}比{y}少{d}个。画线段图时，谁的线段要画得长？", y, [x, "一样长"],
               f"{x}少、{y}多，多的那根线段画得长。")


def cheaper_find_dearer():
    x, y = rng.sample(["铅笔盒", "书包", "水杯", "台灯", "皮球", "跳绳"], 2)
    a = rng.randint(5, 40); d = rng.randint(2, 20)
    ans = a + d
    return add(M, "比便宜，求贵", "M003", U2, f"{x}{a}元，比{y}便宜{d}元。{y}多少元？", ans, [abs(a - d) if a != d else a + 1, ans - 1],
               f"{x}便宜，{y}贵：{a}＋{d}＝{ans}（元）。")


def dearer_find_cheaper():
    x, y = rng.sample(["盒装牛奶", "瓶装果汁", "面包", "蛋糕", "皮球", "跳绳"], 2)
    a = rng.randint(6, 40); d = rng.randint(2, a - 1)
    ans = a - d
    return add(M, "比贵，求便宜", "M003", U2, f"{x}{a}元，比{y}贵{d}元。{y}多少元？", ans, [a + d, ans + 1],
               f"{x}贵，{y}便宜：{a}－{d}＝{ans}（元）。")


def same_total_compare():
    g, b = rng.choice(NAMES_G), rng.choice(NAMES_B); t = rng.randint(30, 90)
    x, y = sorted(rng.sample(range(5, t - 3), 2))
    if rng.random() < .5:
        return add(M, "总量相同，比较剩余", "M001", U1,
                   f"{g}和{b}都要读{t}页书。{g}读了{x}页，{b}读了{y}页。谁剩下的多？", g, [b, "一样多"],
                   f"总数一样，读得少的剩得多。{g}读得少。")
    e1, e2 = sorted(rng.sample(range(2, 10), 2))
    return add(M, "总量相同，比较剩余", "M001", U1,
               f"两盒饼干一样多。{g}吃了{e2}块，{b}吃了{e1}块。谁的盒子里剩得少？", g, [b, "一样多"],
               f"总数一样，吃得多的剩得少。{g}吃得多。")


def same_total_left():
    t = rng.randint(30, 90); x = rng.randint(8, t - 5); b = rng.choice(NAMES_B)
    return add(M, "总量相同，求剩余", "M001", U1, f"{b}要读{t}页书，已经读了{x}页。还剩几页？", t - x, [x, t - x + 10],
               f"{t}－{x}＝{t-x}（页）。")


def backward():
    k = rng.randint(1, 3)
    if k == 1:
        r = rng.randint(25, 80); p = rng.randint(1, 5); q = 10
        ans = r + q + p
        return add(M, "倒推", "M006", U1, f"□ 减{p} 得到 □，再减{q} 得到{r}。第一个□是几？", ans, [r - q - p, ans - 2 * p],
                   f"倒着想：{r}＋{q}＝{r+q}，{r+q}＋{p}＝{ans}。")
    if k == 2:
        r = rng.randint(30, 85); q = 10; p = rng.randint(1, 5)
        ans = r + p - q
        return add(M, "倒推", "M006", U1, f"□ 加{q} 得到 □，再减{p} 得到{r}。第一个□是几？", ans, [r + q - p, ans - 2],
                   f"倒着想：{r}＋{p}＝{r+p}，{r+p}－{q}＝{ans}。")
    r = rng.randint(15, 60); q = rng.randint(5, 30)
    return add(M, "倒推", "M006", U1, f"一个数减去{q}，得{r}。这个数是几？", r + q, [abs(r - q) or 1, r + q - 10],
               f"减掉的加回来：{r}＋{q}＝{r+q}。")


def mult_equation():
    a, b = rng.randint(2, 9), rng.randint(2, 5)
    p = a * b
    cs = [c for c in (2, 3, 4, 5, 8, 10) if p % c == 0 and 1 <= p // c <= 10 and c not in (a, b)]
    if not cs:
        return False
    c = rng.choice(cs); ans = p // c
    if rng.random() < .5:
        q = f"{a}×{b}＝□×{c}，□里填几？"
    else:
        q = f"{a}×{b}＝{c}×□，□里填几？"
    d2 = b if b != ans else a
    return add(M, "乘法等式", "M007-M009", U4, q, ans, [p, d2 if d2 != p else ans + 1],
               f"先算{a}×{b}＝{p}，再想几乘{c}得{p}：{ans}×{c}＝{p}。")


def mult_reading():
    a, b = rng.randint(2, 9), rng.randint(2, 9); p = a * b
    return add(M, "乘法算式的读法", "M010", U4, f"{a}×{b}＝{p} 读作什么？", f"{cn_num(a)}乘{cn_num(b)}等于{cn_num(p)}",
               [f"{a}个{b}相加", f"{cn_num(a)}加{cn_num(b)}等于{cn_num(p)}"],
               "“×”读“乘”，“＝”读“等于”。“几个几相加”是意思，不是读法。")


def mult_meaning():
    b = rng.randint(2, 9); n = rng.randint(3, 5)
    s = "＋".join([str(b)] * n)
    if rng.random() < .5:
        return add(M, "乘法的意义", "M010", U4, f"{s} 写成乘法算式是哪个？", f"{n}×{b}", [f"{b}＋{n}", f"{b}×{b}" if b != n else f"{b}＋{b}"],
                   f"{n}个{b}相加，写成{n}×{b}。")
    return add(M, "乘法的意义", "M010", U4, f"{s} 是几个{b}相加？", n, [b, n * b], f"数一数，有{n}个{b}。")


def rmb():
    k = rng.randint(1, 9)
    return add(M, "人民币单位", "—（教材巩固）", U2, f"{k}元＝几角？", 10 * k, [k, 100 * k], f"1元＝10角，{k}元＝{10*k}角。")


def money_mult():
    p, n = rng.randint(2, 9), rng.randint(2, 5)
    return add(M, "用乘法算钱", "—（教材巩固）", U2 + "／" + U4, f"一支笔{p}元，买{n}支，要付多少元？", p * n, [p + n, int(f"{p}{n}")],
               f"{n}个{p}元：{p}×{n}＝{p*n}（元）。")


for fn, n in [(borrow_sub, 25), (exchange_ten, 10), (round_tens_sub, 15), (place_value, 12), (less_find_more, 15),
              (more_find_less, 15), (line_diagram, 8), (cheaper_find_dearer, 12), (dearer_find_cheaper, 12),
              (same_total_compare, 10), (same_total_left, 10), (backward, 20), (mult_equation, 25),
              (mult_reading, 15), (mult_meaning, 15), (rmb, 8), (money_mult, 12)]:
    gen(n, fn)

E = "英语"
eng_fixed = [
 ("物主形容词找错", "E001", "—（补充练习）", "哪个词错了？Lily is my sister. His eyes are big.", "His", ["Lily", "big"], "Lily 是女孩，His 要改成 Her。"),
 ("物主形容词 my", "E003", "—（补充练习）", "男孩抱着自己的小猫说：This is ___ cat.", "my", ["your", "her"], "说自己的东西用 my。"),
 ("物主形容词 your", "E003", "—（补充练习）", "Hello, Ben! Is this ___ pencil?（问 Ben 的）", "your", ["my", "her"], "问对方的东西，用 your。"),
 ("物主形容词 our", "E002", "—（补充练习）", "We are in Class 2. ___ teacher is Miss Li.", "Our", ["Their", "My"], "“我们的”用 Our。"),
 ("cupboard", "E004", "—（补充练习）", "“橱柜”的英文是？", "cupboard", ["fridge", "box"], "cupboard 橱柜；fridge 冰箱。"),
 ("fridge", "E004", "—（补充练习）", "“冰箱”的英文是？", "fridge", ["cupboard", "bed"], "fridge 冰箱；cupboard 橱柜。"),
 ("don't 的全写", "E005", "—（补充练习）", "don't ＝ ?", "do not", ["does not", "is not"], "don't 是 do not 的缩写。"),
 ("I like…", "E005", "—（补充练习）", "No, I don't. I ___ orange juice.", "like", ["likes", "am"], "I 后面用 like。"),
 ("Do 开头的问句", "E005", "—（补充练习）", "___ you like apples?", "Do", ["Are", "Is"], "问“你喜欢……吗”用 Do you like…?"),
 ("名词作定语用单数", "E006", "—（补充练习）", "I want a ham and ___ sandwich.", "egg", ["eggs", "an egg"], "egg 说明是哪种三明治，用单数 egg。"),
 ("泛指喜欢用复数", "E006", "—（补充练习）", "Do you like ___?（问喜不喜欢吃鸡蛋）", "eggs", ["egg", "an eggs"], "泛指喜欢某类东西，用复数 eggs。"),
 ("名词作定语用单数", "E006", "—（补充练习）", "I like ___ juice.（苹果汁）", "apple", ["apples", "an apple"], "apple 说明是哪种果汁，用单数。"),
 ("brush my teeth", "—（教材巩固）", "Unit 1 日常起居", "刷牙：I ___ my teeth.", "brush", ["wash", "get"], "brush my teeth 刷牙。"),
 ("wash my face", "—（教材巩固）", "Unit 1 日常起居", "洗脸：I ___ my face.", "wash", ["brush", "play"], "wash my face 洗脸。"),
 ("by bus", "—（教材巩固）", "Unit 3 On the way", "坐公共汽车上学：I go to school ___.", "by bus", ["on bus", "by foot"], "坐车用 by：by bus。"),
 ("on foot", "—（教材巩固）", "Unit 3 On the way", "走路上学：I go to school ___.", "on foot", ["by foot", "on bus"], "走路是 on foot，不是 by foot。"),
 ("play football", "—（教材巩固）", "Unit 4 Playing sports", "踢足球是？", "play football", ["play basketball", "jump"], "football 足球；basketball 篮球。"),
 ("moon", "—（教材巩固）", "Unit 5 In the sky", "晚上天上亮亮的是？I can see the ___ at night.", "moon", ["sun", "cloud"], "晚上看到 moon 月亮。"),
 ("winter", "—（教材巩固）", "Unit 7 Seasons", "最冷的季节是？", "winter", ["summer", "spring"], "winter 冬天。"),
 ("grape", "—（教材巩固）", "Unit 8 Yummy fruit", "“葡萄”的英文是？", "grape", ["pear", "orange"], "grape 葡萄；pear 梨。"),
 ("see", "—（教材巩固）", "Unit 9 The five senses", "I ___ with my eyes.", "see", ["hear", "smell"], "眼睛用来 see 看。"),
 ("hear", "—（教材巩固）", "Unit 9 The five senses", "I ___ with my ears.", "hear", ["see", "taste"], "耳朵用来 hear 听。"),
 ("under", "E004", "Unit 2 My room", "球在床底下：The ball is ___ the bed.", "under", ["on", "in"], "under 在……下面。"),
 ("on", "E004", "Unit 2 My room", "书在书桌上：The book is ___ the desk.", "on", ["under", "in"], "on 在……上面。"),
 ("in", "E004", "Unit 2 My room", "玩具在盒子里：The toy is ___ the box.", "in", ["on", "under"], "in 在……里面。"),
]
for kp, ref, unit, q, a, d, ex in eng_fixed:
    add(E, kp, ref, unit, q, a, d, ex, tts=q.replace("___", "blank"))

girls = ["Angie", "Lily", "Amy", "Kitty", "Alice"]; boys = ["Tom", "Ben", "Danny", "Peter", "Eddie"]
things = [("bag", "is red"), ("hair", "is short"), ("dog", "is big"), ("kite", "is new"), ("cat", "is small"), ("ball", "is blue")]
for _ in range(40):
    t, rest = rng.choice(things); k = rng.random()
    if k < .4:
        nm = rng.choice(girls); q = f"{nm} is a girl. ___ {t} {rest}."; a, d, ex = "Her", ["His", "Their"], f"{nm} 是女孩，“她的”用 Her。"; ref = "E001"
    elif k < .75:
        nm = rng.choice(boys); q = f"{nm} is my friend. ___ {t} {rest}."; a, d, ex = "His", ["Her", "Our"], f"{nm} 是男孩，“他的”用 His。"; ref = "E001"
    else:
        rel = rng.choice(["sisters", "brothers", "friends"]); q = f"They are my {rel}. ___ {t} {rest}."
        a, d, ex = "Their", ["Our", rng.choice(["His", "Her"])], "说的是他们的东西，用 Their。"; ref = "E002"
    add(E, "物主形容词", ref, "—（补充练习）", q, a, d, ex, tts=q.replace("___", "blank"))

unc = ["milk", "juice", "water", "bread", "soya milk", "rice"]; cnt = ["apples", "eggs", "books", "cups", "pears", "balls"]
places = [("in the fridge", "在冰箱里"), ("on the desk", "在书桌上"), ("in the cupboard", "在橱柜里"), ("on the table", "在桌子上")]
for _ in range(24):
    pl, _cn = rng.choice(places)
    if rng.random() < .5:
        w = rng.choice(unc); q = f"There ___ some {w} {pl}."; a, d, ex = "is", ["are", "am"], f"{w} 不能一个一个数，用 There is。"
    else:
        n = rng.choice(["two", "three", "four", "five"]); w = rng.choice(cnt); q = f"There ___ {n} {w} {pl}."
        a, d, ex = "are", ["is", "am"], f"{n} {w} 是复数，用 There are。"
    add(E, "There is / There are", "E004", "—（补充练习）", q, a, d, ex, tts=q.replace("___", "blank"))

foods = ["bananas", "grapes", "pears", "oranges", "eggs", "noodles", "carrots"]
for f in foods:
    add(E, "Do you like…? 否定回答", "E005", "—（补充练习）", f"Do you like {f}?（你不喜欢）", "No, I don't.", ["Yes, I do.", "No, I am."],
        "不喜欢就答 No, I don't.", tts=f"Do you like {f}? 你不喜欢，怎么回答？")
    add(E, "Do you like…? 肯定回答", "E005", "—（补充练习）", f"Do you like {f}?（你喜欢）", "Yes, I do.", ["Yes, I like.", "No, I don't."],
        "喜欢就答 Yes, I do.", tts=f"Do you like {f}? 你喜欢，怎么回答？")

for i, it in enumerate(items):
    it["id"] = ("M" if it["subject"] == M else "E") + f"{i:03d}"

proj, assets = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
assets.mkdir(parents=True, exist_ok=True)
bank = dict(version="v2-2026-11-04", seed=SEED, items=items)
(proj / "question-bank-v2.json").write_text(json.dumps(bank, ensure_ascii=False, indent=1), encoding="utf-8")
(assets / "bank.json").write_text(json.dumps(bank, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
from collections import Counter
print("total", len(items), Counter(i["subject"] for i in items))
print(Counter((i["subject"], i["kp"]) for i in items).most_common())
