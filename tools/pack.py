#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
打完整包：gradle 出 launcher 的 release apk → 合入游戏移植运行时 → zipalign → 用 keystore 签名。

为什么需要这一步：
    launcher 自己只编译启动器代码（几 MB），游戏运行时（classes4.dex、libddport.so、
    assets/ui_merge_base.zip 等）不在源码里，得从游戏安装包里搬。所以仓库里没有
    gradlew assembleRelease 直接产出的成品，必须再合一次。

用法（在仓库任意位置执行都行）：
    python tools/pack.py                # 编译 + 合并 + 签名，输出 dist/DD-DarkestLauncher.apk
    python tools/pack.py --no-build     # 跳过 gradle，直接用现有 build 产物
    python tools/pack.py --from D:/path/to/game.apk   # 指定运行时来源（默认自动找）
    python tools/pack.py --offline false              # 允许 gradle 联网拉依赖

运行时来源的查找顺序：
    1. --from 指定的 apk
    2. 仓库上层目录里的游戏安装包（Darkest Dungeon_*.apk 之类）
    3. 上一次打好的 dist/DD-DarkestLauncher.apk（注意：这条是「从上一次成品里搬」，
       能用但会继承上一次的问题，来源干净时优先用 1/2）

依赖：Android SDK（build-tools 里有 zipalign / apksigner）、JDK、keystore.properties。
需要 .gitignore 里那几项：keystore.properties、keystore/ 都不能进仓库。
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
import time
import zipfile
from pathlib import Path

# 这些条目来自游戏安装包，launcher 源码里没有
RUNTIME = [
    "classes4.dex",
    "lib/arm64-v8a/libc++_shared.so",
    "lib/arm64-v8a/libddastc.so",
    "lib/arm64-v8a/libddnx.so",
    "lib/arm64-v8a/libddport.so",
    "assets/base_png.txt",
    "assets/layout_keys.txt",
    "assets/pc_fonts.zip",
    "assets/ui_merge_base.zip",
]

SIG_SUFFIX = (".sf", ".rsa", ".dsa", ".ec")
APK_OUT_NAME = "DD-DarkestLauncher.apk"


def repo_root() -> Path:
    """脚本放在 <repo>/tools/pack.py"""
    return Path(__file__).resolve().parent.parent


def is_windows() -> bool:
    return os.name == "nt"


def die(msg: str) -> None:
    print(f"[x] {msg}", file=sys.stderr)
    sys.exit(1)


def info(msg: str) -> None:
    print(f"[*] {msg}")


# ---------------------------------------------------------------- SDK / 签名

def find_sdk(root: Path) -> Path:
    """环境变量 → local.properties → 常见安装位置"""
    for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        v = os.environ.get(key)
        if v and Path(v).is_dir():
            return Path(v)

    local = root / "local.properties"
    if local.is_file():
        for line in local.read_text(encoding="utf-8", errors="ignore").splitlines():
            m = re.match(r"\s*sdk\.dir\s*=\s*(.+)$", line)
            if m:
                p = Path(m.group(1).strip().replace("\\\\", "\\").replace("\\:", ":"))
                if p.is_dir():
                    return p

    for guess in ("D:/Android/SDK", "C:/Android/SDK", str(Path.home() / "Android/Sdk")):
        if Path(guess).is_dir():
            return Path(guess)
    die("找不到 Android SDK，设 ANDROID_HOME 或在 local.properties 里写 sdk.dir")


def find_build_tools(sdk: Path) -> Path:
    """取版本号最大、且 zipalign 和 apksigner 都在的那个 build-tools"""
    bt = sdk / "build-tools"
    if not bt.is_dir():
        die(f"{bt} 不存在，装一下 Android SDK Build-Tools")
    signer = "apksigner.bat" if is_windows() else "apksigner"
    align = "zipalign.exe" if is_windows() else "zipalign"

    def version_key(p: Path):
        parts = re.findall(r"\d+", p.name)
        return [int(x) for x in parts] or [0]

    usable = [d for d in bt.iterdir() if (d / signer).is_file() and (d / align).is_file()]
    if not usable:
        die(f"{bt} 下没有同时包含 zipalign 和 apksigner 的版本")
    return sorted(usable, key=version_key)[-1]


def read_props(root: Path) -> dict:
    f = root / "keystore.properties"
    if not f.is_file():
        die("缺少 keystore.properties（storeFile / storePassword / keyAlias / keyPassword）")
    out = {}
    for line in f.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        out[k.strip()] = v.strip()
    for k in ("storeFile", "storePassword", "keyAlias", "keyPassword"):
        if not out.get(k):
            die(f"keystore.properties 缺 {k}")
    return out


# ---------------------------------------------------------------- 运行时来源

def zip_has_runtime(apk: Path) -> bool:
    try:
        with zipfile.ZipFile(apk) as z:
            names = set(z.namelist())
    except Exception:
        return False
    return all(n in names for n in RUNTIME)


def pick_runtime_source(root: Path, explicit: str | None) -> Path:
    if explicit:
        p = Path(explicit).expanduser().resolve()
        if not p.is_file():
            die(f"--from 指定的文件不存在：{p}")
        if not zip_has_runtime(p):
            die(f"{p} 里缺少运行时条目，不像是完整的游戏包")
        return p

    # 上层目录里的游戏安装包
    parent = root.parent
    candidates = []
    for f in parent.glob("*.apk"):
        if f.name.lower().startswith("dd-darkestlauncher"):
            continue
        if zip_has_runtime(f):
            candidates.append(f)
    if candidates:
        candidates.sort(key=lambda p: p.stat().st_size, reverse=True)
        return candidates[0]

    # 兜底：上一次的成品
    prev = root / "dist" / APK_OUT_NAME
    if prev.is_file() and zip_has_runtime(prev):
        info("警告：运行时从上一次的成品里搬的，来源是 dist/ 里的旧包")
        return prev

    die("找不到带运行时的安装包：把游戏 APK 放到仓库上层目录，或用 --from 指定")


# ---------------------------------------------------------------- 各步骤

def run(cmd, **kw):
    print("    $", " ".join(str(c) for c in cmd))
    return subprocess.run([str(c) for c in cmd], check=True, **kw)


def gradle_build(root: Path, offline: bool):
    wrapper = root / ("gradlew.bat" if is_windows() else "gradlew")
    if not wrapper.is_file():
        die(f"找不到 {wrapper}（Linux/macOS 上先 chmod +x gradlew）")
    cmd = [wrapper, ":app:assembleRelease"]
    if offline:
        cmd.append("--offline")
    info("编译 release apk" + ("（offline）" if offline else ""))
    run(cmd, cwd=root)


def merge(start: Path, src: Path, stage: Path):
    """把运行时按原样塞进 launcher 产物，顺手去掉旧签名文件"""
    stage.parent.mkdir(parents=True, exist_ok=True)
    if stage.exists():
        stage.unlink()

    with zipfile.ZipFile(start) as z:
        runtime = [(z.getinfo(n), z.read(n)) for n in RUNTIME]

    written = set()
    with zipfile.ZipFile(src) as s, zipfile.ZipFile(stage, "w") as dst:
        for info_ in s.infolist():
            name = info_.filename
            low = name.lower()
            # 旧签名一旦改了内容就失效，必须全部丢掉重签
            if name.startswith("META-INF/") and low.endswith(SIG_SUFFIX + (".mf",)):
                continue
            dst.writestr(info_, s.read(name))
            written.add(name)
        for info_, data in runtime:
            if info_.filename in written:
                continue  # 产物里已有同名条目就保留产物自己的
            dst.writestr(info_, data)
            written.add(info_.filename)
    return len(runtime)


def main():
    ap = argparse.ArgumentParser(description="打完整包（编译 + 合运行时 + 对齐 + 签名）")
    ap.add_argument("--no-build", action="store_true", help="跳过 gradle，直接用现有产物")
    ap.add_argument("--from", dest="source", help="运行时来源 apk（默认自动查找）")
    ap.add_argument("--offline", default="true", help="gradle 是否离线，默认 true")
    ap.add_argument("--out", help="输出路径，默认 dist/DD-DarkestLauncher.apk")
    args = ap.parse_args()

    t0 = time.time()
    root = repo_root()
    src_apk = root / "app/build/outputs/apk/release/app-release.apk"
    out = Path(args.out).resolve() if args.out else root / "dist" / APK_OUT_NAME
    stage = root / "build/tmp/pack-merge.apk"
    aligned = root / "build/tmp/pack-merge-aligned.apk"

    info(f"仓库 {root}")
    sdk = find_sdk(root)
    tools = find_build_tools(sdk)
    info(f"SDK {sdk}  build-tools {tools.name}")
    props = read_props(root)

    if not args.no_build:
        gradle_build(root, args.offline.strip().lower() not in ("false", "0", "no"))
    if not src_apk.is_file():
        die(f"没有 {src_apk}，先跑一次 gradlew :app:assembleRelease")

    source = pick_runtime_source(root, args.source)
    info(f"运行时来源 {source.name}（{source.stat().st_size / 1048576:.1f} MB）")

    info("合并运行时")
    n = merge(source, src_apk, stage)
    info(f"合入 {n} 个运行时条目")

    if aligned.exists():
        aligned.unlink()
    info("zipalign")
    run([tools / ("zipalign.exe" if is_windows() else "zipalign"), "-f", "-p", "4", stage, aligned])

    out.parent.mkdir(parents=True, exist_ok=True)
    if out.exists():
        out.unlink()
    ks = (root / props["storeFile"]).resolve()
    if not ks.is_file():
        die(f"签名文件不存在：{ks}")

    info("签名（v1/v2/v3/v4）")
    signer = tools / ("apksigner.bat" if is_windows() else "apksigner")
    run([
        signer, "sign",
        "--ks", ks,
        "--ks-pass", f"pass:{props['storePassword']}",
        "--ks-key-alias", props["keyAlias"],
        "--key-pass", f"pass:{props['keyPassword']}",
        "--v1-signing-enabled", "true",
        "--v2-signing-enabled", "true",
        "--v3-signing-enabled", "true",
        "--v4-signing-enabled", "true",
        "--out", out,
        aligned,
    ])

    info("校验签名")
    run([signer, "verify", "--print-certs", out])

    # 清掉中间产物，别让 build/tmp 越堆越多
    for tmp in (stage, aligned):
        try:
            tmp.unlink()
        except OSError:
            pass

    size = out.stat().st_size
    print()
    info(f"完成 {out}")
    info(f"大小 {size / 1048576:.2f} MB（{size} 字节）")
    info(f"耗时 {time.time() - t0:.1f}s")
    info(f"装机：adb install -r \"{out}\"")


if __name__ == "__main__":
    main()
