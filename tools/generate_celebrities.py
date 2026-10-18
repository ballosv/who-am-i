#!/usr/bin/env python3
"""
Erzeugt aus Wikidata eine Liste bekannter Personen samt Kategorien für die App
(core/src/main/resources/de/ballosv/whoami/core/celebrities_wikidata.tsv).

- Bekanntheit = Anzahl der Wikipedia-Sprachversionen (Sitelinks); deutschsprachige Personen
  bekommen einen Bonus, weil sie international weniger Artikel haben, hier aber bekannt sind.
- Bereich aus dem Beruf (P106), Herkunft aus der Staatsangehörigkeit (P27),
  Jahrzehnte näherungsweise aus Geburts-/Sterbejahr, „Geschichte“ für historische Personen.
- Nur Personen mit Bild (P18) und Artikel in der deutschen Wikipedia.

Läuft in GitHub Actions (Workflow „Personenliste erzeugen“), weil query.wikidata.org dort erreichbar ist.
"""
import collections
import csv
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

UA = "WhoAmI-Generator/0.2 (https://github.com/ballosv/who-am-i)"
ENDPOINT = "https://query.wikidata.org/sparql"
OUT = "core/src/main/resources/de/ballosv/whoami/core/celebrities_wikidata.tsv"
TARGET = int(os.environ.get("TARGET", "4500"))
MIN_LINKS = int(os.environ.get("MIN_LINKS", "20"))
GERMAN_BONUS = float(os.environ.get("GERMAN_BONUS", "2.0"))
CURRENT_YEAR = 2026

# Beruf (Wikidata) -> Bereichskürzel der App
OCCUPATIONS = {
    # Film & Schauspiel
    "Q33999": "A", "Q10800557": "A", "Q10798782": "A", "Q2526255": "A",
    # Musik
    "Q177220": "M", "Q639669": "M", "Q488205": "M", "Q2252262": "M", "Q36834": "M", "Q753110": "M", "Q130857": "M",
    # TV & Comedy
    "Q947873": "T", "Q245068": "T", "Q17125263": "T",
    # Sport
    "Q937857": "S", "Q628099": "S", "Q10833314": "S", "Q3665646": "S", "Q10841764": "S", "Q378622": "S",
    "Q11338576": "S", "Q11513337": "S", "Q10843402": "S", "Q4144610": "S", "Q16029547": "S", "Q2309784": "S",
    "Q11774891": "S", "Q11303721": "S", "Q19204627": "S", "Q10871364": "S", "Q12840545": "S",
    # Politik & Gesellschaft
    "Q82955": "P", "Q116": "P", "Q372436": "P", "Q15253558": "P",
    # Wissenschaft & Wirtschaft
    "Q901": "W", "Q169470": "W", "Q593644": "W", "Q131524": "W", "Q43845": "W", "Q11631": "W",
    "Q82594": "W", "Q170790": "W", "Q11063": "W", "Q4964182": "W",
    # Kunst & Literatur
    "Q36180": "K", "Q6625963": "K", "Q49757": "K", "Q1028181": "K", "Q1281618": "K", "Q33231": "K", "Q42973": "K",
    # Mode & Models
    "Q4610556": "F", "Q3501317": "F",
}

# Deutschland (inkl. historischer Staaten), Österreich, Schweiz
GERMAN_STATES = {"Q183", "Q713750", "Q16957", "Q7318", "Q43287", "Q41304", "Q27306", "Q40", "Q39", "Q12548", "Q131964"}

# Schritt 1: pro Beruf nur Person + Bekanntheit (schnell)
CANDIDATES = """
SELECT ?p ?links WHERE {
  ?p wdt:P106 wd:%(occ)s ; wikibase:sitelinks ?links .
  FILTER(?links >= %(min)d)
  ?article schema:about ?p ; schema:isPartOf <https://de.wikipedia.org/> .
}
"""

# Schritt 2: Details für eine Gruppe von Personen
DETAILS = """
SELECT ?p ?title ?label ?birth ?death ?img ?c ?family WHERE {
  VALUES ?p { %(values)s }
  ?p wdt:P31 wd:Q5 .
  ?article schema:about ?p ; schema:isPartOf <https://de.wikipedia.org/> ; schema:name ?title .
  OPTIONAL { ?p rdfs:label ?label . FILTER(LANG(?label) = "de") }
  OPTIONAL { ?p wdt:P18 ?img }
  OPTIONAL { ?p wdt:P569 ?birth }
  OPTIONAL { ?p wdt:P570 ?death }
  OPTIONAL { ?p wdt:P27 ?c }
  OPTIONAL { ?p wdt:P734 ?fam . ?fam rdfs:label ?family . FILTER(LANG(?family) IN ("de", "mul", "en")) }
}
"""


def log(msg):
    print(msg, file=sys.stderr, flush=True)


def sparql(query):
    data = urllib.parse.urlencode({"query": query, "format": "json"}).encode()
    for attempt in range(5):
        try:
            req = urllib.request.Request(ENDPOINT, data=data, headers={"User-Agent": UA, "Accept": "application/sparql-results+json"})
            with urllib.request.urlopen(req, timeout=75) as r:
                return json.load(r)["results"]["bindings"]
        except Exception as e:  # noqa: BLE001
            log(f"  Fehler ({attempt + 1}/5): {e}")
            time.sleep(5 * (attempt + 1))
    raise RuntimeError("SPARQL-Abfrage fehlgeschlagen")


def occupation_labels():
    values = " ".join("wd:" + q for q in OCCUPATIONS)
    rows = sparql(f'SELECT ?o ?l WHERE {{ VALUES ?o {{ {values} }} ?o rdfs:label ?l FILTER(LANG(?l)="de") }}')
    return {r["o"]["value"].rsplit("/", 1)[1]: r["l"]["value"] for r in rows}


def year(value):
    m = re.match(r"^(-?\d{1,4})-", value or "")
    return int(m.group(1)) if m else None


PARTICLES = {"von", "van", "der", "den", "de", "da", "di", "du", "del", "la", "le", "zu", "zum", "ten", "ter"}
ROMAN = re.compile(r"^(?:[IVX]+\.?|Jr\.?|Sr\.?)$")


def split_name(label, family):
    clean = re.sub(r"\s*\(.*?\)\s*", " ", label).strip()
    tokens = [t for t in clean.split() if not ROMAN.match(t)]
    if not tokens:
        return "", clean
    name = " ".join(tokens)
    if family and family in name and family != name:
        idx = name.rfind(family)
        first, last = name[:idx].strip(), name[idx:].strip()
    elif len(tokens) == 1:
        return "", tokens[0]
    else:
        first, last = " ".join(tokens[:-1]), tokens[-1]
    # Namenszusätze („von der“, „van“, „de“) gehören zum Nachnamen.
    parts = first.split()
    while parts and parts[-1].lower() in PARTICLES:
        last = parts.pop() + " " + last
    return " ".join(parts), last


def decades(birth, death):
    if birth is None:
        return []
    start = birth + 20
    end = min(death if death is not None else CURRENT_YEAR, birth + 75, CURRENT_YEAR)
    result = []
    if start < 1960:
        result.append("50")
    for d in range(1960, 2030, 10):
        if start <= d + 9 and end >= d:
            result.append(f"{d % 100:02d}")
    return result


def main():
    labels = occupation_labels()
    report = ["## Berufe (Kontrolle der Wikidata-IDs)", ""]
    for q, code in OCCUPATIONS.items():
        report.append(f"- {q} → {code}: {labels.get(q, '??? unbekannt')}")

    # Schritt 1: Kandidaten mit Bekanntheit und Bereichen sammeln
    links = {}
    areas = collections.defaultdict(set)
    for i, (occ, code) in enumerate(OCCUPATIONS.items(), 1):
        try:
            rows = sparql(CANDIDATES % {"occ": occ, "min": MIN_LINKS})
        except RuntimeError:
            log(f"[{i}/{len(OCCUPATIONS)}] {occ}: übersprungen (Zeitüberschreitung)")
            report.append(f"- WARNUNG: Beruf {occ} übersprungen (Zeitüberschreitung)")
            continue
        for r in rows:
            qid = r["p"]["value"].rsplit("/", 1)[1]
            links[qid] = int(r["links"]["value"])
            areas[qid].add(code)
        log(f"[{i}/{len(OCCUPATIONS)}] {occ} ({labels.get(occ, '?')}): {len(rows)} Kandidaten, gesamt {len(links)}")
        time.sleep(1)

    # Nur Kandidaten, die mit Bonus noch unter die besten TARGET kommen können
    ranked = sorted(links.values(), reverse=True)
    cutoff = ranked[min(len(ranked), TARGET) - 1] / GERMAN_BONUS if ranked else 0
    selected = [q for q, n in links.items() if n >= cutoff]
    log(f"Details für {len(selected)} von {len(links)} Kandidaten (Sitelinks >= {cutoff:.0f})")

    # Schritt 2: Details in Gruppen laden
    people = {}
    batch_size = 150
    for i in range(0, len(selected), batch_size):
        batch = selected[i:i + batch_size]
        try:
            rows = sparql(DETAILS % {"values": " ".join("wd:" + q for q in batch)})
        except RuntimeError:
            log(f"  Gruppe {i // batch_size + 1} übersprungen")
            continue
        for r in rows:
            qid = r["p"]["value"].rsplit("/", 1)[1]
            p = people.setdefault(qid, {
                "qid": qid, "title": r["title"]["value"], "label": None, "links": links[qid],
                "areas": areas[qid], "cits": set(), "birth": None, "death": None, "family": None, "img": False,
            })
            if "label" in r:
                p["label"] = r["label"]["value"]
            if "img" in r:
                p["img"] = True
            if "c" in r:
                p["cits"].add(r["c"]["value"].rsplit("/", 1)[1])
            b, d = year(r.get("birth", {}).get("value")), year(r.get("death", {}).get("value"))
            if b is not None and (p["birth"] is None or b < p["birth"]):
                p["birth"] = b
            if d is not None:
                p["death"] = d
            fam = r.get("family", {}).get("value")
            if fam and (p["family"] is None or len(fam) > len(p["family"])):
                p["family"] = fam
        log(f"  Details {min(i + batch_size, len(selected))}/{len(selected)}")
        time.sleep(0.5)

    rows = []
    for p in people.values():
        decs = decades(p["birth"], p["death"])
        if not decs or not p["img"] or not p["label"]:
            continue
        if p["family"] and p["family"] not in p["label"]:
            p["family"] = None
        german = bool(p["cits"] & GERMAN_STATES)
        areas = set(p["areas"])
        if (p["death"] is not None and p["death"] < 2000) or (p["birth"] is not None and p["birth"] < 1900):
            areas.add("H")
        first, last = split_name(p["label"], p["family"])
        if not last:
            continue
        score = p["links"] * (GERMAN_BONUS if german else 1.0)
        rows.append({
            "qid": p["qid"], "title": p["title"], "first": first, "last": last,
            "areas": " ".join(sorted(areas)), "decades": " ".join(decs),
            "origin": "D" if german else "I", "links": p["links"], "score": score,
        })
    rows.sort(key=lambda r: -r["score"])
    rows = rows[:TARGET]

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, delimiter="\t", lineterminator="\n")
        w.writerow(["qid", "title", "first", "last", "areas", "decades", "origin", "links"])
        for r in rows:
            w.writerow([r["qid"], r["title"], r["first"], r["last"], r["areas"], r["decades"], r["origin"], r["links"]])

    area_count = collections.Counter(a for r in rows for a in r["areas"].split())
    dec_count = collections.Counter(d for r in rows for d in r["decades"].split())
    origin_count = collections.Counter(r["origin"] for r in rows)
    report += ["", f"## Ergebnis: {len(rows)} Personen (von {len(people)} Kandidaten)", "",
               f"- Bereiche: {dict(sorted(area_count.items()))}",
               f"- Jahrzehnte: {dict(sorted(dec_count.items()))}",
               f"- Herkunft: {dict(origin_count)}",
               f"- Mindest-Sitelinks im Ergebnis: {min((r['links'] for r in rows), default=0)}",
               "", "### Beispiele (Plätze 1–15, 1000, 2000, 3000, letzte)", ""]
    for i in list(range(15)) + [999, 1999, 2999, len(rows) - 1]:
        if 0 <= i < len(rows):
            r = rows[i]
            report.append(f"- {i + 1}. {r['first']} | {r['last']} ({r['title']}) {r['areas']} / {r['decades']} / {r['origin']} / {r['links']}")
    text = "\n".join(report)
    print(text)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write(text + "\n")


if __name__ == "__main__":
    main()
