#!/usr/bin/env python3
"""Diagnose: dieselben Abrufe wie CelebrityImages in der App, für zufällige Personen der Liste."""
import csv, json, random, urllib.parse, urllib.request, urllib.error

UA = "WhoAmI-Android/0.2 (https://github.com/ballosv/who-am-i)"
TSV = "core/src/main/resources/de/ballosv/whoami/core/celebrities_wikidata.tsv"


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()[:300]


rows = list(csv.DictReader(open(TSV, encoding="utf-8"), delimiter="\t"))
print("Zeilen:", len(rows), "Spalten:", list(rows[0].keys()))
random.seed(1)
sample = random.sample(rows, 16)
titles = [r["title"] for r in sample]
q = ("action=query&format=json&formatversion=2&redirects=1&prop=pageimages|pageprops&ppprop=wikibase_item"
     "&piprop=thumbnail|name&pithumbsize=960&titles=" + urllib.parse.quote_plus("|".join(titles)))
status, body = get("https://de.wikipedia.org/w/api.php?" + q)
print("articles:", status, len(body))
print(body[:1500].decode("utf-8", "replace"))
for r in sample[:6]:
    qid = r["qid"]
    q = ("action=query&format=json&formatversion=2&generator=search&gsrnamespace=6&gsrlimit=40&gsrsearch="
         + urllib.parse.quote_plus(f"haswbstatement:P180={qid} filetype:bitmap")
         + "&prop=imageinfo&iiprop=url|size|mime|extmetadata&iiurlwidth=960&iiextmetadatafilter=Artist|LicenseShortName")
    status, body = get("https://commons.wikimedia.org/w/api.php?" + q)
    pages = json.loads(body).get("query", {}).get("pages", []) if status == 200 else []
    print(r["title"], qid, "commons:", status, len(pages))
    for p in pages[:2]:
        info = (p.get("imageinfo") or [{}])[0]
        url = info.get("thumburl") or info.get("url")
        s, b = get(url)
        print("   ", s, len(b), url)
