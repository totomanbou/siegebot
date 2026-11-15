"""本サーバーの管理者に確認してもらうための zip を作る。

使い方: python -I tools/make_review_zip.py <審査用の文書とライセンスを置いたフォルダ>
そのフォルダには 審査用の説明.txt・導入手順.txt・licenses/ があること。
プロジェクトの直下に SiegeBot-本サーバー用.zip を作る（前の zip は置き換える）。
zip の中のパスは / 区切り、日本語のファイル名は UTF-8 の印付きで書く（Linux で展開しても崩れない）。
"""
import hashlib
import io
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOCS = Path(sys.argv[1])
OUT = ROOT / "SiegeBot-本サーバー用.zip"

PLUGINS = {
    "plugins/SiegeBot-0.3.2.jar": ROOT / "target" / "SiegeBot-0.3.2.jar",
    "plugins/Citizens.jar": ROOT / "testserver" / "plugins" / "Citizens.jar",
    "plugins/ItemJoin.jar": ROOT / "testserver" / "plugins" / "ItemJoin.jar",
    "plugins/ItemJoin/items.yml": ROOT / "itemjoin" / "items.yml",
}
# source/SiegeBot-src.zip に入れるもの（ビルドに要る分だけ。試験サーバー・道具・成果物は入れない）
SOURCE_FILES = ["pom.xml", "build.cmd", ".gitattributes", "README.md", "LICENSE"]
SOURCE_DIRS = ["src", "itemjoin"]


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def source_zip():
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for name in SOURCE_FILES:
            z.write(ROOT / name, f"SiegeBot-src/{name}")
        for d in SOURCE_DIRS:
            for p in sorted((ROOT / d).rglob("*")):
                if p.is_file():
                    z.write(p, "SiegeBot-src/" + p.relative_to(ROOT).as_posix())
    return buf.getvalue()


def main():
    for need in ["審査用の説明.txt", "導入手順.txt", "licenses"]:
        if not (DOCS / need).exists():
            raise SystemExit(f"見つからない: {DOCS / need}")
    for arc, src in PLUGINS.items():
        if not src.exists():
            raise SystemExit(f"見つからない: {src}")

    entries = {}  # zip の中のパス -> 中身
    for arc, src in PLUGINS.items():
        entries[arc] = src.read_bytes()
    entries["source/SiegeBot-src.zip"] = source_zip()
    entries["README.md"] = (ROOT / "README.md").read_bytes()
    for name in ["審査用の説明.txt", "導入手順.txt"]:
        entries[name] = (DOCS / name).read_bytes()
    for p in sorted((DOCS / "licenses").rglob("*")):
        if p.is_file():
            entries["licenses/" + p.relative_to(DOCS / "licenses").as_posix()] = p.read_bytes()

    sums = [f"{sha256(entries[a])}  {a}" for a in sorted(entries) if a.startswith(("plugins/", "source/"))]
    entries["SHA256SUMS.txt"] = ("\n".join(sums) + "\n").encode("utf-8")

    if OUT.exists():
        OUT.unlink()
    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
        for arc in sorted(entries):
            z.writestr(arc, entries[arc])
    sys.stdout.reconfigure(encoding="utf-8")
    print(f"{OUT.name}: {OUT.stat().st_size} bytes, {len(entries)} files")
    for line in sums:
        print(line)


if __name__ == "__main__":
    main()
