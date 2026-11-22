# Phase 2 audit: Running AI Coach

Centrale referentie voor het verbetertraject na de phase-1 audit (`docs/audit/phase-1-report.md`).
Deze audit is read-only uitgevoerd: core-berekeningen zijn regel per regel gelezen, de runner-services,
de datapijplijn, security/multi-user, tests/build en de templates zijn in vier parallelle deel-audits
doorgelicht. Er is niets gemeten op de live app en er zijn geen API's aangeroepen.

**Uitgangspunt.** De app is bruikbaar en de UI is voorzichtig geformuleerd ("verband, geen oorzaak"), maar
een deel van de getallen is aantoonbaar fout (verkeerde populatie, impliciete nullen, tijdzone-menging) en
een deel van de oordelen rust op ongevalideerde drempels met dunne steekproeven. De phase-1 audit noemde
dit al; deze audit werkt het uit tot implementeerbare punten.

## Statusoverzicht

Prioriteit: **P0** = breekt vertrouwen of is een securityprobleem, **P1** = grote correctheidsfout,
**P2** = betrouwbaarheid/randgeval, **P3** = UX/onderhoud. Status verwijst naar de fase waarin het
opgelost wordt; fase 1 is in dit document afgerond.

| ID | Bevinding | Prioriteit | Status | Impact |
|---|---|---|---|---|
| A1 | Ontkoppelingsmediaan over gemengde populatie | P1 | **Opgelost (fase 1)** | Verkeerd getal op performance-pagina |
| A2 | 7-daagse laadsommen: 5/7 dagen telt als volle week, ontbrekende dagen = 0 | P1 | **Opgelost (fase 1)** | Weekbelasting, acute band, `load7Level`, raceweek-% te laag |
| A3 | `weeksToGo` door integerdeling: 6 dagen = 0 weken | P1 | **Opgelost (fase 1)** | Verkeerde haalbaarheidstekst en -vergelijking |
| A4 | `BestEffortService` cachet lege streamresultaten permanent | P1 | **Opgelost (fase 1)** | Best efforts blijven leeg na late stream-download |
| A5 | `load90` heet 90 maar gebruikt 26 weken | P2 | **Opgelost (fase 1)** | Misleidende naam, geen rekenfout |
| A6 | Max-HR-schatting: <5 runs → ruwe piek; 12 mnd leeg → hele historiek | P1 | Open | Zones, TRIMP en EF-strata schuiven |
| A7 | Twee verschillende HR-referenties binnen één laadpagina | P1 | Open | TRIMP en zoneverdeling uit andere max/rust-HR |
| A8 | `ZonedDateTime.now(clock)` (systeemzone) naast Europe/Brussels | P2 | Open | Dagvensters rond middernacht |
| A9 | Garmin `calendarDate` als UTC-datum, rest Brussels | P1 | Open | Slaap/HRV/body battery op verkeerde dag |
| A10 | TRIMP valt stil terug op gemiddelde HR | P2 | Open | Intervalbelasting onderschat, niet zichtbaar |
| A11 | Cachekeys: `maxHr` in key + onbegrensd; string-concatenatie in load | P2 | Open | Geheugengroei, zeldzame cachecollisie |
| A12 | `!!` na guard op een andere lijst | P2 | Deels opgelost (A1) | Crash bij lege/configuratie-randgevallen |
| A13 | EF-baseline over de hele historiek | P1 | Open | Oude en recente training tegen één basislijn |
| A14 | Meervoudige vergelijkingen zonder correctie (stratumkeuze) | P1 | Open | "Beste" trend deels geselecteerd op ruis |
| A15 | Dunne steekproeven met harde verdicts (n=3/5, 3 %-drempel) | P1 | Open | EF- en duurzaamheidsoordelen te stellig |
| A16 | Riegel eenzijdig gecapt, marathon op 0-1 anker | P1 | Open | Voorspelling optimistisch |
| A17 | Accuracy-band uit 5 races met 10 % trim | P2 | Open | Band lijkt preciezer dan hij is |
| A18 | Specificiteit zonder noemer (seconden i.p.v. aandeel) | P2 | Open | Lang/langzaam blok kan hoger scoren |
| A19 | Pacing-plan gebruikt midden van voorspelling als doeltijd | P3 | Open | Schatting oogt als doel |
| A20 | Spearman zonder betrouwbaarheidsinterval | P3 | Open | Coëfficiënt zonder onzekerheid |
| A21 | Base-rate telt dagen met partiële Garmin-data mee | P2 | Open | Frequentie lijkt vollediger dan data |
| A22 | Fitness-drempels hergebruiken EF-drempel en hardcoded 1,0 pp | P2 | Open | Oordeel zonder onderbouwing |
| A23 | Ongevalideerde drempels (LapClassifier, WorkoutStructure, duurzaamheid, load) | P1 | Open | Heuristiek als oordeel op het scherm |
| B1 | Lap-backfill herfetcht runs zonder laps voor altijd | P2 | Open | API-verspilling |
| B2 | `fetch-all` kan detailvelden permanent wissen | P1 | Open | Dataverlies |
| B3 | Sync-cursor kan oude pagina's overslaan na fout | P1 | Open | Ontbrekende historiek |
| B4 | Sync meldt succes bij falen (`lastSyncAt` altijd) | P2 | Open | Status liegt tegen de gebruiker |
| B5 | Verwijderde/oud-gewijzigde Strava-activiteiten nooit verzoend | P2 | Open | Spookactiviteiten in load en race |
| B6 | Token-refresh verliest `athleteId` | P2 | Open | Tokenrij zonder atleet |
| B7 | Stille datum-/pagina-corruptie in de Strava-adapter | P1 | Open | Foutieve of weggevallen data |
| B8 | Garmin sportmapping ≠ matcher (trail/treadmill) | P2 | Open | Garmin-trailrun matcht niets |
| B9 | Geen timeouts op de Strava-client | P1 | Open | Scheduler kan oneindig hangen |
| B10 | Geen retry/backoff, `Retry-After` genegeerd | P1 | Open | 429 → halve sync |
| B11 | Token-refresh zonder lock (5 duplicaten) | P2 | Open | Stale refresh token |
| B12 | Geen overlap-guard op Strava-sync | P2 | Open | Dubbele calls, budget op |
| B13 | OAuth zonder `state` | P2 | Open | Login-CSRF |
| B14 | Garmin-retry negeert `Retry-After` | P2 | Open | 429-storm |
| B15 | Garmin-rate-limit stopt de run niet | P2 | Open | Blijft hameren |
| B16 | Garmin `lastSuccessDate` bevriest bij één blijvende fout | P1 | Open | Groeiend incremental venster |
| B17 | N+1 queries op raw payloads | P2 | Open | Trage sync |
| B18 | Full-table loads en `findById` per samenvatting | P2 | Open | Trage sync |
| B19 | Ontbrekende indexen (`start_date`, `activity_laps.activity_id`, `start_time_gmt`) | P2 | Open | Trage queries |
| B20 | `saveLaps`/`deleteByActivityId` zonder transactie | P2 | Open | `TransactionRequiredException` mogelijk |
| B21 | Connectiepool van 2 voor web + jobs | P2 | Open | Web kan hongeren |
| B22 | Alle `@Scheduled` jobs op één thread | P2 | Open | Garmin blokkeert Strava en race-log |
| B23 | Stille exception-swallowing (`runCatching { }.getOrNull()`) | P2 | Open | Storing wordt "geen data" |
| B24 | O(n²)/onge cachet werk op requestpad | P2 | Open | Trage pagina's bij lange historiek |
| B25 | Ongebruikte config (`hardLookbackDays`, `minWeeksBefore`) | P3 | Open | Verwarring |
| B26 | Predictielog schrijft bij elke paginaweergave | P3 | Open | Overbodige writes |
| B27 | RunComparison geeft null voor de hele vergelijking bij één verwijderde activiteit | P3 | Open | Generieke lege staat |
| B28 | Gedupliceerde logica (NL-namen, vensters, daily load, max-HR) | P3 | Open | Drift tussen pagina's |
| B29 | UI-teksten die zekerheid overschatten | P3 | Open | Vertrouwen |
| C1 | Eén DB-credential (superuser) voor alle tenants | P0 | Open | Tenant-isolatie alleen op databasenaam |
| C2 | Strava-tokens plaintext in elke tenant-DB | P0 | Open | Elke dump bevat live tokens |
| C3 | Wachtwoordminimum 1 teken | P0 | Open | Iedereen met invite maakt account |
| C4 | Prod kan open starten bij lege `APP_PASSWORD` | P0 | Open | `/admin` bereikbaar |
| C5 | Remember-me-cookie zonder SameSite bij CSRF uit | P1 | Open | Cross-site POST na sessieverloop |
| C6 | Login-throttling alleen per (IP, gebruiker) | P1 | Open | Roterende aanvaller niet geremd |
| C7 | Back-ups onversleuteld naast de tenant-key | P1 | Open | Backup + `.env` = volledige compromise |
| C8 | `migrate.sh restore` destructief zonder bevestiging, kent `running_control` niet | P1 | Open | Onherstelbare fout |
| C9 | Dev-defaults: Postgres op 0.0.0.0 met `admin`, H2-console open | P1 | Open | Open database/console |
| C10 | Geen container-hardening (read_only, cap_drop, healthcheck) | P2 | Open | Grotere impact bij compromis |
| C11 | Secrets als env-variabelen (zichtbaar in `docker inspect`) | P2 | Open | Lek via Docker |
| C12 | Geen TLS naar Postgres | P3 | Open | Alleen intern netwerk |
| C13 | `ClientIp` herkent intern netwerk via string-prefix | P3 | Open | Verkeerde IP-classificatie |
| C14 | OAuth-URL's zonder URL-encoding | P3 | Open | Alleen bij afwijkende config |
| C15 | XSS: `th:utext` op profieltekst | P1 | Open | Ingevoerde HTML wordt uitgevoerd |
| D1 | `ktlint` op de classpath maar niet toegepast; geen detekt/JaCoCo/CI | P2 | Open | Geen borging |
| D2 | Spring BOM lekt in de Spring-vrije `core`-module | P2 | Open | Architectuurregel ondermijnd |
| D3 | Geen directe tests voor `race/Plan.kt`, `load/Statistics.kt`, `performance/Zones.kt`, `load/Model.kt` | P2 | Open | Rekenkern deels onbewaakt |
| D4 | Geen property-based tests | P3 | Open | Randgevallen onbekend |
| D5 | Klokafhankelijke/flaky tests (`Thread.sleep`, wall clock) | P2 | Open | Onbetrouwbare suite |
| D6 | Docs drift (`-Dspring.profiles.active=dev`, multi-user-status) | P3 | Open | Verkeerde instructies |
| D7 | Repo-clutter en dubbele Garmin-docsets | P3 | Open | Onoverzichtelijk |
| D8 | Grote templates/CSS, inline scripts, ongepagineerde race-historiek | P3 | Open | Onderhoudbaarheid |
| D9 | Tests vastleggen soms implementatie i.p.v. businessregel | P3 | Open | Wijzigingen vechten tegen tests |
| D10 | Geen test voor de UI-teksten op "zekerheid" buiten load/performance | P3 | Open | Claim kan terugsluipen |

## 1. Rekenkern en accuraatheid

### 1.1 Aantoonbare fouten

**A1 — Ontkoppelingsmediaan over een gemengde populatie (P1, opgelost in fase 1).**
`PerformanceService.kt:267-269` bouwt de samenvatting met de driftmediaan over `driftOk` (runs met
`driftAvailable`) maar de ontkoppelingsmediaan over `driftRows` (alle stabiele runs, inclusief runs waarvan
het tempo tussen de helften meer dan 5 % verschilde). Precies die runs zijn uitgesloten omdat ontkoppeling
dan door het tempo wordt verklaard. Zelfde patroon in de tiles: `PerformanceText.kt:397-403`
(`medDec` en "Runs onder 5 % ontkoppeling" over `driftRows`, terwijl de drift-tile `ok` gebruikt).
Impact: één cijfer op de performance-pagina is fout en de dekking in de tekst klopt niet.
Oplossing: één populatie (`driftAvailable`) voor drift én ontkoppeling, null-veilig, en dezelfde populatie
in de tiles.

**A2 — 7-daagse laadsommen met impliciete nullen (P1, opgelost in fase 1).**
`Rolling.window` (`core/.../load/Load.kt:238`) accepteert 70 % bekende dagen en telt ontbrekende dagen als
0. Voor een 28-daagse chronic som is dat verdedigbaar; voor een 7-daagse weekbelasting niet: 5 van 7 dagen
geeft een te lage week. Gebruikt door:
`LoadService.kt:333` (`trailing7`), `:348` (`acute`), `:353` (`last7`), `:360` (acute-lijn in de grafiek),
en `race/Readiness.kt:103-110` (`RaceWeek.loadPct`, de raceweek-%).
Impact: weekbelasting, `acuteBand`, readiness-`load7Level`, `load90`-percentiel en raceweek-% zijn te laag
zodra een dag onbekend is (een dag met alleen een activiteit zonder hartslag, of het begin van de historiek).
Oplossing: een complete venstersom (alle dagen bekend, anders `null`) voor elke 7-daagse weekbelasting.
Zelfde patroon, buiten fase 1: `race/History.kt:17` (`Taper.load7Pct`).

**A3 — `weeksToGo` door integerdeling (P1, opgelost in fase 1).**
`RaceService.kt:229`: `ChronoUnit.DAYS.between(today, g.date) / 7`. Zes dagen voor de race wordt 0 weken,
dertien dagen wordt 1 week. Dat getal gaat naar `GoalFeasibility.evaluate` (`race/Plan.kt:130-162`), waar het
de lengte van de historiekvergelijking bepaalt (`weeklySeries[i]` tegen `[i + weeks]`) en in de tekst
terechtkomt ("in N weken", "periodes van N weken").
Impact: haalbaarheid wordt op de verkeerde horizon beoordeeld en de tekst noemt de verkeerde termijn.
Oplossing: resterende tijd in dagen doorgeven, de historiekvergelijking op hele weken (minstens 1) baseren en
de resterende tijd in dagen tonen.

**A4 — Negatieve streamresultaten permanent gecachet (P1, opgelost in fase 1).**
`BestEffortService.kt:60-66` (`streamEfforts`) en `:128-130` (`hasUsableStreams`) gebruiken `getOrPut` en
bewaren ook `emptyMap()`/`false` wanneer de streams nog niet gedownload zijn. `RaceService.kt:173-175` doet
het al goed ("only found results are cached"). `PerformanceService.kt:177` cachet ook alleen bij
`hasStream`.
Impact: best efforts en persoonlijke records blijven leeg tot een `evict` (reset/delete), ook nadat de
streams binnen zijn.
Oplossing: alleen positieve resultaten cachen; een ontbrekende stream opnieuw opvragen. Om te voorkomen dat
`personalRecords` per afstand opnieuw gaat kijken: één streamlookup per activiteit per aanroep.

**A5 — `load90` heet 90 maar gebruikt 26 weken (P2, opgelost in fase 1).**
`LoadService.kt:412` (`load90`), `:413` (`mono90`), `:431` (`lp90`). De bedoelde semantiek staat wél
gedocumenteerd: `core/.../load/Signals.kt:19-20` ("je eigen 90e percentiel van de laatste 26 weken") en
`LoadPage.kt:102`. Het getal is dus een 90e percentiel, geen 90-daags venster; alleen de naam suggereert
iets anders. Geen rekenfout.
Oplossing: namen gelijktrekken met `SignalInput.load7P90`/`monotonyP90` en de lookback expliciet documenteren.

### 1.2 Formules en modellen die niet kloppen of niet passen

**A6 — Max-HR-schatting (P1, open).** `analysis/Models.kt:223`: onder 5 runs wordt de ruwe maximum gebruikt
(precies de piek die de p95-regel wilde dempen). `:232`: zonder runs in de laatste 12 maanden valt
`maxHrWindow` terug op de hele historiek, dus een oude piek zet de zones. De docstring noemt het een
ondergrens, maar zones en TRIMP gebruiken het als de echte waarde. Gebruikt op zes plaatsen met
verschillende fallbacks (`LoadService.kt:203,255`, `RaceService.kt:181`, `PerformanceService.kt:244,401,412`;
`:401` gebruikt `runs.ifEmpty { targets }`, `:412` `runs.ifEmpty { listOf(target) }`).
Oplossing: één gedeelde schatter met één fallback, en de zones/TRIMP-tekst laten zeggen dat het een
ondergrens is.

**A7 — Twee HR-referenties binnen één pagina (P1, open).** `LoadService.kt:200-201` kiest de RUNNING-rij,
dan DEFAULT, voor TRIMP; `:255` bouwt `HrZoneSet.garmin(...)` uit de volledige lijst, die een andere rij kan
kiezen. TRIMP en zonebuckets kunnen dus uit verschillende max/rust-HR komen.
Oplossing: de gekozen rij één keer bepalen en aan beide doorgeven.

**A8 — Tijdzone-menging (P2, open).** `LoadService.kt:203,255`, `RaceService.kt:181`,
`PerformanceService.kt:244,412` gebruiken `ZonedDateTime.now(clock)` (zone van de `Clock`-bean), terwijl alle
`today()`-functies `Europe/Brussels` gebruiken. Rond middernacht kunnen "vandaag" en de max-HR-vensters een
dag verschillen. Oplossing: overal `clock.withZone(ZONE)`.

**A9 — Garmin `calendarDate` als UTC-datum (P1, open).** `GarminRepositoryImpl.kt:140` slaat de UTC-datum op;
`GarminRangeSync.kt:24` en `GarminActivityProjection.kt:10,15` werken in Europe/Brussels. Een run na 22:00
UTC (00:00 Brussels) komt in kalenderdatum-queries op de verkeerde dag.
Oplossing: één conversie (Brussels) bij het opslaan en bij het lezen.

**A10 — TRIMP uit gemiddelde HR (P2, open).** `LoadService.kt:213` geeft `averageHeartrate` mee wanneer er
geen stream is. Banister is convex in HR, dus een gemiddelde onderschat intervallen. Die waarde wordt naast
stream-TRIMP in dezelfde reeks gezet; `Coverage` telt ze, maar de grafiek scheidt ze niet.
Oplossing: bron per dag meegeven in `DayPoint` en in de grafiek/legenda onderscheiden.

**A11 — Cachekeys (P2, open).** `PerformanceService.kt:174` stopt `maxHr` in de key; die wordt uit de hele
historiek herschat, dus elke wijziging maakt per run een nieuw item aan. Oude items worden nooit verwijderd
(alleen `evict(tenant)`). `LoadService.kt:210` bouwt de key door strings aan elkaar te plakken
(`VERSION + trimpB + minHrCoverage`), wat kan collidiëren (1.9 + 20 vs 19 + 2.0).
Oplossing: een echte dataclass als key en een begrensde cache.

**A12 — `!!` na een guard op een andere lijst (P2, deels opgelost).** `PerformanceService.kt:268-269`
(opgelost met A1), `:298` (`Stats.median(durB)!!` na een guard op `durB`, dus veilig), `:308`
(`Stats.median(...)!!` — crasht als `minLongRunsList` op 0 wordt gezet).
Oplossing: medianen als `Double?` behandelen en de tekst alleen bouwen als alle delen bestaan.

**A13 — EF-baseline over de hele historiek (P1, open).** `LoadService.kt:323-324`: EF wordt uitgedrukt als
percentage van de mediaan over de hele historiek van het stratum met de meeste runs. Vroege en recente
training worden tegen één statische basislijn gezet, en het gekozen stratum kan verschuiven naarmate er runs
bijkomen. Oplossing: een rollend venster (6-12 maanden) en het stratum vastzetten per periode.

**A14 — Meervoudige vergelijkingen (P1, open).** `PerformanceService.kt:256-258` draait EF-trends voor elk
stratum × 2 vensters; `:359` (`maxByOrNull { it.nA + it.nB }`) kiest daarna de "beste". De getoonde trend is
deels geselecteerd op ruis. Oplossing: de keuze vooraf vastleggen (bijv. op stratumgrootte) of een
correctie/waarschuwing tonen.

**A15 — Dunne steekproeven, harde verdicts (P1, open).** `performance/Common.kt:12-27`: `minRunsTrend = 5`
per kant, `minLongRunsList = 3`, `minRunsDurability = 5`; `Models.kt:77` (`EfVerdict`) gebruikt
`minSessions` met een 3 %-drempel. Met de spreiding van EF in hardlopen is 5 per kant te weinig voor 3 %.
De noten ("een trend vraagt 5 lange runs") verzachten dit deels. Oplossing: drempels verhogen of een
onzekerheidsband tonen in plaats van "beter/slechter".

**A16 — Riegel eenzijdig optimistisch (P1, open).** `race/Race.kt:188-196`: `capAnchor` verlaagt alleen,
nooit verhoogt; marathons leunen op 0-1 anker boven 21 km. `RaceService.kt:301-302` gebruikt de
Riegel-exponent 1.06 met alleen een waarschuwing in de tekst; `RaceText.kt` toont de marathonwaarschuwing
niet naast de marathonvoorspelling. Oplossing: de cap tweezijdig maken of als band presenteren, en de
waarschuwing bij het getal zetten.

**A17 — Accuracy-band uit weinig races (P2, open).** `race/Accuracy.kt:57-72` eist 5 eigen races en knipt
10 % aan elke kant weg (`rangeTrimPct`), wat bij n≈5 niets zinnigs doet. Oplossing: bij kleine n de
anker-spreiding blijven gebruiken en dat expliciet zeggen.

**A18 — Specificiteit zonder noemer (P2, open).** `RaceService.kt:349-351` telt seconden op of sneller dan
doelpace over alle runs in het venster. Een lang, overwegend langzaam blok kan hoger scoren dan een kort
kwaliteitsblok. Oplossing: als aandeel van de trainingstijd of per week normeren.

**A19 — Pacing-plan met het midden van de voorspelling als doel (P3, open).** `RaceService.kt:242-247` valt
terug op `prediction.rawSeconds` als er geen doeltijd is. De tekst zegt "het midden van je voorspelling",
maar de split-cijfers lezen als een plan. Oplossing: als band of als expliciete schatting presenteren.

**A20 — Spearman zonder interval (P3, open).** `LoadService.kt:286` rapporteert de coëfficiënt zonder
onzekerheid; `LoadPage.kt:294` noemt het terecht "overeenkomstcheck". Oplossing: permutatie-p-waarde of
een band toevoegen (de infrastructuur bestaat al in `LoadStats.spearmanPermutation`).

**A21 — Base-rate met partiële dagen (P2, open).** `LoadService.kt:425-437` telt dagen waarop enige
Garmin-rij bestaat; een dag met één meting telt even zwaar als een volledige dag. De code zegt "counts only,
not a probability", maar de readiness-UI kan het als risico lezen. Oplossing: volledigheid meewegen of de
dekking naast de frequentie tonen.

**A22 — Hergebruikte drempels (P2, open).** `PerformanceService.kt:371` beoordeelt de critical-speed-
verandering met `EfVerdict.THRESHOLD_PCT` (3 %, een EF-drempel), `:384` beoordeelt drift tegen een
hardcoded 1,0 procentpunt. Verschillende eenheden en variantiebronnen, geen onderbouwing.
Oplossing: aparte, gedocumenteerde drempels of geen verdict.

**A23 — Ongevalideerde heuristieken (P1, open).** `strava/domain/LapClassifier.kt` (1.15/1.08,
HR-marges +3/+5/+8), `WorkoutStructure.kt` (10/15 %), duurzaamheid (`>80 %` blokken, 15 m/km, 5 % thirds),
`load/Load.kt` (`LoadConfig`, grotendeels PROPOSAL), `TerrainClass` (5/15 m/km). Ze zijn gelabeld, maar
staan als oordeel op het scherm. Oplossing: één gelabeld corpus (bijv. 50 met de hand getypeerde trainingen)
als regressietest, zoals `GoldenLaps` dat al doet voor laps.

## 2. Methodologische beperkingen (samengevat)

Deze punten zijn geen bug maar een grens aan wat de app kan bewijzen. Ze horen in de UI-tekst en in de
validatie:

1. **Steekproefgrootte.** 3-5 runs per kant is te weinig voor een 3 %-oordeel (A15); de band moet mee.
2. **Selectie.** Meerdere strata × vensters, daarna het grootste kiezen (A14), zonder correctie.
3. **Basislijn.** EF en laadpercentielen zijn all-time of 26 weken (A5, A13); dat is een keuze, geen wet.
4. **Modelaannames.** Riegel (1.06, gelijke training over afstanden), Banister (vaste tau 42/7, één
   HR-referentiepaar voor de hele historiek, `b = 1.92` mannen, DECISION P4-2), TRIMP uit gemiddelde HR
   (A10, A16).
5. **Drempels.** Vrijwel alle klassificatie- en oordeeldrempels zijn PROPOSAL/UNVERIFIED (A23).
6. **Geen causaliteit.** De app toont verbanden; `PostRaceAnalysis.NOTE` en de load-teksten zeggen dat al
   goed. Dat moet zo blijven.
7. **Ontbrekende invoer.** Slaap, RPE, weer, schoenen, voeding en ziekte zijn niet bekend; de
   `TrainingAssessment`-docstring zegt dat expliciet.

## 3. Datapijplijn en dataintegriteit

De rekenkern kan niet beter zijn dan de invoer. Hier zit de meeste winst op "accuraat".

### 3.1 Dataverlies en verkeerde data

**B1 — Lap-backfill herfetcht voor altijd (P2).** `BackfillLapsDataImpl.kt:36` selecteert
`it.id in missingDetail || it.id !in withLaps`, ondanks dat `markDetailFetched` op `:51` juist bedoeld is om
runs zonder laps af te vinken. Elke backfill doet die runs opnieuw.

**B2 — `fetch-all` kan detail wissen (P1).** `FetchAllHistoricalDataImpl.kt:40` schrijft samenvattingen via
`saveAll`; `ActivityRepositoryImpl.kt:148-188` (`toEntity`) zet description, calories, sufferScore,
hoogte en `weightedAverageWatts` op null maar laat `detailFetchedAt` staan (`:184`). Mislukt de detailcall
daarna, dan wordt de rij nooit meer opgehaald. `fetch-all` haalt bovendien detail én streams opnieuw op voor
elke bestaande activiteit (geen skip).

**B3 — Sync-cursor kan pagina's overslaan (P1).** `SyncStravaDataImpl.kt:45-47` neemt `nieuwste - 7 dagen`
met een vloer van 30 dagen; een fout op pagina N zet `hasMore = false` (`:144-147`). Alles ouder dan die 30
dagen dat op pagina N stond, komt er niet meer in zonder `executeFull`.

**B4 — Sync meldt succes bij falen (P2).** `SyncStravaDataImpl.kt:151-154` schrijft altijd
`lastSyncAt = now`, ook bij errors of rate limit; fouten gaan alleen naar de log
(`StravaSyncScheduler.kt:53-55`).

**B5 — Geen verzoening met Strava (P2).** Verwijderde activiteiten blijven staan en tellen mee in load en
racevoorspellingen; edits ouder dan 30 dagen (`SyncStravaDataImpl.kt:24-28`) worden alleen door `executeFull`
opgepikt.

**B6 — `athleteId` gaat verloren bij refresh (P2).** `StravaApiClientImpl.kt:73` geeft `athleteId = 0`
terug; `SyncStravaDataImpl.kt:203-206` bewaart dat en `StravaTokenRepositoryImpl.kt:20` schrijft 0 over de
opgeslagen waarde.

**B7 — Stille corruptie in de adapter (P1).** `StravaApiClientImpl.kt:284`: ontbrekende `start_date` wordt
`ZonedDateTime.now()` (corrumpeert de volgorde). `:181`: `(raw["id"] as Number)` gooit en gooit de hele
pagina weg. `:228`: een lap zonder id krijgt `id = 0`, en dat is de primaire sleutel
(`ActivityEntity.kt:116-117`), dus laps botsen. `:150,156,163`: `mapNotNull` verschuift de indexen tussen
tijd-, afstands- en coördinaatreeksen.

**B8 — Garmin sportmapping ≠ matcher (P2).** `GarminActivityProjection.kt:68` mapt elk key met "running"
naar Run (ook trail en treadmill); `GarminActivityMatcher.kt:193` matcht alleen exacte keys. Een
geprojecteerde Garmin-trailrun matcht dus niets.

**B9-B16 — API-robustheid.** `AppConfig.kt:46` (`RestClient.create()`, geen read-timeout; Garmin heeft 30 s
in `GarminConfig.kt:40-41`); geen retry/backoff en `Retry-After` genegeerd
(`StravaApiClientImpl.kt:97-99,116-118,140-142`); `ensureValidToken` vijf keer gedupliceerd zonder lock
(`SyncStravaDataImpl.kt:201-209`, `FetchAllHistoricalDataImpl.kt:80-88`, `FetchRemainingDataImpl.kt:100-108`,
`BackfillStreamsDataImpl.kt:83-91`, `BackfillLapsDataImpl.kt:65-73`); geen overlap-guard op de Strava-sync
(`StravaSyncScheduler.kt:43-49`, `isSyncing` in `ActivityEntity.kt:158-159` wordt nooit gezet; Garmin heeft
die guard wél in `GarminSyncCoordinator.kt:47`); OAuth zonder `state` (`StravaOAuthController.kt:19-37`);
Garmin-retry met vaste 1 s/4 s (`GarminRetry.kt:26`) terwijl `GarminHttp.kt:18` de header al leest;
`GarminRangeSync.kt:77` stopt alleen op AUTH/NOT_CONFIGURED.

**B16 — `lastSuccessDate` bevriest (P1).** `GarminRangeSync.kt:151-152` en `GarminSync.kt:49` zetten
`lastSuccessDate` alleen bij nul fouten. Eén endpoint dat permanent 403/4xx geeft, laat het incremental
venster elke dag groeien (`SyncGarminDataImpl.kt:36-38`).

### 3.2 Persistentie en performance

**B17-B21.** N+1 op raw payloads (`GarminRepositoryImpl.kt:63-67`, `:109-111` delete+flush per dag);
full-table loads (`ActivityRepositoryImpl.kt:124-128`, `:115-121`) en `findById` per samenvatting
(`SyncStravaDataImpl.kt:77`, tot 100 queries per pagina); ontbrekende indexen op `start_date`,
`activity_laps.activity_id` (`ActivityEntity.kt:22-23,118-119`) en `garmin_activity_ref.start_time_gmt`
(`GarminEntities.kt:130`); `deleteByActivityId`/`saveLaps` zonder `@Transactional`
(`ActivityRepositoryImpl.kt:38,80-82`) terwijl `markDetailFetched` (`:26-27`) die wél heeft;
`maximum-pool-size: 2` (`application.yml:12`, `application-prod.yml:9`) voor web + jobs.

**B22 — Eén scheduler-thread (P2).** `Application.kt:9` aan; geen `TaskScheduler`-bean;
`GarminSyncScheduler.kt:27` draait synchroon (`async=false`). Een lange Garmin-run blokkeert de Strava-sync
en de race-log-cron.

**B23-B28 — Rest.** Stille `runCatching { }.getOrNull()` (`LoadService.kt:384`, `RaceService.kt:365,371,387`)
maakt van een storing "geen data"; O(n²)/onge cachet werk op het requestpad
(`LoadService.kt:259-261,286-287`, `RaceService.kt:349-351,406,417`, `RunComparisonService.kt:87`);
ongebruikte config (`LoadConfig.hardLookbackDays`, `minWeeksBefore`); predictielog schrijft bij elke
paginaweergave (`RaceService.kt:219`, idempotent per dag via `PredictionLog`); `RunComparisonService.kt:61`
geeft null voor de hele vergelijking als één activiteit intussen weg is; gedupliceerde NL-namen
(`LoadService.kt:447`, `RaceService.kt:354-359`), vensterfilters (`PerformanceService.kt:248-249,357-358`),
daily-load-filters (`LoadService.kt:347,478`, `RaceService.kt:375,401`) en max-HR-schatting (zes plaatsen).

**B29 — UI-teksten.** `LoadPage.kt:228` en `LoadText.kt:28` zijn zorgvuldig; `LoadResponsePage.kt:116`
gebruikt `LoadConfig.DEFAULT.minIsolated` in plaats van de geconfigureerde waarde; `RaceText.kt:78` toont de
Riegel-aanname maar niet de marathonwaarschuwing; `PerformanceText.kt:325` is correct, maar de 3 %-drempel
erbij is een voorstel; `RecoveryText.kt:88` en `TrainingRatingPage.kt:126,664` zijn goed afgebakend.

## 4. Security en multi-user

**C1 — Eén DB-credential voor alle tenants (P0).** `TenantRoutingDataSource.kt:46-53` bouwt elke
tenantpool met de `username`/`password` van de owner; `deploy/docker-compose.yml:41-42` draait als
`postgres` (superuser). Scheiding is alleen "welke databasenaam de code kiest". Oplossing: een
`running_app`-rol met `CONNECT` op alleen de eigen databases, geen superuser, en `REVOKE CONNECT` op
`running_control` voor tenants.

**C2 — Strava-tokens plaintext (P0).** `strava/db/StravaTokenEntity.kt:12-16` bewaart `access_token` en
`refresh_token` onversleuteld in elke tenant-DB. Alleen de Garmin-key gaat door `SecretCipher`
(`SetupController.kt:120`). Elke dump (`backup.sh`, `migrate.sh`) bevat live tokens. Oplossing: beide
tokens versleutelen volgens hetzelfde patroon.

**C3 — Wachtwoordminimum 1 (P0).** `RegisterController.kt:108`: `MIN_PASSWORD = 1`. Oplossing: minimaal 10,
en dezelfde regel voor `APP_PASSWORD`.

**C4 — Prod kan open starten (P0).** `application-prod.yml:17` mapt `${APP_PASSWORD}` en de comment zegt dat
de app weigert; `SecurityConfig.kt:52-55` logt alleen een waarschuwing en doet `permitAll()`. Oplossing:
`check(password.isNotBlank())` onder het prod-profiel.

**C5 — Remember-me zonder SameSite (P1).** `SecurityConfig.kt:50` zet CSRF uit met `SameSite=Lax` als
argument, maar `:66-71` zet die vlag niet op de remember-me-cookie (90 dagen, `alwaysRemember`). Na
sessieverloop kan een cross-site formulier `/admin/users/{u}/delete`, `/settings/reset` of
`/setup/garmin/clear` raken. Oplossing: `SameSite=Lax` op die cookie of CSRF-tokens op de POST-routes.

**C6 — Login-throttling (P1).** `TenantWeb.kt:46-58`: 10 fouten per 15 minuten per (IP, gebruiker). Een
aanvaller die IP's of gebruikersnamen roteert wordt niet geremd; de faaldetectie via de `Location`-header is
fragiel. Oplossing: een globale limiet per gebruiker en per IP, met een expliciet faalsignaal.

**C7-C8 — Back-ups (P1).** `deploy/backup.sh` schrijft onversleutelde `pg_dump -Fc`-archieven in
`deploy/backups/` (14 dagen, zelfde schijf); `APP_TENANT_SECRET_KEY` staat in `deploy/.env` in dezelfde
boom. `deploy/running.dump` (48 MB, mode 644) staat in de tree. `migrate.sh restore` draait
`pg_restore --clean --if-exists` zonder bevestiging en kent `running_control` niet. Oplossing: `umask 077`,
versleutelen (`age`/`gpg`), off-host kopie, `running_control` meenemen, getypte bevestiging.

**C9 — Dev-defaults (P1).** `docker-compose.yml:10` publiceert Postgres op `0.0.0.0:5432` met `admin` als
fallback; `application.yml:9` gebruikt `DB_PASSWORD:admin`; `application-dev.yml:17-20` zet de H2-console
open op een aan alle interfaces gebonden server (lege `sa` = willekeurige SQL).

**C10-C15 — Rest.** Geen `read_only`, `cap_drop`, `no-new-privileges`, `pids_limit` of healthcheck
(`deploy/docker-compose.yml`); secrets als env-variabelen; geen `sslmode` naar Postgres; `ClientIp.kt:142`
classificeert interne adressen met string-prefixes; OAuth-URL's zonder URL-encoding
(`StravaOAuthController.kt:23-27`, `TenantStravaOAuthController.kt:45-50`); `OAuthState.kt:59-60` houdt de
replay-map in geheugen; `coach.html:128` gebruikt `th:utext` op de door de gebruiker ingevoerde
profieltekst (XSS).

Wat wél goed is: de routing faalt gesloten (`TenantContext.currentOrFail`), `TenantContextFilter` bindt de
tenant alleen uit de principal, `asTenant` herstelt in `finally`, caches zijn tenant-keyed
(`TenantKey.of`), jobs lopen per tenant via `TenantJobRunner`, async werk erft de tenant
(`TenantBackfill.kt:84`), `AdminUsers.reader` gebruikt een verse thread tegen de open-in-view-connection,
`TenantReset` weigert de owner en onveilige namen, en `ddl-auto` staat uit zodra multi-user aan is.

## 5. Tests, tooling en documentatie

**D1** — `ktlint` staat in `buildSrc/build.gradle.kts:15` maar wordt in geen module toegepast; geen detekt,
geen JaCoCo, geen CI.
**D2** — `core` krijgt via `kotlin-module.gradle.kts` `api(platform(SpringBootPlugin.BOM_COORDINATES))`;
Spring lekt in de Spring-vrije module.
**D3** — Geen directe tests voor `race/Plan.kt`, `load/Statistics.kt`, `performance/Zones.kt`,
`load/Model.kt`; `GoldenLaps` dekt alleen de lap-classifier.
**D4** — Geen property-based tests voor de rekenkern (monotonie, grenzen, idempotentie).
**D5** — Klokafhankelijk/flaky: `OnboardingIT.kt:194` (`Thread.sleep(100)`), `AiPromptTest.kt:103-104`,
`RaceControllerTest.kt:49,65,67` (wall clock).
**D6** — Docs drift: `AGENTS.md` noemt `-Dspring.profiles.active=dev` (Gradle negeert dat; gebruik `-P` of
`--args`); `AGENTS.md` zegt "phases 0-6 done" terwijl `MULTI_USER_PROGRESS.md` "Phase 7 approved" zegt.
**D7** — Repo-clutter: `running-coach-source.zip`, `connect-personal-api-python-main/`, twee parallelle
Garmin-docsets (root `GARMIN_*.md` vs `docs/garmin-*`).
**D8** — `layout.html` (765 regels), `performance.html` (631), `app.css` (1556); inline scripts in
`layout.html`, `ai.html`, `admin-users.html`; `race-history.html:74` niet gepagineerd.
**D9** — Enkele tests leggen de huidige implementatie vast in plaats van de bedoelde regel (zie ook de
`Rolling`-test die 5/7 als 50 punten vastlegt; die blijft geldig voor `Rolling.window`, maar de nieuwe
weekregel krijgt een eigen test).
**D10** — De "geen zekerheidstaal"-test bestaat voor load/performance; voor race- en herstelteksten is er
geen equivalente controle.

## 6. Voorgesteld traject

| Fase | Inhoud | Effect | Omvang |
|---|---|---|---|
| **1** | A1, A2, A3, A4, A5 | Zichtbaar foute getallen en één dataverlies-pad weg | S |
| 2 | A6, A7, A8, A9, A10 | Zones, TRIMP, readiness en Garmin-dagen consistent | M |
| 3 | B2, B3, B4, B7, B9, B10 | Data-integriteit en API-robustheid | M |
| 4 | C1-C9, C15 | Security en multi-user | M |
| 5 | A13-A16, A23 + gelabeld corpus | De echte sprong naar "accuraat" | L |
| 6 | B16, B22, B17-B21 | Stabiliteit op lange historiek | M |
| 7 | D1-D10 | CI, tests, docs, repo-hygiëne | M |

Fase 1 is bewust klein gehouden: alleen fouten die de getoonde cijfers aantoonbaar vertekenen, zonder
drempelwaarden of heuristieken te wijzigen.

## 7. Fase 1 — implementatieverslag

**Status: afgerond, niet gecommit.** Alleen A1-A5; geen drempels of heuristieken gewijzigd.

### Wat is opgelost

| ID | Oplossing |
|---|---|
| A1 | Drift- en ontkoppelingsmediaan (samenvatting én tiles) gebruiken dezelfde populatie (`driftAvailable`). Lege populatie of `null`-mediaan geeft de "Onvoldoende data"-tekst en `-` in de tiles; geen `!!` meer. |
| A2 | Nieuw `Rolling.completeWindow`: een 7-daagse weekbelasting bestaat alleen als alle dagen bekend zijn, anders `null`. Gebruikt voor `trailing7` (dus `load7`-percentiel, `acuteBand`, `load7Level`), `acute`/`last7`, de acute grafieklijn en `RaceWeek.loadPct`. `Rolling.window` (70 %-regel) blijft voor het 28-daagse gemiddelde. |
| A3 | `GoalFeasibility.evaluate` krijgt `daysToGo` (Long). Historievergelijking: `historyWeeks = max(1, dagen / 7)` (naar beneden, nooit 0). `Feasibility` krijgt `daysToGo`; de racepagina toont resterende dagen i.p.v. weken. |
| A4 | `BestEffortService` cachet alleen resultaten van een activiteit met bruikbare stream. `usableStreamCache` is vervallen; `personalRecords` doet één streamlookup per run. |
| A5 | Semantiek is een 90e percentiel over weekbelastingen uit `bandWeeks` (26) weken, zoals `Signals.kt` al zei. Variabelen hernoemd (`loadP90`, `monotonyP90`). Regeltekst gebruikt nu de gekozen `bandWeeks` (`SignalKey.ruleFor`) i.p.v. een vaste "26 weken". Geen rekenwijziging. |

### Gewijzigde bestanden

- core: `load/Load.kt`, `load/Signals.kt`, `race/Plan.kt`, `race/Readiness.kt`
- runner: `BestEffortService.kt`, `LoadPage.kt`, `LoadService.kt`, `PerformanceService.kt`, `PerformanceText.kt`, `RacePage.kt`, `RaceService.kt`
- tests: `LoadBasicsTest.kt`, `RaceTest.kt`, `LoadServiceTest.kt`, `PerformanceServiceTest.kt`, nieuw `BestEffortServiceTest.kt`

### Tests

- Nieuw/aangepast: `completeWindow` (volledig, 6/7, rustdag = bekend, leeg); `RaceWeek.loadPct` met gat in de week vs. buiten de week; `GoalFeasibility` met 6, 13, 14, 1, 0 en negatieve dagen; bestaande historietest naar dagen omgezet (35 dagen = 5 weken); weekbelasting met onbekende dag in `LoadService` (acute, verschil, band, signaal); signaalregel met 12 weken; drift/ontkoppeling gemengde populatie en lege dataset; `BestEffortService` (stream later beschikbaar, stream zonder tijd/afstand, cachen van gevonden resultaat, records na aankomst stream, geen runs).
- De oude `Rolling`-test (5/7 dagen = 50) blijft geldig voor `window`; de weekregel heeft een eigen test.
- Resultaat: `:core:test` 446 geslaagd, `:runner:test` 304 geslaagd, 0 mislukt, `./gradlew build` geslaagd.

### Open na fase 1

- `Taper.load7Pct` (`race/History.kt:17`) gebruikt nog de 70 %-regel voor 7 dagen; bewust buiten scope.
- Gedragswijziging: een week met een dag zonder hartslag toont nu "niet te berekenen" waar eerder een te laag getal stond. Dat kan vaker voorkomen bij de eerste weken van de historiek.
- Alle overige bevindingen (A6-A23, B, C, D) blijven open.
