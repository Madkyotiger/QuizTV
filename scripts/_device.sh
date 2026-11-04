# 被 build_and_install.sh / gate-check.sh source：读 config/device.env，环境变量 QUIZTV_* 可覆盖。
_QROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TV="" ADB="" ADB_HOME="" PYTHON=python3
[ -f "$_QROOT/config/device.env" ] && . "$_QROOT/config/device.env"
TV="${QUIZTV_TV:-$TV}"; ADB="${QUIZTV_ADB:-$ADB}"; ADB_HOME="${QUIZTV_ADB_HOME:-$ADB_HOME}"; PYTHON="${QUIZTV_PYTHON:-$PYTHON}"
ADB="${ADB:-$(command -v adb || echo adb)}"
# 电视只认第一次授权时那把 adb 钥匙（在 ADB_HOME/.android/adbkey）
[ -n "$ADB_HOME" ] && export HOME="$ADB_HOME"
