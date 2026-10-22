#!/usr/bin/env python3
"""
Prüft die Personenliste gegen Wikipedia, Wikidata und Wikimedia Commons – mit denselben
Abfragen wie die App. Ergebnis: welche Artikel fehlen bzw. kein Bild haben und wie viele
Commons-Fotos pro Person gefunden werden. Läuft in GitHub Actions (Workflow „Wikimedia-Check“).
"""
import json
import os
import random
import re
import sys
import time
import urllib.parse
import urllib.request

UA = "WhoAmI-Check/0.2 (https://github.com/ballosv/who-am-i)"
WIKI = "https://de.wikipedia.org/w/api.php"
COMMONS = "https://commons.wikimedia.org/w/api.php"
EXCLUDE = re.compile(
    r"signatur|signature|autograph|unterschrift|logo|wappen|coat of arms|grave|grab|tomb|statue|denkmal|"
    r"memorial|plaque|gedenktafel|stamp|briefmarke|map|karte|poster|cover|wax|madame tussauds|mural|graffiti",
    re.I,
)


def get(url, params):
    full = url + "?" + urllib.parse.urlencode(params)
    for attempt in range(4):
        try:
            req = urllib.request.Request(full, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.load(r)
        except Exception as e:  # noqa: BLE001
            print(f"  Fehler ({attempt + 1}/4): {e}", file=sys.stderr)
            time.sleep(2 ** attempt)
    raise RuntimeError("Abfrage fehlgeschlagen: " + full)


def curated_titles(path):
    src = open(path, encoding="utf-8").read()
    titles = []
    for m in re.finditer(r'p\("([^"]*)", "([^"]*)", "[^"]*", "[^"]*", (?:DE|INT)(?:, "([^"]*)")?\)', src):
        first, last, title = m.groups()
        titles.append(title or f"{first} {last}")
    return titles


def articles(titles):
    result = {}
    for i in range(0, len(titles), 50):
        batch = titles[i:i + 50]
        q = get(WIKI, {
            "action": "query", "format": "json", "formatversion": 2, "redirects": 1,
            "prop": "pageimages|pageprops", "ppprop": "wikibase_item|disambiguation", "piprop": "thumbnail|name",
            "pithumbsize": 960, "titles": "|".join(batch),
        })["query"]
        mapping = {x["from"]: x["to"] for x in q.get("normalized", []) + q.get("redirects", [])}
        pages = {p["title"]: p for p in q.get("pages", [])}
        for t in batch:
            r = t
            for _ in range(3):
                r = mapping.get(r, r)
            result[t] = pages.get(r)
    return result


def commons_photos(qid):
    q = get(COMMONS, {
        "action": "query", "format": "json", "formatversion": 2, "generator": "search", "gsrnamespace": 6,
        "gsrlimit": 40, "gsrsearch": f"haswbstatement:P180={qid} filetype:bitmap",
        "prop": "imageinfo", "iiprop": "url|size|mime", "iiurlwidth": 960,
    })
    pages = q.get("query", {}).get("pages", [])
    usable = []
    for p in pages:
        info = (p.get("imageinfo") or [{}])[0]
        if EXCLUDE.search(p["title"]) or info.get("mime") not in ("image/jpeg", "image/png"):
            continue
        if min(info.get("width", 0), info.get("height", 0)) < 400:
            continue
        usable.append(p["title"])
    return len(pages), usable


def main():
    out = []
    titles = curated_titles("core/src/commonMain/kotlin/de/ballosv/whoami/core/Celebrities.kt")
    out.append(f"## Personenliste: {len(titles)} Einträge\n")
    arts = articles(titles)
    missing = [t for t, p in arts.items() if p is None or p.get("missing")]
    no_qid = [t for t, p in arts.items() if p and not p.get("missing") and not p.get("pageprops", {}).get("wikibase_item")]
    disambig = [t for t, p in arts.items() if p and "disambiguation" in p.get("pageprops", {})]
    no_image = [t for t, p in arts.items() if p and not p.get("missing") and "thumbnail" not in p]
    out.append(f"- Artikel nicht gefunden: {len(missing)} {missing}")
    out.append(f"- Ohne Wikidata-ID: {len(no_qid)} {no_qid}")
    out.append(f"- Begriffsklärungsseiten (falsche Person!): {len(disambig)} {disambig}")
    out.append(f"- Ohne Artikelbild: {len(no_image)} {no_image}\n")

    sample_size = int(os.environ.get("SAMPLE", "25"))
    with_qid = [(t, p["pageprops"]["wikibase_item"]) for t, p in arts.items()
                if p and p.get("pageprops", {}).get("wikibase_item") and t not in disambig]
    random.seed(1)
    sample = random.sample(with_qid, min(sample_size, len(with_qid)))
    out.append(f"## Commons-Fotos (Stichprobe {len(sample)})\n")
    out.append("| Person | QID | Treffer | nutzbar |")
    out.append("|---|---|---|---|")
    total = 0
    for t, qid in sample:
        hits, usable = commons_photos(qid)
        total += len(usable)
        out.append(f"| {t} | {qid} | {hits} | {len(usable)} |")
    out.append(f"\nDurchschnittlich nutzbare Fotos pro Person: {total / max(1, len(sample)):.1f}")

    report = "\n".join(out)
    print(report)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write(report + "\n")


if __name__ == "__main__":
    main()
