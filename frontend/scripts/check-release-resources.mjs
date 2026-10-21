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
    // 瘦 jar 的警戒线，见下面的 MIN_JAR_BYTES。
    minBytes: 20 * 1024 * 1024,
    hint: "in backend/ run `mvn -B -q -DskipTests package`, then copy backend/target/change-backend-0.1.0-SNAPSHOT.jar to frontend/src-tauri/resources/change-backend.jar",
  },
  {
    path: join(srcTauri, "resources", "runtime", "bin", "java.exe"),
    hint: "run backend/scripts/jlink-runtime.ps1 to produce frontend/src-tauri/resources/runtime",
  },
];

/**
 * jar 的大小警戒线。
 *
 * 后端是 Spring Boot **fat jar**，约 80 MB。而 `mvn package` 的 repackage 一步
 * 一旦失败（最常见的原因：上一次跑起来的 `java -jar` 还占着文件、Windows 上
 * 改不了名），`target` 里会留下一个 **700 KB 左右的瘦 jar**——只有自己的 class，
 * 没有任何依赖。
 *
 * 那种 jar 会被正常拷进资源目录、构建会成功、安装也会成功，然后在用户的机器上
 * 一启动就 NoClassDefFoundError。
 *
 * **2026-10-21 真的发生过一次**：安装包从 104 MB 缩到 33 MB，而这条脚本
 * 一声没吭（它当时只检查文件在不在）。所以现在多一道大小检查——
 * 体积是最难骗过的信号。
 */
const MIN_JAR_BYTES = 20 * 1024 * 1024;

function isFile(path) {
  try {
    return statSync(path).isFile();
  } catch {
    return false;
  }
}

const tooSmall = required
  .filter((entry) => entry.minBytes !== undefined && isFile(entry.path))
  .filter((entry) => statSync(entry.path).size < entry.minBytes);

if (tooSmall.length > 0) {
  console.error("");
  console.error(
    "ERROR: a bundled resource is far too small — it is almost certainly a broken build.",
  );
  console.error("");
  for (const entry of tooSmall) {
    const mb = (statSync(entry.path).size / 1024 / 1024).toFixed(1);
    console.error(`  ${entry.path}`);
    console.error(`  is ${mb} MB, expected at least ${entry.minBytes / 1024 / 1024} MB`);
  }
  console.error("");
  console.error(
    "A few hundred KB means `mvn package` wrote a thin jar: its repackage step failed.",
  );
  console.error(
    "That usually happens because a previous `java -jar` still holds the file.",
  );
  console.error(
    "  fix: kill stray java processes, then in backend/ run `mvn -B -q -DskipTests package`",
  );
  console.error("       and copy the jar into frontend/src-tauri/resources/ again.");
  console.error("");
  process.exit(1);
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
