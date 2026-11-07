# AGENTS.md — 给 AI 助手的说明

你在帮一位家长用 QuizTV（孩子先答题才能看电视）。家长通常不懂命令行：每步用一句大白话说你要做什么，需要家长在电视上按遥控器时，说清楚按哪里、看到什么算对。

## 常见任务

| 家长说 | 你做 |
|---|---|
| 装到电视上 | 按 README“安装”：先确认电视和电脑在同一 Wi-Fi、电视已开 ADB 调试；`adb connect IP:5555`（让家长在电视上点“允许”）；装 Releases 里最新的 APK；复制三份 `config/*.example.*` 去掉 `.example`，问家长要电视 IP、四位家长密码、孩子称呼；`python3 -I scripts/tvctl.py push`；再把默认桌面设成这个程序：先读 `tv-app/AGENTS.md`（上游 CleanYourTV 的安全规则，例如确认新桌面生效前不停用原桌面、不碰工厂菜单），再找 `tv-app/docs/devices/` 里同品牌同型号的记录照做（已有 80 多台电视的实测记录，小米、海信、长虹、飞利浦等）。小米只允许白名单包名当桌面，所以包名固定是 `com.ts.launcher`。最后用 `tvctl.py show` 确认“一致”和“守门服务在运行” |
| 按错题出题 | 读家长给的错题（照片、文字都行），写进 `custom-questions/questions.yaml`（格式见 `questions.example.yaml`）：同一知识点换数字和情境出新题，不照抄原题；错误选项用孩子真会犯的错；讲解一两句。然后 `add_questions.py check`，通过后 `add_questions.py push --no-audio`。新科目要 `tvctl.py set mix=...` 才会出现 |
| 改时间／每次几道题 | `tvctl.py set ...`，键见 README“规则”表；改完看输出里的“读回一致” |
| 孩子说电视坏了／看不了 | 先 `tvctl.py show` 和 `tvctl.py log 30` 看是不是规则本来就在锁（时段外、到上限），再判断 |

## 不要做

- 不要 `adb uninstall com.ts.launcher`，也不要换签名重装：它是默认桌面，卸载会丢设置。升级只用 `adb install -r`。
- 不要把家长密码写进聊天记录、`gate.json` 或任何会上传的地方；只放 `config/secret.json`。
- 不要说“装好了”，除非 `tvctl.py show` 显示一致且守门服务在运行。
- 只在小米 MiTV-MFTP0 上完整测过。其他品牌装完要和家长一起试：按 Home 键、直接开视频 App、待机再唤醒，答题页都要出来，否则告诉家长这台电视拦不住。

## 小米电视的坑

- 重启后 ADB 调试会自动关掉，下次改规则前请家长在电视上再打开。
- 电视只认第一次点“允许”的那台电脑（`~/.android/adbkey`），换电脑要重新允许。
