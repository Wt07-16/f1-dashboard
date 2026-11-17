#!/bin/bash
# F1 Dashboard 双击启动器 —— 自动定位当前脚本所在目录,不硬编码个人路径
# 已在运行 → 把窗口带到前台;未运行 → 后台启动,关掉本终端窗口不影响应用

PROJ="$(cd "$(dirname "$0")" && pwd)"
MVN="$(command -v mvn || echo "/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn")"
LOG=/tmp/f1run.log

# ---- 已在运行:直接带窗口到前台,不再开第二个实例 ----
PID="$(pgrep -f 'f1\.app\.Main' | head -1)"
if [ -n "${PID}" ]; then
  osascript -e "tell application \"System Events\" to set frontmost of (first process whose unix id is ${PID}) to true" >/dev/null 2>&1
  echo "F1 Dashboard 已在运行,窗口已带到前台。"
  echo "(没看到窗口的话,检查 Dock 或按 Cmd+Tab)"
  sleep 2
  exit 0
fi

# ---- 未运行:后台启动(首次需要编译,约 10-30 秒)----
echo "正在启动 F1 Dashboard…(首次启动需编译,约 10-30 秒;之后约 5 秒)"
cd "${PROJ}" || { echo "✗ 找不到项目目录: ${PROJ}"; sleep 3; exit 1; }
nohup "${MVN}" -q -f pom.xml javafx:run > "${LOG}" 2>&1 &

for _ in $(seq 1 20); do
  sleep 1
  if pgrep -f 'f1\.app\.Main' > /dev/null; then
    echo "✓ 启动成功,窗口即将出现。"
    echo "(以后直接双击本文件即可打开;右键菜单里可退出应用)"
    sleep 2
    exit 0
  fi
done
echo "✗ 20 秒内未检测到窗口,启动可能失败 —— 日志: ${LOG}"
sleep 3
