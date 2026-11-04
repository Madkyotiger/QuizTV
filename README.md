# QuizTV｜答题看电视

孩子打开电视，先答几道题，答对了才能看；连续看一段时间要休息，每天有上限。不管从电视桌面、视频 App 还是信号源进去，都会先弹出答题页。

这是给自家小米电视做的家庭项目，基于开源电视桌面 [CleanYourTV](https://github.com/Gathin23/cleanyourtv)（MIT）改的。在一台 Xiaomi MiTV-MFTP0（Android TV 9）上实测过；其他机型没测过，用之前先看“为什么能拦住所有入口”这一节，确认你的电视也是这样。

> English: QuizTV is a parental "answer quiz questions before watching" gate for Android TV, built into a fork of the CleanYourTV launcher. Tested on one Xiaomi TV. Chinese-first docs; questions target Shanghai grade-2 math and English.

## 孩子看到的样子

- **答题页**：粉色背景、小动物（数学小兔、英语小猫、语文熊猫、其他科狐狸），题目会读出来，遥控器选 A/B/C。每次固定几道、各科比例固定，挑不了简单的。
- **答错**：先显示讲解 4 秒（防止乱按），再换一道**同一知识点**的新题；连错 3 道提示“叫爸爸妈妈来一起看看吧”，60 秒后才能再试。最近答错的知识点会更常出。不扣时间、不惩罚。
- **看的时候不打断**：剩 5 分钟、1 分钟各提醒一次；到点出休息页（倒计时、建议看远处喝水）；当天上限到了出“明天再见”。
- **家长按钮**（答题页右上、休息页右下）输四位密码：“大人看电视”到下次待机前不限时不计时；或者给孩子加 20 分钟。
- 只记录答题对错和知识点，用来找卡点，不做排行。

## 规则（`config/gate.json`）

| 键 | 意思 | 样例 |
|---|---|---|
| `windows` | 哪些时段要答题（周循环，多段）；不在时段内电视不受限 | `["FRI 16:00-SUN 19:00"]` |
| `mix` | 每次各科几道 | `{"数学": 3, "英语": 2}` |
| `session_min` / `rest_min` | 连续看多久、休息多久 | 30 / 10 |
| `weekday_cap_min` / `weekend_cap_min` | 周一到周五、周六日每天上限 | 60 / 120 |
| `gap_reset_min` | 关机超过多久，下次开机算新的一段 | 20 |
| `child_name` | 界面上怎么称呼孩子 | `宝贝` |
| `enabled` / `test_until` | 总开关；临时测试到某个时间前一直生效 | `true` / `"2026-11-04 23:00"` |

家长密码单独放在 `config/secret.json`（`{"pin": "1234"}`，不进 git）。推到电视时两者合并。

改规则：

```bash
python3 -I scripts/tvctl.py set session_min=30 rest_min=10
python3 -I scripts/tvctl.py set windows="FRI 16:00-SUN 19:00;SAT 08:00-SAT 12:00"
python3 -I scripts/tvctl.py set mix="数学:2,英语:2,语文:1"
python3 -I scripts/tvctl.py set pin=1234          # 不回显
python3 -I scripts/tvctl.py show                  # 本地和电视上一致吗、守门服务在跑吗
python3 -I scripts/tvctl.py log 50                # 最近的答题／解锁／休息记录
python3 -I scripts/tvctl.py reset                 # 测完清零计时和“最近答错”权重
```

每条命令都会推到电视并**读回**，一致才算成功，电视 1 秒内生效；如果项目是 git 仓库，还会自动提交一笔（作者按运行它的 Hermes Profile 区分）。

## 加题目（不用重新编译）

1. 把 `custom-questions/questions.example.yaml` 复制成 `questions.yaml`，按开头的格式写题：科目、知识点、题目、正确答案、两个错误选项、讲解。任何科目都行。
2. `python3 -I scripts/add_questions.py check` 检查格式。
3. `python3 -I scripts/add_questions.py push`：生成朗读音频（[edge-tts](https://github.com/rany2/edge-tts)，要联网）、推到电视、读回。
4. 新科目要用 `tvctl.py set mix=...` 让它出现在每次的题里。

出题建议：换数字和情境出同一知识点，不照抄原题；错误选项用孩子真会犯的错（不退位、把“比……少”当减法、位值写成 403），答错时能看出卡在哪一步。

内置题库（`question-bank-v2.json`，333 题：数学按题型模板生成并用程序验算，英语按句型）由 `scripts/build_bank_v2.py` 生成，改它要重新编译。

## 安装

需要：电视和电脑在同一局域网、电视打开 ADB 调试；JDK 17、Android SDK（`ANDROID_HOME`）；Python 3 和 `pyyaml`、`edge-tts`。

```bash
cp config/device.example.env config/device.env      # 写上电视 IP，adb 路径
cp config/gate.example.json config/gate.json
cp config/secret.example.json config/secret.json     # 改成你自己的密码
python3 -I scripts/build_bank_v2.py . tv-app/launcher/app/src/main/assets/quiz --audio   # 生成题库和朗读音频（音频不在仓库里）
bash scripts/build_and_install.sh                   # 编译＋单元测试＋装到电视＋读回
python3 -I scripts/tvctl.py push
```

然后把电视的默认桌面设成这个程序（CleanYourTV 的 `launcher/README.md` 有各品牌做法）。小米只允许白名单里的包名当默认桌面，所以包名是 `com.ts.launcher`。

注意：
- **不要卸载重装**：它同时是默认桌面，卸载会丢配置；以后升级都用同一把签名 `install -r`（`build_and_install.sh` 就是这么做的）。
- 小米电视重启后会自动关掉 ADB 调试，要远程改规则得先在电视上再打开一次。
- 没有朗读音频也能用，只是不读题。

## 为什么能拦住所有入口

- 小米电视待机 2 秒后会强行停掉所有第三方程序，然后重新拉起**默认桌面的进程**（但屏幕黑着时不建页面）。守门服务从 `Application.onCreate` 启动，所以待机唤醒、开机、装新版后都会自己起来。
- 服务每秒看一次：该锁的时候，不管前台是小米桌面、小米影视、哪个视频 App 还是信号源，都把答题／休息页盖上去。
- 不需要无障碍、悬浮窗、通知读取、用量统计权限（小米会专门清理带悬浮窗的第三方程序；低内存机型上通知读取授权会被静默丢掉），也不依赖电脑开着。
- 实测拦住了：Home 键、直接启动视频 App、从应用列表开系统桌面、返回键、待机唤醒、覆盖安装。**没测**：断电冷启动、其他品牌和机型。
- 管的是整台电视的时段，分不出某个 App 里放的是动画片还是别的。

## 代码在哪

| 路径 | 是什么 |
|---|---|
| `tv-app/` | CleanYourTV 桌面＋守门代码（`launcher/app/src/main/java/com/gathin/tvhome/gate/`），单元测试在 `launcher/app/src/test/.../gate/` |
| `scripts/tvctl.py` | 改规则、看状态、清零、看记录 |
| `scripts/add_questions.py` | 推送家里自己加的题 |
| `scripts/build_bank_v2.py` | 生成内置题库和音频 |
| `scripts/build_and_install.sh` | 编译、测试、安装、读回 |
| `scripts/gate-check.sh` | 读电视当天的运行证据（只读） |
| `config/*.example.*` | 样例配置 |

守门代码：`GateRules`（计时规则，纯逻辑，有单元测试）、`GateStore`（读规则、存状态、写记录）、`Bank`（题库、选题、换同类题）、`GateService`（每秒检查）、`GateActivity`（全部界面，代码画）、`GateApp`（进程一启动就开服务）、`GateBootReceiver`（开机、装新版后启动）。

## 来源与许可

- 电视桌面：[Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv)，MIT，© 2026 Gathin。本项目基于其 `f592b6d`（2026-10-26），原 README 和 LICENSE 保留在 `tv-app/`。
- 想法来自小红书上一位爸爸的帖子《我把电视魔改了一下》。
- 许可：见 `LICENSE`。
