#!/usr/bin/env python3
"""
Erzeugt aus Wikidata eine Liste bekannter Personen samt Kategorien für die App
(core/src/main/resources/de/ballosv/whoami/core/celebrities_wikidata.tsv).

Bekanntheit = wie oft der Artikel in der deutschen Wikipedia aufgerufen wird (Median der
Monatsaufrufe der letzten 12 Monate – robust gegen kurze Ausschläge durch Skandale oder
Todesfälle). Die Zahl der Sprachversionen gibt nur einen kleinen Bonus, historische
Personen werden leicht abgewertet (deren Gesichter kennt man seltener).

Kandidaten: Personen mit passendem Beruf und mindestens MIN_LINKS Sprachversionen, plus alle
Personen unter den meistaufgerufenen Artikeln der deutschen Wikipedia der letzten 12 Monate
(erfasst auch hierzulande bekannte TV-/Reality-Promis mit wenigen Sprachversionen).

Bereich aus dem Beruf (P106), Herkunft aus der Staatsangehörigkeit (P27), Jahrzehnte
näherungsweise aus Geburts-/Sterbejahr. Nur Personen mit Bild (P18) und deutschem Artikel.

Läuft in GitHub Actions (Workflow „Personenliste erzeugen“), weil Wikidata/Wikimedia dort erreichbar sind.
"""
import collections
import csv
import datetime
import json
import math
import os
import re
import statistics
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

UA = "WhoAmI-Generator/0.3 (https://github.com/ballosv/who-am-i)"
ENDPOINT = "https://query.wikidata.org/sparql"
WIKI_API = "https://de.wikipedia.org/w/api.php"
PAGEVIEWS = "https://wikimedia.org/api/rest_v1/metrics/pageviews"
OUT = "core/src/main/resources/de/ballosv/whoami/core/celebrities_wikidata.tsv"
TARGET = int(os.environ.get("TARGET", "4500"))
MIN_LINKS = int(os.environ.get("MIN_LINKS", "15"))
MAX_CANDIDATES = int(os.environ.get("MAX_CANDIDATES", "30000"))
SPARQL_PARALLEL = 4  # Wikidata erlaubt bis zu 5 gleichzeitige Abfragen
VIEWS_PARALLEL = 10
CURRENT_YEAR = datetime.date.today().year

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

# Deutschland (inkl. historischer deutscher Staaten), Österreich, Schweiz
GERMAN_STATES = {"Q183", "Q713750", "Q16957", "Q7318", "Q43287", "Q41304", "Q27306", "Q40", "Q39"}

# NSDAP – deren Politiker/Funktionäre gehören nicht in ein Partyspiel
NSDAP = "Q7320"

# Schritt 1: pro Beruf nur Person + Sprachversionen (schnell)
CANDIDATES = """
SELECT ?p ?links WHERE {
  ?p wdt:P106 wd:%(occ)s ; wikibase:sitelinks ?links .
  FILTER(?links >= %(min)d)
  ?article schema:about ?p ; schema:isPartOf <https://de.wikipedia.org/> .
}
"""

# Schritt 3: Details für eine Gruppe von Personen, eine Zeile pro Person
DETAILS = """
SELECT ?p (SAMPLE(?t) AS ?title) (SAMPLE(?l) AS ?label) (MIN(?b) AS ?birth) (MAX(?d) AS ?death)
       (SAMPLE(?i) AS ?img) (SAMPLE(?n) AS ?nsdap) (SAMPLE(?sl) AS ?links)
       (GROUP_CONCAT(DISTINCT ?cq; separator=",") AS ?cits)
       (GROUP_CONCAT(DISTINCT ?oq; separator=",") AS ?occs)
WHERE {
  VALUES ?p { %(values)s }
  ?p wdt:P31 wd:Q5 ; wikibase:sitelinks ?sl .
  ?article schema:about ?p ; schema:isPartOf <https://de.wikipedia.org/> ; schema:name ?t .
  OPTIONAL { ?p rdfs:label ?l . FILTER(LANG(?l) = "de") }
  OPTIONAL { ?p wdt:P18 ?i }
  OPTIONAL { ?p wdt:P569 ?b }
  OPTIONAL { ?p wdt:P570 ?d }
  OPTIONAL { ?p wdt:P27 ?c . BIND(STRAFTER(STR(?c), "entity/") AS ?cq) }
  OPTIONAL { ?p wdt:P106 ?o . BIND(STRAFTER(STR(?o), "entity/") AS ?oq) }
  OPTIONAL { ?p wdt:P102 wd:Q7320 . BIND("1" AS ?n) }
}
GROUP BY ?p
"""


def log(msg):
    print(msg, file=sys.stderr, flush=True)


def http_json(url, data=None, accept="application/json", retries=5):
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, data=data, headers={"User-Agent": UA, "Accept": accept})
            with urllib.request.urlopen(req, timeout=75) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            log(f"  HTTP {e.code} ({attempt + 1}/{retries}): {url[:120]}")
            time.sleep(5 * (attempt + 1) if e.code == 429 else 2 * (attempt + 1))
        except Exception as e:  # noqa: BLE001
            log(f"  Fehler ({attempt + 1}/{retries}): {e}")
            time.sleep(3 * (attempt + 1))
    raise RuntimeError("Abfrage fehlgeschlagen: " + url[:200])


def sparql(query):
    data = urllib.parse.urlencode({"query": query, "format": "json"}).encode()
    return http_json(ENDPOINT, data=data, accept="application/sparql-results+json")["results"]["bindings"]


def occupation_labels():
    values = " ".join("wd:" + q for q in OCCUPATIONS)
    rows = sparql(f'SELECT ?o ?l WHERE {{ VALUES ?o {{ {values} }} ?o rdfs:label ?l FILTER(LANG(?l)="de") }}')
    return {r["o"]["value"].rsplit("/", 1)[1]: r["l"]["value"] for r in rows}


def last_months(n=12):
    """Die letzten n abgeschlossenen Monate als (Jahr, Monat), ältester zuerst."""
    today = datetime.date.today().replace(day=1)
    months = []
    y, m = today.year, today.month
    for _ in range(n):
        m -= 1
        if m == 0:
            y, m = y - 1, 12
        months.append((y, m))
    return list(reversed(months))


def top_articles(months):
    """Meistaufgerufene Artikel der deutschen Wikipedia je Monat (je bis zu 1000)."""
    titles = set()
    for y, m in months:
        data = http_json(f"{PAGEVIEWS}/top/de.wikipedia/all-access/{y}/{m:02d}/all-days")
        for item in (data or {}).get("items", []):
            for a in item.get("articles", []):
                t = a["article"]
                if ":" not in t and t != "Wikipedia:Hauptseite":
                    titles.add(t.replace("_", " "))
    return sorted(titles)


def titles_to_qids(titles):
    qids = {}
    for i in range(0, len(titles), 50):
        batch = titles[i:i + 50]
        data = http_json(WIKI_API + "?" + urllib.parse.urlencode({
            "action": "query", "format": "json", "formatversion": 2, "redirects": 1,
            "prop": "pageprops", "ppprop": "wikibase_item", "titles": "|".join(batch),
        }))
        for page in (data or {}).get("query", {}).get("pages", []):
            q = page.get("pageprops", {}).get("wikibase_item")
            if q:
                qids[q] = page["title"]
    return qids


def median_views(title, months):
    (y0, m0), (y1, m1) = months[0], months[-1]
    article = urllib.parse.quote(title.replace(" ", "_"), safe="")
    url = f"{PAGEVIEWS}/per-article/de.wikipedia/all-access/user/{article}/monthly/{y0}{m0:02d}0100/{y1}{m1:02d}0100"
    data = http_json(url)
    views = [it["views"] for it in (data or {}).get("items", [])]
    views += [0] * (len(months) - len(views))  # Monate ohne Artikel zählen als 0
    return int(statistics.median(views)) if views else 0


def era_factor(birth):
    """Gesichter heutiger Promis kennt man eher als die historischer Persönlichkeiten."""
    if birth is None:
        return 0.5
    if birth >= 1940:
        return 1.0
    if birth >= 1900:
        return 0.8
    return 0.5


def score(views, links, birth):
    # Aufrufe sind der Hauptmaßstab; viele Sprachversionen (Weltstar) geben bis zu +50 %.
    return views * (1.0 + min(links, 150) / 300.0) * era_factor(birth)


def year(value):
    m = re.match(r"^(-?\d{1,4})-", value or "")
    return int(m.group(1)) if m else None


PARTICLES = {"von", "van", "der", "den", "de", "da", "di", "du", "del", "la", "le", "zu", "zum", "ten", "ter"}
PATRONYMIC = re.compile(r"^\w+(owitsch|ewitsch|jewitsch|itsch|owna|ewna|jewna|ichna)$")
ROMAN = re.compile(r"^(?:[IVX]+\.?|Jr\.?|Sr\.?)$")


def split_name(label, family):
    clean = re.sub(r"\s*\(.*?\)\s*", " ", label).strip()
    clean = re.sub(r"\s+der (Ältere|Jüngere)$", "", clean)
    tokens = [t for t in clean.split() if not ROMAN.match(t)]
    # Russische Vatersnamen („Wladimirowitsch“, „Jurjewna“) sagt im Spiel niemand mit
    if len(tokens) >= 3:
        tokens = [tokens[0]] + [t for t in tokens[1:-1] if not PATRONYMIC.match(t)] + [tokens[-1]]
    if not tokens:
        return "", clean
    name = " ".join(tokens)
    if family and family in name and family != name:
        idx = name.rfind(family)
        first, last = name[:idx].strip(), family
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
    months = last_months()
    report = [f"Zeitraum Seitenaufrufe: {months[0][0]}-{months[0][1]:02d} bis {months[-1][0]}-{months[-1][1]:02d}", "",
              "## Berufe (Kontrolle der Wikidata-IDs)", ""]
    for q, code in OCCUPATIONS.items():
        report.append(f"- {q} → {code}: {labels.get(q, '??? unbekannt')}")

    # Schritt 1: Kandidaten über die Berufe
    links = {}

    def candidates(occ):
        try:
            return occ, sparql(CANDIDATES % {"occ": occ, "min": MIN_LINKS})
        except RuntimeError:
            return occ, None

    with ThreadPoolExecutor(max_workers=SPARQL_PARALLEL) as pool:
        for i, (occ, rows) in enumerate(pool.map(candidates, OCCUPATIONS), 1):
            if rows is None:
                log(f"[{i}/{len(OCCUPATIONS)}] {occ}: übersprungen")
                report.append(f"- WARNUNG: Beruf {occ} übersprungen (Zeitüberschreitung)")
                continue
            for r in rows:
                links[r["p"]["value"].rsplit("/", 1)[1]] = int(r["links"]["value"])
            log(f"[{i}/{len(OCCUPATIONS)}] {occ} ({labels.get(occ, '?')}): {len(rows)}, gesamt {len(links)}")

    # Schritt 2: dazu die meistaufgerufenen Artikel der letzten 12 Monate
    top = top_articles(months)
    top_qids = titles_to_qids(top)
    log(f"Top-Artikel: {len(top)} Titel, {len(top_qids)} mit Wikidata-ID")
    candidates_all = set(links) | set(top_qids)
    if len(candidates_all) > MAX_CANDIDATES:
        keep = set(top_qids) | set(sorted(links, key=lambda q: -links[q])[:MAX_CANDIDATES - len(top_qids)])
        candidates_all = keep
    selected = sorted(candidates_all)
    log(f"Kandidaten gesamt: {len(selected)}")

    # Schritt 3: Details (nur Menschen mit deutschem Artikel kommen zurück)
    batches = [selected[i:i + 200] for i in range(0, len(selected), 200)]

    def details(batch):
        try:
            return sparql(DETAILS % {"values": " ".join("wd:" + q for q in batch)})
        except RuntimeError:
            return None

    people = {}
    with ThreadPoolExecutor(max_workers=SPARQL_PARALLEL) as pool:
        for n, rows in enumerate(pool.map(details, batches), 1):
            if rows is None:
                log("  Gruppe übersprungen")
                continue
            for r in rows:
                qid = r["p"]["value"].rsplit("/", 1)[1]
                occs = {o for o in r.get("occs", {}).get("value", "").split(",") if o}
                areas = {OCCUPATIONS[o] for o in occs if o in OCCUPATIONS}
                label = r.get("label", {}).get("value")
                if not areas or "img" not in r or not label or "nsdap" in r and "P" in areas:
                    continue
                people[qid] = {
                    "qid": qid, "title": r["title"]["value"], "label": label,
                    "links": int(r["links"]["value"]), "areas": areas,
                    "cits": {c for c in r.get("cits", {}).get("value", "").split(",") if c},
                    "birth": year(r.get("birth", {}).get("value")), "death": year(r.get("death", {}).get("value")),
                }
            if n % 20 == 0:
                log(f"  Details {n}/{len(batches)} Gruppen, {len(people)} Personen")
    log(f"Personen mit Beruf, Bild und Namen: {len(people)}")

    # Schritt 4: Seitenaufrufe der deutschen Wikipedia
    def views(p):
        try:
            return p["qid"], median_views(p["title"], months)
        except RuntimeError:
            return p["qid"], 0

    with ThreadPoolExecutor(max_workers=VIEWS_PARALLEL) as pool:
        for n, (qid, v) in enumerate(pool.map(views, list(people.values())), 1):
            people[qid]["views"] = v
            if n % 2000 == 0:
                log(f"  Seitenaufrufe {n}/{len(people)}")

    rows = []
    for p in people.values():
        decs = decades(p["birth"], p["death"])
        if not decs or p["views"] <= 0:
            continue
        german = bool(p["cits"] & GERMAN_STATES)
        areas = set(p["areas"])
        if (p["death"] is not None and p["death"] < 2000) or (p["birth"] is not None and p["birth"] < 1900):
            areas.add("H")
        first, last = split_name(p["label"], None)
        if not last:
            continue
        rows.append({
            "qid": p["qid"], "title": p["title"], "first": first, "last": last,
            "areas": " ".join(sorted(areas)), "decades": " ".join(decs), "origin": "D" if german else "I",
            "links": p["links"], "views": p["views"], "score": score(p["views"], p["links"], p["birth"]),
        })
    rows.sort(key=lambda r: -r["score"])
    rows = rows[:TARGET]

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, delimiter="\t", lineterminator="\n")
        w.writerow(["qid", "title", "first", "last", "areas", "decades", "origin", "links", "views"])
        for r in rows:
            w.writerow([r["qid"], r["title"], r["first"], r["last"], r["areas"], r["decades"], r["origin"], r["links"], r["views"]])

    area_count = collections.Counter(a for r in rows for a in r["areas"].split())
    dec_count = collections.Counter(d for r in rows for d in r["decades"].split())
    origin_count = collections.Counter(r["origin"] for r in rows)
    report += ["", f"## Ergebnis: {len(rows)} Personen (von {len(people)} mit Beruf und Bild)", "",
               f"- Bereiche: {dict(sorted(area_count.items()))}",
               f"- Jahrzehnte: {dict(sorted(dec_count.items()))}",
               f"- Herkunft: {dict(origin_count)}",
               f"- Mindest-Aufrufe/Monat im Ergebnis: {min((r['views'] for r in rows), default=0)}", ""]
    # Stufengrenzen wie in der App (CelebrityData): 8 % / 20 % / 40 %
    n = len(rows)
    for name, share in [("Extrem bekannt", 0.08), ("Sehr bekannt", 0.2), ("Bekannt", 0.4)]:
        k = int(n * share)
        edge = rows[max(0, k - 12):k]
        report.append(f"### Untergrenze „{name}“ (Platz {k})")
        report.append(", ".join(f"{r['first']} {r['last']} ({r['views']}/Monat)".strip() for r in edge))
        report.append("")
    report.append("### Top 40")
    report.append(", ".join(f"{r['first']} {r['last']}".strip() for r in rows[:40]))
    text = "\n".join(report)
    print(text)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write(text + "\n")


if __name__ == "__main__":
    main()
