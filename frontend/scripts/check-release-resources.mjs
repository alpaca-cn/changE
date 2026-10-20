// 构建前检查：缺资源的安装包**会构建成功**，然后在朋友的机器上一启动就失效
// （找不到 jar，或找不到随包 JRE）。所以让 `tauri build` 在这里直接失败。
//
// 为什么由 `tauri.conf.json > build > beforeBuildCommand` 调用，而不是一个
// `build:release` npm script：文档里的命令是 `pnpm tauri build`，它只会跑
// `beforeBuildCommand`；包一个 npm script 等于留了一条绕过检查的路。
//
// 为什么用 Node 而不是 PowerShell：脚本要在任何平台上用同一套语义跑，且 Node
// 是前端既有的构建依赖。PowerShell 5.1 读无 BOM 的 UTF-8 会按 ANSI 解码，
// 这种行为不该出现在构建链路上。
//
// 输出消息刻意只用 ASCII 英文：Windows 的 cmd/PowerShell 控制台代码页未必是
// UTF-8，中文提示在构建日志里可能变成乱码——那时它就不叫「告诉人怎么做」了。
// `backend/scripts/jlink-runtime.ps1` 出于同样的理由也只用 ASCII。

import { statSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const srcTauri = join(here, "..", "src-tauri");

// 路径与 `tauri.conf.json > bundle > resources` 的源路径必须一致：
//   resources/change-backend.jar -> <resource_dir>/change-backend.jar
//   resources/runtime/           -> <resource_dir>/runtime/
const required = [
  {
    path: join(srcTauri, "resources", "change-backend.jar"),
    hint: "in backend/ run `mvn -B -q -DskipTests package`, then copy backend/target/change-backend-0.1.0-SNAPSHOT.jar to frontend/src-tauri/resources/change-backend.jar",
  },
  {
    path: join(srcTauri, "resources", "runtime", "bin", "java.exe"),
    hint: "run backend/scripts/jlink-runtime.ps1 to produce frontend/src-tauri/resources/runtime",
  },
];

function isFile(path) {
  try {
    return statSync(path).isFile();
  } catch {
    return false;
  }
}

const missing = required.filter((entry) => !isFile(entry.path));

if (missing.length > 0) {
  console.error("");
  console.error(
    "ERROR: refusing to build an installer without its bundled resources.",
  );
  console.error(
    "`tauri build` would succeed, but the installed app would fail to start on the target machine.",
  );
  console.error("");
  for (const entry of missing) {
    console.error(`  missing file: ${entry.path}`);
    console.error(`  how to produce it: ${entry.hint}`);
    console.error("");
  }
  console.error("Fix the files above, then re-run `pnpm tauri build`.");
  process.exit(1);
}

for (const entry of required) {
  console.log(`[bundled-resources] ok: ${entry.path}`);
}
