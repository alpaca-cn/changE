; 安装前先关掉正在运行的 changE。
;
; 为什么：应用运行时 `runtime\bin\java.dll` 被后端进程占用，NSIS 写不进去，
; 弹出「Error opening file for writing: ...\java.dll」。2026-10-25 一天之内撞了三次。
;
; 只杀 `change.exe`（外壳）：外壳退出时会带走它拉起的 Java 子进程。
; **不要 taskkill java.exe** —— 用户可能同时开着别的 Java 程序，
; 为了装更新把别人的东西杀掉是不可接受的副作用。
!macro NSIS_HOOK_PREINSTALL
  DetailPrint "正在关闭 changE…"
  nsExec::Exec 'taskkill /F /IM change.exe'
  Pop $0
  Sleep 1500
!macroend
