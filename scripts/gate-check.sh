#!/bin/bash
# 读电视上“答题看电视”守门程序当天的运行证据（只读）。
# 用法：gate-check.sh [起始时间 HH:MM，默认 15:00]
# 输出：连接状态、守门服务是否在跑、当天答题／解锁／休息记录、前台应用时间线（合并连续重复）。
. "$(dirname "$0")/_device.sh"
[ -n "$TV" ] || { echo "config/device.env 里没写 TV="; exit 1; }
SINCE="$(date +%F) ${1:-15:00}"
$ADB connect $TV >/dev/null 2>&1
if ! $ADB -s $TV shell true >/dev/null 2>&1; then
  echo "ADB_UNREACHABLE (电视关机、深度待机，或 ADB 调试被关掉)"
  exit 0
fi
echo "SINCE $SINCE"
echo "== power: $($ADB -s $TV shell dumpsys power | grep -o 'mWakefulness=[A-Za-z]*')"
echo "== gate process pid: $($ADB -s $TV shell pidof com.ts.launcher) (empty = not running; a Restarting ServiceRecord alone does not count)"
echo "== home: $($ADB -s $TV shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1)"
echo "== gate.json: $($ADB -s $TV shell cat /sdcard/Android/data/com.ts.launcher/files/gate.json | sed 's/"pin": *"[0-9]*"/"pin":"****"/')"
echo "== gate log since $SINCE"
$ADB -s $TV shell cat /sdcard/Android/data/com.ts.launcher/files/gate-log.jsonl 2>/dev/null | awk -v s="$SINCE" -F'"t":"' '{split($2,a,"\""); if (a[1] >= s) print}'
echo "== foreground timeline since $SINCE"
$ADB -s $TV shell dumpsys usagestats | grep 'type=MOVE_TO_FOREGROUND' \
  | sed -E 's/.*time="([^"]+)".*package=([^ ]+) class=([^ ]+).*/\1 \2 \3/' \
  | awk -v s="$SINCE" '$1" "$2 >= s' | sort -u | awk '{k=$3" "$4; if (k!=last) print; last=k}'
