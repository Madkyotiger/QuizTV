#!/bin/bash
# 答题看电视：编译 tv-app 并装到电视（同签名升级，保留桌面设置和计时记录）。
# 用法：build_and_install.sh            编译＋跑单元测试＋安装＋读回
#       build_and_install.sh --build-only
# 签名和 ADB 授权都绑在 $ADB_HOME/.android（电视只认那把 adbkey；已装的 APK 只能用同一把 debug.keystore 升级），
# 所以 config/device.env 的 ADB_HOME 决定 HOME，不管哪个 Agent 在跑。绝不要 uninstall com.ts.launcher：会丢掉桌面配置和默认桌面设置。
# JDK 17 / Android SDK：优先用项目里的 toolchain/（不进 git），否则用系统已有的 JAVA_HOME / ANDROID_HOME。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
. "$ROOT/scripts/_device.sh"
J="$(ls -d "$ROOT"/toolchain/jdk/jdk-17* 2>/dev/null | head -1 || true)"
[ -n "$J" ] && export JAVA_HOME="$J"
[ -d "$ROOT/toolchain/android-sdk" ] && export ANDROID_HOME="$ROOT/toolchain/android-sdk"
[ -n "$ADB_HOME" ] && export ANDROID_USER_HOME="$ADB_HOME/.android" GRADLE_USER_HOME="$ADB_HOME/.gradle"
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$PATH"
APK="$ROOT/tv-app/launcher/app/build/outputs/apk/release/app-release.apk"

cd "$ROOT/tv-app/launcher"
bash ./gradlew testReleaseUnitTest assembleRelease -PlauncherApplicationId=com.ts.launcher --no-daemon --console=plain -q
echo "编译和单元测试通过：$(sha256sum "$APK" | cut -c1-16)…"
[ "${1:-}" = "--build-only" ] && exit 0

$ADB connect $TV >/dev/null 2>&1 || true
$ADB -s $TV shell true >/dev/null 2>&1 || { echo "连不上电视：要开着并打开 ADB 调试"; exit 1; }
ok=""
for i in 1 2 3; do
  out=$($ADB -s $TV install -r --no-streaming "$APK" 2>&1 | tail -1)
  [ "$out" = "Success" ] && { ok=1; break; }
  echo "安装第 $i 次没成功：$out"
  case "$out" in *UPDATE_INCOMPATIBLE*|*signatures*) echo "签名不一致：必须用第一次安装时那把 debug.keystore（ADB_HOME/.android）编译，不要卸载重装。"; exit 1;; esac
  sleep 5; $ADB connect $TV >/dev/null 2>&1 || true
done
[ -n "$ok" ] || exit 1
want=$(sha256sum "$APK" | cut -d' ' -f1)
sleep 4
got=$($ADB -s $TV shell 'sha256sum $(pm path com.ts.launcher | cut -d: -f2)' | cut -d' ' -f1)
[ "$want" = "$got" ] || { echo "电视上的程序和刚编译的不一致"; exit 1; }
svc=$($ADB -s $TV shell dumpsys activity services com.ts.launcher | grep -c 'gate.GateService' || true)
echo "已安装并读回一致（${want:0:16}…）；守门服务运行中：$([ "$svc" -gt 0 ] && echo 是 || echo 否)"
