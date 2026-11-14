"""SiegeBot の jar に同梱するライブラリのライセンス一覧と、各 jar の LICENSE / NOTICE を集める。

使い方: python -I tools/third_party.py <出力フォルダ>
tools/deps.txt（mvn dependency:list の出力）と tools/m2（Maven の置き場）を読む。
"""
import re
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
M2 = ROOT / "tools" / "m2"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
# pom.xml から除いたもの（サーバー側のものを使う）
EXCLUDED = {("org.slf4j", "slf4j-api")}
# pom にライセンスの記載がないもの（配布元で確認した値）
KNOWN = {("com.standardwebhooks", "standardwebhooks"):
         [("Apache-2.0（github.com/standard-webhooks/standard-webhooks の LICENSE で確認）", "")]}


def pom_path(g, a, v):
    return M2 / Path(*g.split(".")) / a / v / f"{a}-{v}.pom"


def licenses_of(g, a, v, depth=0):
    p = pom_path(g, a, v)
    if not p.exists() or depth > 6:
        return []
    root = ET.parse(p).getroot()
    lic = [(e.findtext("m:name", "", NS).strip(), e.findtext("m:url", "", NS).strip())
           for e in root.findall("m:licenses/m:license", NS)]
    if lic:
        return lic
    parent = root.find("m:parent", NS)
    if parent is None:
        return []
    return licenses_of(parent.findtext("m:groupId", "", NS), parent.findtext("m:artifactId", "", NS),
                       parent.findtext("m:version", "", NS), depth + 1)


def main():
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    deps = []
    for line in (ROOT / "tools" / "deps.txt").read_text(encoding="utf-8").splitlines():
        m = re.match(r"\s*([\w.\-]+):([\w.\-]+):jar:([\w.\-]+):(compile|runtime)", line)
        if m and (m.group(1), m.group(2)) not in EXCLUDED:
            deps.append((m.group(1), m.group(2), m.group(3)))
    lines = [
        "SiegeBot-0.1.0.jar に同梱している第三者のライブラリ",
        "=" * 48,
        "",
        "SiegeBot の jar には、Claude API を呼ぶための SDK と、その依存ライブラリが入っています（改変なし）。",
        "各ライブラリの LICENSE / NOTICE は、このフォルダの bundled/ に jar から取り出して収めています。",
        "",
    ]
    for g, a, v in sorted(deps):
        lic = KNOWN.get((g, a)) or licenses_of(g, a, v)
        names = "; ".join(f"{n} ({u})" if u else n for n, u in lic) or "（pom にライセンスの記載なし）"
        lines.append(f"- {g}:{a}:{v}")
        lines.append(f"    ライセンス: {names}")
        jar = M2 / Path(*g.split(".")) / a / v / f"{a}-{v}.jar"
        if jar.exists():
            with zipfile.ZipFile(jar) as z:
                for info in z.infolist():
                    name = info.filename
                    base = name.rsplit("/", 1)[-1].upper()
                    if info.is_dir() or not name.upper().startswith("META-INF/"):
                        continue
                    if base.startswith(("LICENSE", "NOTICE")) or "/LICENSES/" in name.upper():
                        dest = out / "bundled" / f"{a}-{v}" / name.split("/", 1)[1]
                        dest.parent.mkdir(parents=True, exist_ok=True)
                        dest.write_bytes(z.read(info))
    (out / "SiegeBot-THIRD-PARTY.txt").write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    print(f"{len(deps)} libraries")


if __name__ == "__main__":
    main()
