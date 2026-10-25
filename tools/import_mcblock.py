# -*- coding: utf-8 -*-
"""离线登记用户有权使用的 MCBlock 投影；不请求网站、不登录、不改写投影。

先用 --list-candidates 查看候选作品。通过作品页正常下载后，运行：
  python tools/import_mcblock.py --file <原文件.litematic> --source-url <作品详情URL>
      --author <作者> --license-note <实际授权依据与使用限制> [--dry-run]
导入后在游戏建筑库点“刷新导入”，由游戏检查方块与状态兼容性。
"""

import argparse
import gzip
import hashlib
import json
import re
import struct
from datetime import datetime, timezone
from io import BytesIO
from pathlib import Path
from urllib.parse import urlsplit

from extract_terrain import read_nbt


DEFAULT_SCHEMATICS = Path("schematics")
MAX_FILE = 8 * 1024 * 1024
MAX_NBT = 64 * 1024 * 1024
MAX_DATA_VERSION = 3955  # Minecraft Java 1.21 / 1.21.1；不把新版本伪装成旧版本。
EXTENSIONS = {".nbt", ".snbt", ".litematic", ".schem"}
TERMS_URL = "https://mcblock.top/terms"
CANDIDATES = (
    ("c8db481c-e86e-47ae-976d-2a870ab2855b", "茶坊", "JPCore"),
    ("7583ff98-a659-487c-b71a-731075f99b0c", "玉桂狗咖啡厅", "MassiveSpeck"),
    ("be8ccb2d-9714-45c8-a2d7-10c5acc3ac6d", "樱花小屋", "MassiveSpeck"),
)


def candidate_list():
    for identity, title, author in CANDIDATES:
        print(json.dumps({
            "title": title, "author": author,
            "sourceUrl": "https://mcblock.top/buildings/" + identity,
            "attributionStatus": "platform-verified",
            "dataVersion": None,
            "permissionStatus": "unconfirmed",
            "downloadStatus": "login-required-not-downloaded",
            "checkedOn": "2026-10-22",
        }, ensure_ascii=False))
    print("以上仅为候选，新增投影数为 0；版本与使用范围待确认。")
    print("通过作品页的正常入口手动下载；如需登录、验证码或付费，由用户自行处理。")
    print("仅导入有权本地学习的文件，不再分发，不打包进 JAR。条款：" + TERMS_URL)


def text(value, field, limit):
    # Java 的 String.length 统计 UTF-16 单元，元数据须与目录校验一致。
    if not value or not value.strip() or len(value.encode("utf-16-le")) // 2 > limit:
        raise ValueError(f"{field} 不能为空且不能超过 {limit} 个 UTF-16 单元")
    if any(ord(char) < 32 and char != "\n" for char in value):
        raise ValueError(f"{field} 含不支持的控制字符")
    return value


def source_url(value):
    url = urlsplit(value)
    if (url.scheme != "https" or url.netloc != "mcblock.top"
            or url.query or url.fragment
            or not re.fullmatch(r"/buildings/[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}", url.path)):
        raise ValueError("来源必须是 https://mcblock.top/buildings/<作品UUID> 详情页")
    return value


def original_file(path):
    path = path.resolve(strict=True)
    extension = path.suffix.lower()
    if extension not in {".litematic", ".schem", ".nbt"}:
        raise ValueError("离线版本检查支持 .litematic / .schem / .nbt；请勿仅改扩展名")
    with path.open("rb") as stream:
        original = stream.read(MAX_FILE + 1)
    if not original or len(original) > MAX_FILE:
        raise ValueError("原投影必须非空且不超过 8 MiB")
    # 与 BlueprintStore 一致，只接收 gzip NBT；限制解压量后复用已有 NBT 读取器。
    with gzip.GzipFile(fileobj=BytesIO(original)) as stream:
        raw = stream.read(MAX_NBT + 1)
    if len(raw) > MAX_NBT or not raw or raw[0] != 10:
        raise ValueError("投影必须是最多 64 MiB 的 NBT compound")
    root = read_nbt(raw)
    if extension == ".litematic":
        if not isinstance(root.get("Regions"), dict) or not root["Regions"]:
            raise ValueError("litematic 缺少 Regions")
        version = root.get("MinecraftDataVersion")
    elif extension == ".schem":
        schematic = root.get("Schematic", root)
        if not isinstance(schematic, dict) or not all(
                key in schematic for key in ("Width", "Height", "Length")):
            raise ValueError("schem 缺少尺寸")
        version = schematic.get("DataVersion")
    else:
        if "size" not in root or "blocks" not in root or not (
                "palette" in root or "palettes" in root):
            raise ValueError("nbt 不是原版结构格式")
        version = root.get("DataVersion")
    if type(version) is not int or version <= 0:
        raise ValueError("缺少有效 DataVersion，不能确认适配 MC1.21.1；请按旧版重新导出")
    if version > MAX_DATA_VERSION:
        raise ValueError(f"源 DataVersion={version} 高于 MC1.21.1 的 {MAX_DATA_VERSION}，拒绝导入")
    return path, original, version


def metadata(args, path, original, version):
    title = text(args.title or path.stem, "title", 160)
    author = text(args.author, "author", 160)
    source = text(source_url(args.source_url), "sourceUrl", 600)
    license_note = text(args.license_note, "license", 600)
    return {
        "title": title, "author": author, "sourceUrl": source, "license": license_note,
        "description": "用户手动下载的 MCBlock 原投影；原始字节保留。仅检查文件数据版本上限，"
                       "尚未通过游戏方块/属性/尺寸/格数检查；请在建筑库刷新导入。"
                       "署名与授权依据由用户提供，脚本不据此认定再分发许可。",
        "tags": ["MCBlock", "手动导入"],
        # 附加审计字段；ArchitectureCatalog 仍使用原有六个显示字段。
        "provenance": {
            "originalFileName": path.name,
            "sha256": hashlib.sha256(original).hexdigest(),
            "dataVersion": version,
            "maximumTargetDataVersion": MAX_DATA_VERSION,
            "importedAt": datetime.now(timezone.utc).isoformat(),
            "acquisition": "user-downloaded-local-file",
            "permissionStatus": "user-supplied-not-independently-verified",
            "compatibilityStatus": "requires-in-game-refresh",
            "termsUrl": TERMS_URL,
        },
    }


def import_file(args):
    path, original, version = original_file(args.file)
    name = text(path.stem, "文件名", 120)
    info = metadata(args, path, original, version)
    encoded = (json.dumps(info, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    if len(encoded) > 32768:
        raise ValueError("目录元数据超过 32 KiB")
    target = args.schematics.resolve()
    if target.exists() and not target.is_dir():
        raise ValueError("schematics 目标不是目录")
    if target.exists():
        for entry in target.iterdir():
            # 同名不同格式会被 BlueprintStore 按优先级遮蔽，不能视作成功导入。
            if ((entry.suffix.lower() in EXTENSIONS and entry.stem.casefold() == name.casefold())
                    or entry.name.casefold() == (name + ".catalog.json").casefold()):
                raise ValueError(f"目标已有同名投影或元数据，未覆盖：{entry.name}")
    destination = target / path.name
    sidecar = target / (name + ".catalog.json")
    report = {
        "status": "dry-run" if args.dry_run else "staged-requires-in-game-refresh",
        "file": str(destination), "metadata": str(sidecar),
        "dataVersion": version, "sha256": info["provenance"]["sha256"],
        "compatible": None,
    }
    if not args.dry_run:
        target.mkdir(parents=True, exist_ok=True)
        created = []
        try:
            for output, data in ((sidecar, encoded), (destination, original)):
                with output.open("xb") as stream:
                    created.append(output)
                    stream.write(data)
        except OSError:
            # 仅回滚本次独占创建的文件，不删除或覆盖已有投影。
            for output in reversed(created):
                output.unlink()
            raise
    print(json.dumps(report, ensure_ascii=False, indent=2))
    print("版本上限检查通过不等于兼容通过。请在游戏建筑库点“刷新导入”，查看检查结果。")
    print("每次刷新最多解码 8 个变更文件；存在剩余文件时再刷新。")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--list-candidates", action="store_true", help="显示三个未下载候选及手动流程")
    parser.add_argument("--file", type=Path, help="用户通过正常入口下载且有权使用的本地原文件")
    parser.add_argument("--source-url", help="MCBlock 作品详情 URL，不能填站点首页")
    parser.add_argument("--author", help="实际作者署名；以作品页及原始发布信息核实")
    parser.add_argument("--license-note", help="实际本地使用依据、授权状态与再分发限制")
    parser.add_argument("--title", help="可选作品标题；默认原文件名")
    parser.add_argument("--schematics", type=Path, default=DEFAULT_SCHEMATICS,
                        help="目标实例 schematics 目录；默认当前目录下的 schematics；建议显式传入本人实例路径")
    parser.add_argument("--dry-run", action="store_true", help="只检查并显示计划，不写文件")
    args = parser.parse_args()
    if args.list_candidates:
        candidate_list()
        return
    for option in ("file", "source_url", "author", "license_note"):
        if not getattr(args, option):
            parser.error("导入需要 --" + option.replace("_", "-"))
    try:
        import_file(args)
    except (OSError, ValueError, EOFError, IndexError, KeyError, TypeError, RecursionError, struct.error) as error:
        parser.exit(1, f"未导入：{error}\n")


if __name__ == "__main__":
    main()
