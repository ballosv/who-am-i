# Who Am I?

Android-Partyspiel: Erkenne prominente Personen, während ihr Gesicht Kachel für Kachel aufgedeckt wird.
Je früher du buzzerst und richtig liegst, desto mehr Punkte gibt es.

## Funktionen

- **Einzelspieler und Mehrspieler**, Auswahl auf dem Startbildschirm
- **Einstellbar:** Anzahl Kacheln, Abstand zwischen den Kacheln (mit Live-Vorschau), Personen pro Runde, Zeit zwischen dem Aufdecken (ms),
  Genauigkeit (nur Nachname / Vor- und Nachname), Punkte-Abstufung (Startwert, Abzug pro Kachel,
  Minimum), Antwortzeit und Strafe (50 % des aktuellen Punktewerts oder fester Abzug)
- **Buzzer:** Die Kachel-Uhr pausiert, der Spieler tippt den Namen oder sagt ihn (Spracherkennung).
  Bei falscher Antwort oder abgelaufener Zeit gibt es einen Punkteabzug, danach läuft die Runde weiter.
- **Kategorien:** Bereich (Film & Schauspiel, Musik, TV & Comedy, Sport, Politik & Gesellschaft,
  Wissenschaft & Wirtschaft, Kunst & Literatur, Mode & Models, Geschichte), Jahrzehnt (50er & früher
  bis 2020er) und Herkunft (Deutsche Promis / International). Innerhalb einer Gruppe gilt „oder“,
  zwischen den Gruppen „und“ (Musik + 80er = Musiker der 80er). Ohne Auswahl sind alle Personen dabei.
- **Passen:** Wer keine Idee hat, passt (und kann das wieder zurücknehmen). Haben alle ratenden
  Spieler gepasst, werden alle Kacheln aufgedeckt und die Person aufgelöst – ohne Punkte.
- **Punkteanzeige:** Zeigt die aktuell möglichen Punkte, jede Verringerung ist animiert.
  Die Rangliste ist immer nach Punkten sortiert.
- **Countdown** vor dem Start
- **Mehrspieler** über Nearby Connections (Bluetooth/WLAN direkt zwischen den Handys, kein Server):
  - Lobby mit Namen und „Bereit“. Sind alle bereit, startet das Spiel automatisch.
  - Einladen: Spieler in der Nähe mit aktiviertem *„Für Einladungen erreichbar“* bekommen eine
    Benachrichtigung. Antippen öffnet die App und tritt direkt bei. Alternativ lässt sich ein
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

Die Porträts kommen zur Laufzeit aus der deutschsprachigen Wikipedia (Artikelbild, freie Lizenzen).
Deshalb braucht der Promi-Modus Internet auf dem Host-Gerät; die Mitspieler bekommen die Bilder vom Host.
Die Personenliste (426 Personen mit Kategorien) steht in `core/src/main/kotlin/de/ballosv/whoami/core/Celebrities.kt`.
Die App merkt sich, wer zuletzt dran war: Zuerst kommen Personen, die noch nie gezeigt wurden, danach die,
deren letzter Auftritt am längsten zurückliegt. So wiederholt sich über viele Runden hinweg niemand.

## Projektaufbau

```
core/   Reines Kotlin-Modul ohne Android: Spielregeln (HostEngine), Punkte, Namensabgleich,
        Uhrensynchronisation, Netzwerkprotokoll – mit Unit-Tests
app/    Android-App (Jetpack Compose)
  session/   HostSession (führt die Engine aus) und GuestSession (Mitspieler)
  net/       Nearby Connections
  tv/        HTTP-Server für die TV-Ansicht (Seite: app/src/main/assets/tv/index.html)
  invite/    Hintergrunddienst für Einladungs-Benachrichtigungen
  content/   Promi-Liste, Wikipedia-Lader, Bildaufbereitung
  ui/        Bildschirme
```

## Bauen

Voraussetzungen: JDK 17 und das Android SDK (API 35).

```bash
./gradlew :core:test          # Spiellogik testen
./gradlew :app:assembleDebug  # APK: app/build/outputs/apk/debug/
```

GitHub Actions baut bei jedem Push und Pull Request; das Debug-APK hängt als Artefakt am Workflow-Lauf.

**Releases** entstehen automatisch: Jeder Merge nach `main` erzeugt ein GitHub-Release `v<versionName>`
(aus `app/build.gradle.kts`) mit der APK zum direkten Download unter *Releases*. Für ein neues Release
vorher `versionName` und `versionCode` erhöhen – ein bestehendes Release wird nicht überschrieben.

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
