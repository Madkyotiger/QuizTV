# AGENTS.md — 给 AI 助手的说明

你在帮一位家长用 QuizTV（孩子先答题才能看电视）。家长通常不懂命令行：每步用一句大白话说你要做什么，需要家长在电视上按遥控器时，说清楚按哪里、看到什么算对，然后等她回复再继续。家长手里的教程是 `新手教程.md`，她看到的步骤编号和那里一致。

## 第一次装到电视（照顺序做，每步确认结果再往下）

**0. 先判断能不能装**
- 电视得是安卓系统（Android 8 及以上；小米、红米、海信、TCL、长虹等大多是）。华为鸿蒙智慧屏、苹果电视、三星／LG 自家系统装不了，直接告诉家长。
- 问家长电视品牌和型号（设置 →“关于”里有），在 `tv-app/docs/devices/` 和 `tv-app/examples/` 找同型号记录，只参考其中“默认桌面能不能换、怎么换”的部分，尤其是设默认桌面那一步；记录里的 `debloat.txt` 是上游项目清理电视用的停用清单，装 QuizTV 不需要，不要照着停用电视自带的程序。

**1. 家长在电视上用遥控器做（你一句一句告诉她）**
1. 电视连家里 Wi-Fi，和电脑同一个网络（不能是“访客网络”）。
2. 打开 ADB 调试。这个开关默认是隐藏的，要先打开开发者模式。小米／红米：设置 → 关于 → 选中“产品型号”，连续按遥控器“确定”7 次以上，直到提示进入开发者模式；再回到 设置 → 账号与安全，这时才会出现“ADB 调试”，选“允许”（小米官方说明 mi.com/global/support/article/KA-06513）。其他品牌多是 设置 →“关于”里连按“版本号”7 次，再到“开发者选项”打开“USB 调试”或“网络调试”；找不到看上面同型号记录，或请家长拍设置页照片给你。
3. 查电视 IP：小米 设置 → 网络（或“网络与连接”）→ 点开正在用的 Wi-Fi → IP 地址，形如 192.168.1.20。

**2. 你在电脑上准备**
- 先确认你自己跑在家长的这台电脑上（“本地电脑”模式），不是云端沙箱：云端连不到家里电视。是云端就请家长切换。
- 装 Android 官方 platform-tools（里面有 `adb`）：`https://dl.google.com/android/repository/platform-tools-latest-windows.zip`（Mac 把 `windows` 换成 `darwin`）；下不动就用腾讯镜像 `https://mirrors.cloud.tencent.com/AndroidSDK/platform-tools-latest-windows.zip`。解压到当前文件夹里就行，不用改系统 PATH，用完整路径调用。Mac 上被系统拦就 `xattr -dr com.apple.quarantine platform-tools`。
- 装 Python 3（Windows 可用微软应用商店或 `https://mirrors.huaweicloud.com/python/`），加题还要 `pyyaml`：`pip install pyyaml`，慢就加 `-i https://pypi.tuna.tsinghua.edu.cn/simple`。
- 本仓库里的 `.sh` 脚本在 Windows 上不能直接跑，不需要它们：装机只用 `adb` 和 `scripts/*.py`。
- 安装包用 Releases 里最新的 `QuizTV-*.apk`；家长是从网盘下载的，压缩包里已经带了 APK。不用装 JDK 和 Android SDK，除非要改代码。

**3. 连上电视**
1. `adb connect 电视IP:5555`。这时电视上会弹“允许调试吗”：请家长用方向键先选中“一律允许这台电脑”的小框按“确定”打勾，再选“允许”。弹窗一会儿会自己消失，没点上就重连一次。
2. `adb devices` 里这台电视要显示 `device`。显示 `unauthorized` 是电视上没点允许；连不上多半是 ADB 调试没开或不在同一网络。

**4. 安装并设成默认桌面**
1. `adb shell pm path com.ts.launcher` 必须什么都不输出。有输出说明电视上已有同名程序，停下来告诉家长，不要覆盖。
2. 记下原来的桌面，回退用：`adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME`，最后一行就是。
3. `adb install -r QuizTV-*.apk`。小米上如果被拒绝安装，请家长在 设置 → 账号与安全 打开“安装未知来源的应用”再装。
4. `adb shell cmd package set-home-activity --user 0 com.ts.launcher/com.gathin.tvhome.MainActivity`，再跑第 2 步的命令，最后一行要变成 `com.ts.launcher/com.gathin.tvhome.MainActivity`。
5. **不要停用、卸载电视原来的桌面**（小米的 PatchWall 必须保留），同型号记录里提到的上游安装脚本 `build-and-install.sh` 本仓库没有带（它默认会停用原桌面），不要去上游找来跑。小米只认白名单里的包名当桌面，所以包名固定是 `com.ts.launcher`；别的品牌如果设不成，看同型号记录。

**5. 写设置并推到电视**
1. 复制 `config/device.example.env`、`config/gate.example.json`、`config/secret.example.json`，去掉 `.example`。
2. 问家长：四位家长密码、孩子怎么称呼、哪几天几点要先答题（`windows`）、要不要分段看（`slots`、`slot_min`）、每天最多看多久、每轮几道题各科几道（`mix`）。密码只写进 `secret.json`。
3. `python3 -I scripts/tvctl.py push`，看到“读回一致”才算成功。
4. 内置题库是上海二年级数学和英语。孩子不是二年级，或者家长想按错题本出题，就按下面“按错题出题”加题，再用 `mix` 打开对应科目。

**6. 和家长一起试一遍，都通过才说“装好了”**
1. 临时打开守门：`python3 -I scripts/tvctl.py set test_until="今天日期 再过一小时，如 2026-11-08 15:00"`。
2. 请家长依次试：按遥控器 Home 键、直接打开常用的视频 App、按电源键待机再唤醒。每次都要出答题页。答完一轮要能正常看电视。
3. 请家长把电视关机再开一次，确认能正常开机、出答题页或桌面。小米重启后 ADB 调试会自己关掉，这是正常的。
4. 测完：`tvctl.py set test_until=`，再 `tvctl.py reset` 清掉测试的计时和答题记录。
5. 有一步拦不住，就告诉家长这台电视拦不住哪一种入口，不要说装好了。

**7. 交代给家长**
- 遥控器上的家长按钮：输密码可以“大人看电视”（到下次待机前不限时）或给孩子加 20 分钟。
- 以后想改时间、加题，再找你；小米电视要先在电视上重新打开 ADB 调试。
- 想停用：最快是 `tvctl.py set enabled=false`。想彻底换回原来的桌面：`adb shell cmd package set-home-activity --user 0 <第 4 步记下的原桌面>`，确认换回后才能卸载本程序。

## 常见任务

| 家长说 | 你做 |
|---|---|
| 按错题出题 | 读家长给的错题（照片、文字都行），写进 `custom-questions/questions.yaml`（格式见 `questions.example.yaml`）：同一知识点换数字和情境出新题，不照抄原题；错误选项用孩子真会犯的错；讲解一两句。然后 `add_questions.py check`（太长、电视上放不下的题会指出来），通过后 `add_questions.py push`。新科目要 `tvctl.py set mix=...` 才会出现 |
| 改时间／每次几道题 | `tvctl.py set ...`，键见 README“规则”表；改完看输出里的“读回一致” |
| 孩子说电视坏了／看不了 | 先 `tvctl.py show` 和 `tvctl.py log 30` 看是不是规则本来就在锁（时段外、到上限），再判断 |
| 升级到新版 | 下载 Releases 最新 APK，`adb install -r`（同一把签名，设置和记录保留），再 `tvctl.py show` |

## 不要做

- 不要 `adb uninstall com.ts.launcher`，也不要换签名重装：它是默认桌面，卸载会丢设置。升级只用 `adb install -r`。
- 不要把家长密码写进聊天记录、`gate.json` 或任何会上传的地方；只放 `config/secret.json`。
- 不要说“装好了”，除非 `tvctl.py show` 显示一致且守门服务在运行。
- 只在小米 MiTV-MFTP0 上完整测过；脚本在 Windows 上没测过（`.sh` 脚本要 Git Bash 或 WSL，`tvctl.py` 等 Python 工具应能直接用）。

## 小米电视的坑

- 重启后 ADB 调试会自动关掉，下次改规则前请家长在电视上再打开。
- 电视只认第一次点“允许”的那台电脑（`~/.android/adbkey`），换电脑要重新允许。
- 内存小的电视（如 MiTV-MFTP0，`getprop ro.config.low_ram` 为 true）看视频时，系统会因内存不够杀掉守门（`logcat -b events` 里 `am_proc_died … com.ts.launcher,200,3`），答题页就不弹了。可选的补救：打开“答题守门保活”——`settings get secure enabled_accessibility_services` 先读出原有值，在后面加 `:com.ts.launcher/com.gathin.tvhome.gate.GateKeepAliveService` 写回（不要覆盖原有服务），再 `settings put secure accessibility_enabled 1`。它不读任何内容、不拦按键，只让守门的优先级从 200 升到 100。每次待机后系统会把它移出列表，要再打开一次（电脑常开的话可以每 30 秒检查一次）。
