"""小さなローカルモデルで、チャットの指示を方針に直せるかを測る。

プラグインと同じシステムプロンプト（LlmClient.java から読む）と同じ状況の書き方で Ollama に問い合わせ、
返ってきた JSON をプラグインと同じ規則（OrderResolver を写したもの）で方針に直して、正解と比べる。
CPU だけで動かす（num_gpu=0）。Agames のサーバーには GPU がないため。

使い方: python llm_bench.py <model> [--threads N] [--only 1,2,3]
"""
import argparse
import json
import statistics
import sys
import time
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
JAVA = HERE.parent / "src" / "main" / "java" / "siegebot" / "LlmClient.java"
OLLAMA = "http://127.0.0.1:11434"


def system_prompt():
    """Java のテキストブロック SYSTEM_PROMPT を、Java と同じ規則で文字列に戻す。"""
    src = JAVA.read_text(encoding="utf-8").splitlines()
    start = next(i for i, l in enumerate(src) if 'SYSTEM_PROMPT = """' in l)
    end = next(i for i in range(start + 1, len(src)) if src[i].strip().startswith('""";'))
    body = src[start + 1:end + 1]
    indent = min(len(l) - len(l.lstrip()) for l in body if l.strip())
    lines = [l[indent:] if l.strip() else "" for l in body]
    text = "\n".join(lines[:-1]) + "\n"  # 閉じの """ の行は改行だけを残す
    return text.replace("\\\n", "")


FEWSHOT_PROMPT = """あなたはマインクラフトの攻城戦で、ボット兵士への指示を JSON に直す係です。
味方の発言を読み、方針を変えるボットだけを orders に入れます。

mode の意味:
- front: 前線で戦う（「攻めろ」「前線へ」「突撃」など。場所の名前が無いとき）
- hold: place の場所を守る（場所の名前か別名が出たときだけ）
- follow: player についていく（「ついてきて」「護衛」など。「俺」「me」は発言者の名前にする）
stance: 「突っ込め」「積極的に」は push、「深追いするな」「無理するな」「待機」は guard、それ以外は空文字。
bot: 個別の名前（「ボット1」「1番」は A1 のように番号で対応）。全員・名前なしは "*"。
指示でない発言（雑談・質問・できないこと）は orders を空にする。

例（ボット B1, B2 / 場所 西の塔, 橋 / 味方 Taro, Hana）:
[Taro] 前線に出ろ -> {"orders":[{"bot":"*","mode":"front","place":"","player":"","stance":""}],"reply":"了解"}
[Taro] B2は橋を守れ -> {"orders":[{"bot":"B2","mode":"hold","place":"橋","player":"","stance":""}],"reply":"了解"}
[Hana] 俺についてきて -> {"orders":[{"bot":"*","mode":"follow","place":"","player":"Hana","stance":""}],"reply":"了解"}
[Taro] B1はHanaを守って、B2は西の塔 -> {"orders":[{"bot":"B1","mode":"follow","place":"","player":"Hana","stance":""},{"bot":"B2","mode":"hold","place":"西の塔","player":"","stance":""}],"reply":"了解"}
[Hana] 2番、全力で突撃 -> {"orders":[{"bot":"B2","mode":"front","place":"","player":"","stance":"push"}],"reply":"了解"}
[Taro] 無理せず橋で待機 -> {"orders":[{"bot":"*","mode":"hold","place":"橋","player":"","stance":"guard"}],"reply":"了解"}
[Hana] お腹すいた -> {"orders":[],"reply":""}
[Taro] 城壁を壊して -> {"orders":[],"reply":"それはできない"}
"""


def build_context(cfg, case):
    """OrderService.buildContext と同じ書き方。"""
    sb = [f"Our side: {cfg['side']}", "Gates opened: 0 of 1", f"Current front line: {cfg['front']}", "Bots:"]
    sb += [f"- {b} (now: front, push)" for b in cfg["bots"]]
    sb.append("Places:")
    for p in cfg["places"]:
        s = f"- {p['name']}"
        if p["aliases"]:
            s += f" (aliases: {', '.join(p['aliases'])})"
        sb.append(s)
    sb.append("Teammates online: " + " ".join(cfg["teammates"]))
    sb.append("Messages:")
    sb.append(f"[{case['speaker']}] {case['text']}")
    return "\n".join(sb) + "\n"


def schema(cfg):
    """ローカルで使うときの出力の形。名前・場所・モードは選択肢に縛る（llama.cpp の文法で同じことができる）。"""
    place_names = [""] + [p["name"] for p in cfg["places"]] + [a for p in cfg["places"] for a in p["aliases"]]
    return {
        "type": "object",
        "properties": {
            "orders": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "bot": {"type": "string", "enum": ["*"] + cfg["bots"]},
                        "mode": {"type": "string", "enum": ["front", "hold", "follow"]},
                        "place": {"type": "string", "enum": place_names},
                        "player": {"type": "string", "enum": [""] + cfg["teammates"]},
                        "stance": {"type": "string", "enum": ["", "push", "guard"]},
                    },
                    "required": ["bot", "mode", "place", "player", "stance"],
                },
            },
            "reply": {"type": "string"},
        },
        "required": ["orders", "reply"],
    }


def resolve(decision, cfg, speaker):
    """OrderResolver.resolve を写したもの。戻り値: bot -> (mode, place, player, stance), 警告の一覧。"""
    aliases = {}
    for p in cfg["places"]:
        aliases[p["name"].lower()] = p["name"]
    for p in cfg["places"]:
        for a in p["aliases"]:
            aliases.setdefault(a.lower(), p["name"])
    out, warns = {}, []
    for bo in decision.get("orders") or []:
        if not isinstance(bo, dict):
            continue
        name = (bo.get("bot") or "").strip()
        if name in ("*", "全員") or name.lower() == "all":
            targets = list(cfg["bots"])
        else:
            targets = [b for b in cfg["bots"] if b.lower() == name.lower()]
        if not targets:
            warns.append(f"bot:{name}")
            continue
        mode = (bo.get("mode") or "").strip().lower()
        st = (bo.get("stance") or "").strip().lower()
        st = st if st in ("push", "guard") else None
        if mode == "front":
            order = ("front", None, None, st or "push")
        elif mode == "hold":
            pl = aliases.get((bo.get("place") or "").strip().lower())
            if pl is None:
                warns.append(f"place:{bo.get('place')}")
                continue
            order = ("hold", pl, None, st or "guard")
        elif mode == "follow":
            want = (bo.get("player") or "").strip()
            if want == "" or want.lower() == "me":
                want = speaker or ""
            found = None
            for t in cfg["teammates"]:
                if t.lower() == want.lower():
                    found = t
            if found is None:
                warns.append(f"player:{bo.get('player')}")
                continue
            order = ("follow", None, found, st or "guard")
        else:
            warns.append(f"mode:{mode}")
            continue
        for t in targets:
            out[t] = order
    return out, warns


def matches(resolved, expect_alts, bots):
    for alt in expect_alts:
        exp = {}
        for k, v in alt.items():
            for b in (bots if k == "ALL" else [k]):
                exp[b] = v
        if set(exp) != set(resolved):
            continue
        ok = True
        for b, (m, pl, py, st) in exp.items():
            rm, rpl, rpy, rst = resolved[b]
            if (m, pl, py) != (rm, rpl, rpy) or (st != "*" and st != rst):
                ok = False
        if ok:
            return True
    return False


def post(path, payload, timeout=600):
    req = urllib.request.Request(OLLAMA + path, data=json.dumps(payload).encode("utf-8"),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def get(path):
    with urllib.request.urlopen(OLLAMA + path, timeout=30) as r:
        return json.loads(r.read().decode("utf-8"))


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("model")
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--only", default="")
    ap.add_argument("--prompt", choices=["plugin", "fewshot"], default="plugin")
    args = ap.parse_args()

    cfg = json.loads((HERE / "cases.json").read_text(encoding="utf-8"))
    cases = cfg["cases"]
    if args.only:
        keep = {int(x) for x in args.only.split(",")}
        cases = [c for c in cases if c["id"] in keep]
    sysp = system_prompt() if args.prompt == "plugin" else FEWSHOT_PROMPT
    fmt = schema(cfg)
    options = {"temperature": 0, "num_gpu": 0, "num_thread": args.threads, "num_ctx": 4096, "num_predict": 512}
    print(f"model={args.model} threads={args.threads} prompt={args.prompt} cases={len(cases)} "
          f"system_prompt_chars={len(sysp)}")

    # 読み込み（時間は数えない）と、GPU を使っていないことの確認
    post("/api/chat", {"model": args.model, "messages": [{"role": "user", "content": "hi"}], "stream": False,
                       "think": False, "options": {**options, "num_predict": 1}})
    ps = [m for m in get("/api/ps")["models"] if m["name"].startswith(args.model)]
    vram = ps[0].get("size_vram", -1) if ps else -1
    print(f"loaded: size={ps[0]['size'] / 1e9:.2f}GB size_vram={vram}" if ps else "loaded: (not in /api/ps)")
    if vram != 0:
        print("警告: GPU を使っている可能性があります（size_vram != 0）")

    rows = []
    out_path = HERE / (f"results_{args.model.replace(':', '_').replace('/', '_')}_t{args.threads}"
                       f"{'' if args.prompt == 'plugin' else '_' + args.prompt}.jsonl")
    with out_path.open("w", encoding="utf-8") as f:
        for c in cases:
            t0 = time.perf_counter()
            r = post("/api/chat", {
                "model": args.model, "stream": False, "think": False, "format": fmt, "options": options,
                "messages": [{"role": "system", "content": sysp},
                             {"role": "user", "content": build_context(cfg, c)}],
            })
            wall = time.perf_counter() - t0
            content = r["message"].get("content", "")
            thinking = r["message"].get("thinking", "")
            try:
                decision = json.loads(content)
                parse_ok = True
            except json.JSONDecodeError:
                decision, parse_ok = {}, False
            resolved, warns = resolve(decision, cfg, c["speaker"])
            ok = parse_ok and matches(resolved, c["expect"], cfg["bots"])
            row = {
                "id": c["id"], "text": c["text"], "ok": ok, "parse_ok": parse_ok, "wall_s": round(wall, 2),
                "prompt_tokens": r.get("prompt_eval_count"), "gen_tokens": r.get("eval_count"),
                "prefill_s": round(r.get("prompt_eval_duration", 0) / 1e9, 2),
                "gen_s": round(r.get("eval_duration", 0) / 1e9, 2),
                "thinking_chars": len(thinking),
                "resolved": {k: list(v) for k, v in resolved.items()}, "warnings": warns,
                "reply": decision.get("reply", "") if isinstance(decision, dict) else "", "raw": content,
            }
            rows.append(row)
            f.write(json.dumps(row, ensure_ascii=False) + "\n")
            mark = "OK " if ok else "NG "
            got = ", ".join(f"{k}={v[0]}{'/' + (v[1] or v[2]) if (v[1] or v[2]) else ''}/{v[3]}"
                            for k, v in sorted(resolved.items())) or "(変更なし)"
            print(f"{mark}#{c['id']:>2} {wall:5.1f}s  {c['text']}  ->  {got}"
                  + (f"  警告={warns}" if warns else "") + ("" if parse_ok else "  JSON不正"))

    walls = [r["wall_s"] for r in rows]
    n_ok = sum(r["ok"] for r in rows)
    pt = sum(r["prompt_tokens"] or 0 for r in rows)
    ps_ = sum(r["prefill_s"] for r in rows)
    gt = sum(r["gen_tokens"] or 0 for r in rows)
    gs = sum(r["gen_s"] for r in rows)
    print(f"\n正答 {n_ok}/{len(rows)}  JSON不正 {sum(not r['parse_ok'] for r in rows)}  "
          f"思考の出力あり {sum(r['thinking_chars'] > 0 for r in rows)}")
    print(f"時間 中央値 {statistics.median(walls):.1f}s  平均 {statistics.mean(walls):.1f}s  最大 {max(walls):.1f}s")
    print(f"読み込み {pt / max(len(rows), 1):.0f} トークン/件 {pt / ps_ if ps_ else 0:.0f} tok/s  "
          f"生成 {gt / max(len(rows), 1):.0f} トークン/件 {gt / gs if gs else 0:.1f} tok/s")
    print(f"結果: {out_path}")


if __name__ == "__main__":
    main()
