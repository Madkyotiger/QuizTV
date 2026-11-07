# QuizTV｜答题看电视

孩子打开电视，先答几道题，答对了才能看；连续看一段时间要休息，每天有上限。不管从电视桌面、视频 App 还是信号源进去，都会先弹出答题页。

这是给自家小米电视做的家庭项目，基于开源电视桌面 [CleanYourTV](https://github.com/Gathin23/cleanyourtv)（MIT）改的。在一台 Xiaomi MiTV-MFTP0（Android TV 9）上实测过；其他机型没测过，用之前先看“为什么能拦住所有入口”这一节，确认你的电视也是这样。

> English: QuizTV is a parental "answer quiz questions before watching" gate for Android TV, built into a fork of the CleanYourTV launcher. Tested on one Xiaomi TV. Chinese-first docs; questions target Shanghai grade-2 math and English.

## 孩子看到的样子

- **开场**：每轮先显示“现在是答题看电视时间”，5 秒后进第一题。每轮随机一套深色插画主题（星空毛绒小兔／夜晚森林小狐狸和熊猫），同一轮内的页面都用这一套；整体低亮度，不晃眼。
- **答题**：题目只用文字显示（楷体，接近课本字形），遥控器选 A/B/C。每轮固定几道、各科比例固定（样例：数学 4、英语 4、语文 2），挑不了简单的。
- **答错**：先显示讲解 4 秒，再换一道**同一知识点**的新题；连错 3 道提示“叫爸爸妈妈来一起看看吧”，60 秒后才能再试。最近答错的知识点会更常出。不扣时间、不惩罚。
- **分时段看**（可选，`slots`）：例如周末上午、下午、晚上各一段，每段先答题、最多看 30 分钟；两段之间电视锁住，显示“下一次：下午 13:30 可以看”。同一段里关电视超过 20 分钟再开，要重新答题、只看剩下的分钟。
- **快到点**：剩 5 分钟、1 分钟各提醒一次（屏幕提示；装了提示语音就再说一句，临时压低节目声，不改音量）。
- **到点平复**：正看着到点，先放 60 秒“和电视说再见”（伸懒腰、深呼吸、看远处），再进等待页；等待页让孩子选“画画／搭积木／出去玩”，选完或 1 分钟后画面变成几乎全黑，只剩一行下次时间。
- **不分时段时**：连续看到 `session_min` 进休息倒计时，满 `rest_min` 才能重新答题；每天有上限。
- **家长按钮**（答题页右上、其他页右下）输四位密码：“大人看电视”到下次待机前不限时不计时；或者给孩子加 20 分钟（两段之间也能用）。
- 只记录答题对错、知识点和孩子选了做什么，用来找卡点，不做排行。

## 规则（`config/gate.json`）

| 键 | 意思 | 样例 |
|---|---|---|
| `windows` | 哪些时段要答题（周循环，多段）；不在时段内电视不受限 | `["FRI 16:00-SUN 19:00"]` |
| `slots` / `slot_min` | 可选：生效时段里只有这些段能看，每段先答题、最多看几分钟；不写就不分段 | `["SAT 08:00-SAT 11:30", "SAT 13:30-SAT 17:00"]` / 30 |
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
python3 -I scripts/tvctl.py set windows="FRI 16:00-SUN 20:30"
python3 -I scripts/tvctl.py set slots="SAT 08:00-SAT 11:30;SAT 13:30-SAT 17:00" slot_min=30   # slots= 留空即取消分段
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
3. `python3 -I scripts/add_questions.py push --no-audio`：推文字题库到电视并读回。
4. 新科目要用 `tvctl.py set mix=...` 让它出现在每次的题里。

出题建议：换数字和情境出同一知识点，不照抄原题；错误选项用孩子真会犯的错（不退位、把“比……少”当减法、位值写成 403），答错时能看出卡在哪一步。

内置题库（`question-bank-v2.json`，333 题：数学按题型模板生成并用程序验算，英语按句型）由 `scripts/build_bank_v2.py` 生成，改它要重新编译。

## 安装

最省事：从本仓库 [Releases](../../releases) 下载编译好的 `QuizTV-*.apk`，不用装开发工具。需要一台电脑和电视在同一个 Wi-Fi、电视打开 ADB 调试（小米：设置 → 账号与安全 → ADB 调试），电脑上有 `adb`（Android 官方 platform-tools）和 Python 3。

```bash
adb connect 电视IP:5555              # 第一次连接，电视上会弹窗，选“允许”
adb install -r QuizTV-*.apk
cp config/device.example.env config/device.env      # 写上电视 IP、adb 路径
cp config/gate.example.json config/gate.json
cp config/secret.example.json config/secret.json     # 改成你自己的密码
python3 -I scripts/tvctl.py push
```

自己改代码再编译：另需 JDK 17、Android SDK（`ANDROID_HOME`），`bash scripts/build_and_install.sh` 会编译＋单元测试＋装到电视＋读回。Releases 里的 APK 不带语音（开场和提醒语音是可选的 `assets/quiz/audio/*.ogg`，没有也照常用）。

然后把电视的默认桌面设成这个程序：各品牌做法见 `tv-app/docs/devices/`（上游 CleanYourTV 的实测记录），安全规则见 `tv-app/AGENTS.md`。小米只允许白名单里的包名当默认桌面，所以包名是 `com.ts.launcher`。

注意：
- **不要卸载重装**：它同时是默认桌面，卸载会丢配置；以后升级都用同一把签名 `install -r`（`build_and_install.sh` 就是这么做的）。
- 小米电视重启后会自动关掉 ADB 调试，要远程改规则得先在电视上再打开一次。

## 为什么能拦住所有入口

- 小米电视待机 2 秒后会强行停掉所有第三方程序，然后重新拉起**默认桌面的进程**（但屏幕黑着时不建页面）。守门服务从 `Application.onCreate` 启动，所以待机唤醒、开机、装新版后都会自己起来。
- 服务每秒看一次：该锁的时候，不管前台是小米桌面、小米影视、哪个视频 App 还是信号源，都把答题／休息页盖上去。
- 不需要无障碍、悬浮窗、通知读取、用量统计权限（小米会专门清理带悬浮窗的第三方程序；低内存机型上通知读取授权会被静默丢掉），也不依赖电脑开着。
- 实测拦住了：Home 键、直接启动视频 App、从应用列表开系统桌面、返回键、待机唤醒、覆盖安装。**没测**：断电冷启动、其他品牌和机型。
- 管的是整台电视的时段，分不出某个 App 里放的是动画片还是别的。

## 交给 AI 助手

题目最好按自家孩子的错题来出，每家都不一样，所以这个仓库设计成让你自己的 AI 助手（能在电脑上读文件、跑命令的那种）来改。把仓库下载到电脑，打开 AI 助手，选这个文件夹，然后直接说，例如：

- “读 AGENTS.md，帮我把这个装到我家电视上，电视 IP 是 192.168.1.20。”
- “这是孩子语文错题本的照片，按 custom-questions 的格式出 10 道同知识点的新题，检查后推到电视。”
- “改成周六周日上午、下午各一段，每段看 40 分钟。”

`AGENTS.md` 写了助手该怎么做、哪些事不能做（例如不能卸载重装）。电视上的几步（打开 ADB 调试、第一次连接点“允许”、设默认桌面）要你自己用遥控器按。

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
