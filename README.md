# Who Am I?

Partyspiel für Android und iPhone: Erkenne prominente Personen, während ihr Gesicht Kachel für Kachel aufgedeckt wird.
Je früher du buzzerst und richtig liegst, desto mehr Punkte gibt es.

## Funktionen

- **Einzelspieler und Mehrspieler**, Auswahl auf dem Startbildschirm
- **Einstellbar:** Anzahl Kacheln, Abstand zwischen den Kacheln (mit Live-Vorschau), Personen pro Runde, Zeit zwischen dem Aufdecken (ms),
  Genauigkeit (nur Nachname / Vor- und Nachname), Punkte-Abstufung (Startwert, Abzug pro Kachel,
  Minimum), Antwortzeit und Strafe (50 % des aktuellen Punktewerts oder fester Abzug)
- **Buzzer:** Die Kachel-Uhr pausiert, der Spieler tippt den Namen oder sagt ihn (Spracherkennung).
  Bei falscher Antwort oder abgelaufener Zeit gibt es einen Punkteabzug, danach läuft die Runde weiter.
- **Kategorien:** Bekanntheit (Bekannt / Sehr bekannt / Extrem bekannt), Bereich (Film & Schauspiel, Musik, TV & Comedy, Sport, Politik & Gesellschaft,
  Wissenschaft & Wirtschaft, Kunst & Literatur, Mode & Models, Geschichte), Jahrzehnt (50er & früher
  bis 2020er) und Herkunft (Deutsche Promis / International). Innerhalb einer Gruppe gilt „oder“,
  zwischen den Gruppen „und“ (Musik + 80er = Musiker der 80er). Ohne Auswahl sind alle Personen dabei.
- **Passen:** Wer keine Idee hat, passt (und kann das wieder zurücknehmen). Haben alle ratenden
  Spieler gepasst, werden alle Kacheln aufgedeckt und die Person aufgelöst – ohne Punkte.
- **Punkteanzeige:** Zeigt die aktuell möglichen Punkte, jede Verringerung ist animiert.
  Die Rangliste ist immer nach Punkten sortiert.
- **Countdown** vor dem Start
- **Mehrspieler** ohne Server, direkt zwischen den Handys:
  - Android untereinander über Nearby Connections (Bluetooth/WLAN, auch ohne gemeinsames WLAN).
  - Mit iPhones über das lokale WLAN (oder den Hotspot eines Handys); gefunden wird das Spiel per Bonjour.
    Android-Hosts bieten ihr Spiel immer auf beiden Wegen an.
  - Lobby mit Namen und „Bereit“. Sind alle bereit, startet das Spiel automatisch.
  - Einladen: Spieler in der Nähe mit aktiviertem *„Für Einladungen erreichbar“* bekommen eine
    Benachrichtigung (nur Android; iOS erlaubt keine solche Suche im Hintergrund). Antippen öffnet die App und tritt direkt bei. Alternativ lässt sich ein
    Einladungslink (`whoami://join?code=…`) oder der Code teilen.
- **Battle-Modus:** Jeder wählt eigene Fotos (samt Namen) aus. Die Bilder kommen reihum dran; der
  Besitzer des aktiven Bildes kann nicht lösen und ist in der Rangliste markiert.
- **TV-Streaming:** Der Host startet in der Lobby eine TV-Ansicht. Ein Smart-TV im selben WLAN öffnet
  die angezeigte Adresse (z. B. `http://192.168.1.23:8080`) im Browser. Die Handys sind dann nur
  noch Buzzer und Eingabegerät.

## Synchronität im Mehrspieler

Damit niemand einen Vorteil hat, weil das Bild bei ihm früher aufgedeckt wird:

1. **Uhrensynchronisation:** Jedes Gerät misst regelmäßig per Ping/Pong den Versatz zur Host-Uhr
   (NTP-Prinzip, es zählen die Messungen mit der kürzesten Laufzeit).
2. **Zeitpunkte statt Befehle:** Der Host verteilt keinen Befehl „jetzt Kachel 5 aufdecken“, sondern
   Zeitstempel (Start der Kachel-Uhr, Pausen, Countdown-Ende) in Host-Zeit. Jedes Gerät berechnet
   daraus selbst, was gerade sichtbar ist. Netzwerklatenz verzögert also nur die Nachricht,
   nicht die Anzeige.
3. **Bilder vorab:** Alle Bilder werden vor dem Countdown auf alle Geräte übertragen. Der Countdown
   startet erst, wenn jedes Gerät alles hat.
4. **Faire Buzzer:** Jeder Buzzer-Druck trägt seinen Zeitpunkt in Host-Zeit. Treffen innerhalb von
   300 ms mehrere ein, gewinnt der früheste Druck, nicht die schnellste Verbindung.
   Die Punkte richten sich nach dem Moment des Drückens.

Die TV-Webseite macht dasselbe gegen die Uhr des Host-Handys.

## Promi-Bilder

Die Porträts kommen zur Laufzeit von **Wikimedia Commons** (freie Lizenzen): Über die Wikidata-ID einer
Person werden Fotos gesucht, die sie laut Commons zeigen. Eine Gesichtserkennung auf dem Gerät (ML Kit; das Modell lädt die App beim ersten Start über die Google-Play-Dienste nach, die APK muss dafür nicht im Play Store sein)
nimmt nur Einzelporträts und schneidet sie auf das Gesicht zu. Pro Person merkt sich die App, welche Fotos
schon gezeigt wurden, damit möglichst jedes Mal ein anderes kommt. Notfalls wird das Artikelbild der
deutschsprachigen Wikipedia verwendet. Urheber und Lizenz stehen bei der Auflösung unter dem Namen.
Deshalb braucht der Promi-Modus Internet auf dem Host-Gerät; die Mitspieler bekommen die Bilder vom Host.
**Personen:** 426 von Hand gepflegte Personen (`core/src/commonMain/kotlin/de/ballosv/whoami/core/Celebrities.kt`)
plus mehrere Tausend aus Wikidata erzeugte (`core/data/celebrities_wikidata.tsv`).
Die Liste erzeugt `tools/generate_celebrities.py` über den Workflow *Personenliste erzeugen*: Bereich aus dem Beruf,
Herkunft aus der Staatsangehörigkeit, Jahrzehnte näherungsweise aus den Lebensdaten. **Bekanntheit** = Seitenaufrufe
der deutschen Wikipedia (Vorauswahl über 60 Tage, dann Median der Monatsaufrufe der letzten 12 Monate, robust gegen Nachrichtenwellen);
die Anzahl der Sprachversionen gibt nur einen kleinen Bonus. Zusätzlich werden die meistaufgerufenen Artikel
der letzten 12 Monate als Kandidaten aufgenommen, damit auch hierzulande bekannte TV-Promis dabei sind. Daraus ergeben sich drei Bekanntheitsstufen,
einstellbar als *Extrem bekannt* (ca. 360 Personen), *Sehr bekannt* (ca. 1.040, Standard) und *Bekannt* (ca. 1.975); die unbekanntesten Personen der Wikidata-Liste sind nie im Spiel. Bekanntere Personen kommen häufiger dran.
Historische Personen werden leicht abgewertet, NS-Funktionäre ausgeschlossen. Den Workflow bei Bedarf
erneut starten (*Actions → Personenliste erzeugen → Run workflow*), um die Liste zu aktualisieren.
Die App merkt sich, wer zuletzt dran war: Zuerst kommen Personen, die noch nie gezeigt wurden, danach die,
deren letzter Auftritt am längsten zurückliegt. So wiederholt sich über viele Runden hinweg niemand.

## Projektaufbau

```
core/     Reines Kotlin-Multiplatform-Modul: Spielregeln (HostEngine), Punkte, Namensabgleich,
          Uhrensynchronisation, Netzwerkprotokoll, Personenliste (core/data) – mit Unit-Tests
shared/   Gemeinsamer App-Code für Android und iOS (Compose Multiplatform)
  commonMain/  Oberfläche (ui), Sitzungen (session), WLAN-Mehrspieler (net), TV-Server (tv),
               Promi-Bilder (content); Plattformschnittstellen in platform/Platform.kt
  androidMain/ Nearby Connections, ML-Kit-Gesichtserkennung, Spracheingabe, Fotoauswahl, NSD
  iosMain/     Vision-Gesichtserkennung, Spracherkennung, Foto-Picker, Bonjour, Einstieg MainViewController
  tv/          TV-Seite (index.html), wird beim Bauen eingebettet
app/      Android-App: Activity, Application, Hintergrunddienst für Einladungen
iosApp/   iPhone-App: SwiftUI-Hülle, Info.plist, Icon; Xcode-Projekt per XcodeGen (project.yml)
```

## Bauen

**Android** – Voraussetzungen: JDK 17 und das Android SDK (API 35).

```bash
./gradlew :core:jvmTest       # Spiellogik testen
./gradlew :app:assembleDebug  # APK: app/build/outputs/apk/debug/
```

**iPhone** – Voraussetzungen: Mac mit Xcode 16, JDK 17 und [XcodeGen](https://github.com/yonaskolb/XcodeGen).

```bash
cd iosApp && xcodegen generate && open WhoAmI.xcodeproj
```

GitHub Actions baut bei jedem Push und Pull Request beide Apps (die iPhone-App auf einem Mac-Runner,
inklusive Probestart im Simulator); APK und IPA hängen als Artefakte am Workflow-Lauf.

**Releases** entstehen automatisch: Jeder Merge nach `main` erzeugt ein GitHub-Release `v<versionName>`
(aus `app/build.gradle.kts`) mit APK und IPA zum direkten Download unter *Releases*. Für ein neues Release
vorher `versionName` und `versionCode` erhöhen (und in `iosApp/project.yml` `MARKETING_VERSION` /
`CURRENT_PROJECT_VERSION`) – ein bestehendes Release wird nicht überschrieben.

**iPhone-App installieren (ohne Apple-Entwicklerkonto):** Die IPA ist unsigniert. Mit
[Sideloadly](https://sideloadly.io) oder AltStore und der eigenen (kostenlosen) Apple-ID lässt sie sich
auf das iPhone bringen; das Tool signiert sie dabei. Mit einer kostenlosen Apple-ID läuft die App
7 Tage, danach erneut installieren. Mit einem Apple-Entwicklerkonto könnte der Workflow die App
stattdessen direkt nach TestFlight hochladen.

## Branches

- `main`: Releases
- `develop`: Integrationsstand
- Feature-Branches: einzelne Features, per Pull Request nach `develop`

## Grenzen / Ideen

- Mehrspieler funktioniert in der Nähe (gleicher Raum). Spielen über das Internet bräuchte einen Server
  (z. B. Firebase) für Lobby und Push-Benachrichtigungen.
- Einladungs-Benachrichtigungen erhalten nur Geräte, die „Für Einladungen erreichbar“ aktiviert haben.
- Die TV-Ansicht läuft im Browser des Smart-TVs. Eine native Google-Cast-App wäre eine Alternative,
  braucht aber eine registrierte Receiver-App.
