# SAMCNPC — lokalny plan autonomicznego rozwoju

Plan F02 dopisany 2026-09-20: [LLM_INTEGRATION_PLAN](LLM_INTEGRATION_PLAN.md),
**IN_PROGRESS, 8/36, L0 ukończone**. Katalog operacji → kontekst →
kontrakt decyzji → provider → Translator → Supervisor → Planner → akceptacja.
[ADR 0085](adr/0085-llm-high-level-planning.md) zastępuje wcześniejszy wymóg
sterowania prymitywami. L0 mapuje istniejące P11.1/P11.7; osobny plan F02 nie
zmienia liczników 112 punktów ani Zoo i nie uruchamia pracy implementacyjnej.

Decyzja użytkownika 2026-09-20: test skórek na dwóch zalogowanych kontach ma status
**MANUAL_PENDING** i trafia do osobnego odbioru ręcznego. Nie blokuje ukończenia prac
autonomicznych ani zamknięcia P10/P11/P12 po spełnieniu pozostałych wymagań.
W raporcie końcowym zachować informację o niezweryfikowanym zakresie; brak kont
nie jest BLOCKED_EXTERNAL. P10.5/P10.7 nie dostają automatycznie PASS: trzeba jeszcze
rozliczyć ich pozostałe dowody. Historyczne liczniki pozostają bez zmian.
Procedura: [ręczny test skórek](../autonomy/run-20260912-O4/authenticated-skin-gate.md).

Aktualizacja 2026-09-12: obowiązuje [Acceptance Zoo](ACCEPTANCE_ZOO_PLAN.md) i
[ADR 0058](adr/0058-acceptance-zoo-and-nonblocking-presentation-gates.md).
**Stary zakres: 96/112 DONE, 16 otwartych. Nowe Zoo: 0/23.**

P00–P09 i automatyczna część O4 mają dowody. Dalsza kolejność to Z1 wspólne niezmienniki,
Z2 Courier/maszyny, Z3 Miner/Farmer/Lumberjack, Z4 Fisherman/Explorer, Z5 wspólna kampania,
Z6 dotychczasowe P11, Z7 dotychczasowe P12. Aktualny punkt startowy: **Z1.1, PLAN_READY**.

Użytkownik obniżył priorytet ręcznych testów skórek i podobnych testów prezentacji do P2.
P10.5/P10.7 pozostają otwarte; nie blokują niezależnej implementacji ani publicznego API/JSON.
Uprawnienia, spójność ekwipunku/GUI, istotna synchronizacja, lifecycle i ładowanie serwera
pozostają krytyczne. [Rzeczywisty test dwóch kont](../autonomy/run-20260912-O4/authenticated-skin-gate.md)
nadal wymaga właściwego dowodu. P11 finalizuje się po krytycznej weryfikacji mechanizmów,
nie po kosmetyce. Otwarte kryteria ograniczają końcową deklarację gotowości.

[Macierz O1–O4](../autonomy/run-20260912-O4/coverage-matrix.md) i aktualne manifesty źródeł
pozwalają ponownie używać niezmienionych wyników. Z5 dodaje godzinny Zoo; obecny 30-minutowy
soak pozostaje dotychczasowym dowodem, nie jest przemianowany na nowy test.
Zachować też nieudane przebiegi i udokumentowaną diagnostykę nadmiarowych nasion.

Aktualne źródła i JAR-y Core/Behavior zostały opublikowane 2026-09-12 jako
`v0.1.0-dev.20260912`; dowody w PROJECT_STATE.md. To nie zamyka P12.
Ten wpis planistyczny nie rozpoczyna runu ani monitoringu. LLM provider, kreator GUI,
zbiorcze repo i crafting pozostają odłożone; crafting jest ostatni.

Poniższe datowane opisy zachowują historię powstawania zakresu. Bieżącą kolejność i
graf zależności określa Acceptance Zoo; 112 wcześniejszych checkboxów zachowuje swój zakres.

P03 zamknięto na podstawie kodu, zachowanych testów oraz kontroli końcowych:
[raport P03](../autonomy/development-20260910-P03/closeout.md),
[dowody P03](../autonomy/development-20260910-P03/completed-evidence.json).
Oznaczenia DONE odnoszą się do opisanych etapów, nie do całego produktu.

## 1. Cel i przyjęta kolejność

Core ma zapewniać kompletne, przewidywalne ciało NPC podobne do gracza. Behavior ma
doprowadzać ograniczone zadania do sprawdzalnego wyniku, radzić sobie ze zmianą
otoczenia i wyjaśniać swoje decyzje. Rozwój ma następować przez działające przekroje:
obserwacja → decyzja → rzeczywista akcja → wynik → odzyskanie sprawności po problemie.

Użytkownik ustalił 2026-09-10 następującą kolejność:

1. Dopracować kod Core i Behavior oraz rzeczywiste zachowanie NPC.
2. Po ustabilizowaniu tych podstaw dopracować zewnętrzne paczki JSON, katalog
   definicji, kompatybilność formatu i narzędzia dla autorów paczek.
3. Po osiągnięciu dojrzałości rozważyć kreator GUI. Jego wykonanie jest osobnym,
   przyszłym zakresem; obecny plan nie obejmuje budowy kreatora.

Decyzję zapisuje [ADR 0018](adr/0018-code-first-behavior-development.md).
Obowiązują [AGENTS.md](../AGENTS.md), instrukcje modułów i
[kryteria akceptacji](ACCEPTANCE_CRITERIA.md). Zmienia się kolejność prac nad
zewnętrznymi paczkami; pozostają one wymaganiem końcowego produktu.

Istniejący loader, kompilator i ich testy pozostają utrzymywane. Wbudowane zachowania
przechodzą przez ten sam kompilator, rejestry i arbitraż. Przy zmianie potrzebnej
definicji aktualizować minimalny niezbędny fragment schematu, fixture i migracji.
Odłożenie finalizacji formatu nie usprawiedliwia zepsucia działających paczek,
wyłączenia walidacji ani dodania osobnego wykonawcy omijającego kanały.

### Rozszerzenie wymagań — 2026-09-10

Obowiązuje [katalog operacji](OPERATIONS.md) i
[ADR 0024](adr/0024-predefined-operations-and-future-primitive-control.md).
Drwal dostaje wybór gatunku/zestawu drewna i obszaru. Do wymaganych operacji należą
również mining, defend, attack, food gathering, farming i planting trees z opisanymi
wariantami, obok transportu i follow. Każda ma działać samodzielnie w Core+Behavior,
z parametrami, lokalnym odzyskiwaniem pracy i rozliczonym wynikiem, bez wywołań modelu.
Poprzednie odłożenie mining/farming w P07.5 zostaje zastąpione tym zakresem.

Pierwotny zakres z 2026-09-10 obejmował również sterowanie prymitywami przez LLM.
Decyzja 2026-09-20 i ADR 0085 zastępują tę część: F02 to Translator/Supervisor/Planner
nad publicznymi operacjami Behavior, bez przekazywania modelowi kanałów ciała.
Trening/fine-tuning pozostaje poza planem. P00–P03 nie są otwierane ponownie.

### Elastyczne zadania i lokalny LLM — uzupełnienie 2026-09-11

Użytkownik doprecyzował zmianę ilości/zasobu/źródła/odbiorcy/metody/wyposażenia oraz
adaptację do różnych światów. [OPERATIONS](OPERATIONS.md) i
[ADR 0039](adr/0039-adaptive-operations-and-local-llm-boundary.md) określają parametry,
twarde ograniczenia, lokalny dobór kroków i korekty z zachowaniem postępu. Rozszerzamy
warunki otwartych P07.2/.4/.5/.22, P09 i P11.7; nie zaliczamy ich przez dawny P04/P05.
W chwili tej decyzji było 112 punktów (46 DONE, 66 otwartych); aktualne liczniki i Zoo są na początku dokumentu.
Przyszłe F02 celuje w LM Studio/Ollama i konfigurowalne API zgodne z OpenAI także dla
zdalnego modelu. Codex/MCP jest opcjonalnym późniejszym adapterem, nie zależnością.

### Małe zachowania i inspiracje z kodu — uzupełnienie 2026-09-10

Użytkownik zlecił włączenie [15 małych zachowań](BEHAVIOR_COMPONENTS.md) do taska:
odwet, selekcja celu, pomoc summoner, dobór wyposażenia/dystansu, wycofanie/leczenie,
limit pościgu, zaopatrzenie, odkładanie, pickup po drodze, patrol, ustępowanie,
lokalne recovery i powrót do zadania. Są wymagane w przypisanych poniżej etapach.
Nie tworzyć osobnego silnika do ich składania; korzystać z istniejących definicji,
arbitrażu i kontraktu zadania. Wyzwalacz, wybór celu i wykonanie są rozdzielone.

[Przegląd podobnych modów](SIMILAR_MODS.md) wskazuje konkretne źródła i pomysły do
własnej implementacji: nowe zdarzenie obrażeń, fazy magazynu, stany zakończenia,
stabilny dobór wyposażenia oraz lifecycle rozszerzeń. Przejrzeć odpowiedni fragment
przed pracą nad punktem i zachować granice SAMCNPC. Przegląd jest dokumentacją;
obce źródła nie są zależnością projektu ani dowodem naszych testów. LLM pozostaje F02.

## 2. Kontrakt architektoniczny

| Obszar | Core | Behavior |
|---|---|---|
| Tożsamość i ciało | UUID, SummonerBinding, ekwipunek, zdrowie, efekty, skin, synchronizacja | Odczyt faktów potrzebnych do zadania |
| Ruch | Fizyka, sterowanie, wykonanie nawigacji do wskazanego punktu | Wybór celu, stanowiska, obejścia i reakcji na utknięcie |
| Praca z blokiem | Wskazany blok, zasięg, narzędzie, postęp, drop, zużycie | Wybór zasobu, kolejności pracy i dopuszczalnych zmian otoczenia |
| Walka i używanie przedmiotów | Mechanika wobec wskazanego celu/przedmiotu | Cel, taktyka, obrona, ucieczka i moment użycia |
| Informacja | Ograniczone fakty i wyniki mechaniczne | Pamięć zadania, interpretacja, priorytety i uzasadnienie |
| Zapis | Trwałe dane ciała i wersjonowanie | Przypisania, trwały zamiar i potrzebny postęp zadania |
| Grupa NPC | Walidowane operacje pojedynczych ciał | Podział pracy, rezerwacje i sprawiedliwe planowanie |

Zawsze utrzymać trzy artefakty i zależność `samcnpc-llm -> samcnpc-behavior -> samcnpc-core`.
LLM pozostaje rzeczywistym, opcjonalnym modułem bez dostawcy i bez sieci na starcie.
Produkcja pozostaje w Kotlinie; toolchain/bytecode Java 17. Odczytane piny:
Minecraft 1.20.1, Forge 47.4.21, ForgeGradle 6.0.54, Kotlin 2.2.21, KFF 4.12.0.
Nie zmieniać ich bez odtworzonego problemu i uzasadnionej, przypiętej alternatywy.

Warunki są wyłącznie odczytowe. Zmiany świata przechodzą przez Core na wątku serwera.
Definicje są niezmienne, pamięć i zapytania ograniczone, a arbitraż deterministyczny
dla tych samych obserwacji i zapisanych wejść. Fizyczna symulacja Minecrafta nie jest
obietnicą identycznego przebiegu przy dowolnym innym czasie ładowania chunków.

Nie wprowadzać ogólnego języka programowania w JSON, refleksji, wykonywania komend,
fałszywego ServerPlayer ani własnego wielkiego frameworka planowania. Rejestry zawierają
znane implementacje Kotlin. Nową abstrakcję uzasadniać rzeczywistą wspólną potrzebą.

## 3. Historyczny punkt startowy i ryzyka odczytane z kodu

Pierwotny plan korzystał z zapisów testów kodu i gry z 2026-09-08
w [PROJECT_STATE.md](../PROJECT_STATE.md):
57 testów jednostkowych, 25 Core GameTestów, 18 Behavior GameTestów w opisanych tam
uruchomieniach oraz test klienta konfiguracji. Są to dowody historyczne, odnoszące
się do wskazanych źródeł i uruchomień. P00 ustalił nową bazę; aktualny stan jest w tabeli etapów.

Obecny katalog główny nie jest repozytorium Git: `git status --short` zwraca
`fatal: not a git repository`. Lokalne kopie publikacyjne nie są domyślnym miejscem
wykonania planu. Nie inicjalizować Git ani nie publikować dokumentów na potrzeby planu.

| ID | Obserwacja z przeglądu źródeł | Etap i sposób rozstrzygnięcia |
|---|---|---|
| R01 | ConditionHandler otrzymuje modyfikujące NpcFacade; hasLiveTarget usuwa wygasły cel podczas sprawdzania warunku | P01: interfejs odczytowy; porządkowanie pamięci poza oceną warunków |
| R02 | CompiledAction i warunki przechowują JsonObject, a wykonawca odczytuje argumenty i wyszukuje definicje | P01: typowane, niezmienne argumenty i wcześniej rozwiązane definicje |
| R03 | move_to_summoner/move_to_target deklarują MOVEMENT, ale steerToward wywołuje lookAtEntity | P01: pełne deklaracje kanałów i test rzeczywistego konfliktu LOOK |
| R04 | Brak paczki kończy tick wcześniejszym return; samo to nie dowodzi zatrzymania wcześniej uruchomionej długiej akcji | P02/P04: testy utraty sterowania, przypisania i generacji rejestru |
| R05 | Runtime buduje napisy diagnostyczne i sortuje część statycznych danych w ticku | P01/P03/P09: prekompilacja stałego porządku, ograniczone rekordy, pomiary |
| R06 | Rezerwacja obszaru jest przyznawana przy wywołaniu; sortowanie znalezionego konfliktu nie ustala kolejności konkurujących zgłoszeń | P08: jawna kolejność rozpatrywania zgłoszeń i test niezależności od kolejności ticków NPC |
| R07 | verify_distribution.py sprawdza istnienie nazwanych JAR-ów; nie dowodzi dokładnie jednego artefaktu na moduł | P00/P12: uszczelnienie sprawdzania liczby i nazwy artefaktów, bez osłabiania kontroli |
| R08 | BehaviorAssignmentStore zapisuje przypisania bez jawnego pola wersji | P04/P09: wersja zapisu, migracja obecnego formatu jako legacy i testy uszkodzonych danych |

To ustalenia statyczne i hipotezy do sprawdzenia, a nie raport z nowych prób w grze.
Źródła wejściowe:
[Core API](../samcnpc-core/src/main/kotlin/io/samcnpc/core/api/NpcFacade.kt),
[model Behavior](../samcnpc-behavior/src/main/kotlin/io/samcnpc/behavior/model/BehaviorModel.kt),
[definicje](../samcnpc-behavior/src/main/kotlin/io/samcnpc/behavior/registry/BehaviorDefinitions.kt),
[runtime](../samcnpc-behavior/src/main/kotlin/io/samcnpc/behavior/runtime/BehaviorRuntimeService.kt),
[pamięć celu](../samcnpc-behavior/src/main/kotlin/io/samcnpc/behavior/runtime/BehaviorTargetMemory.kt),
[przypisania](../samcnpc-behavior/src/main/kotlin/io/samcnpc/behavior/runtime/BehaviorAssignmentStore.kt),
[rezerwacje](../samcnpc-behavior/src/main/kotlin/io/samcnpc/behavior/kernel/work/SpatialWorkClaimKernel.kt).

## 4. Procedura autonomicznego wykonania i wznowienia

1. Odczytać ten plan, bieżący PROJECT_STATE i odpowiednie AGENTS.md. Wybrać pierwszy
   niedokończony pakiet powiązanych punktów z gotowymi zależnościami według grup poniżej;
   nie odtwarzać historycznego bootstrapu.
2. Ustalić aktualne pliki, uruchomione procesy używające JAR-ów i miejsce dowodów.
   Pracować w kopii świata testowego; chronić istniejące zapisy i nie usuwać cudzych zmian.
3. Dla punktu zapisać oczekiwane zachowanie i test, który może wykazać jego błąd.
   Przy regresji najpierw odtworzyć problem. Nie tworzyć testów powielających składnię.
4. Wykonać najmniejszy kompletny fragment. Zmianę granic, semantyki zachowania,
   trwałego formatu lub zależności udokumentować nowym ADR przed utrwaleniem kontraktu.
5. Wewnątrz pakietu uruchamiać odpowiednie testy przyrostowe/jednostkowe/statyczne oraz
   celowane scenariusze świata. Łączyć powiązane funkcje w jednym przebiegu i kliencie.
   Z1–Z4 mają wspólne zamknięcie milestone'u w Z5: jeden clean build i pełne wymagane
   regresje, klient oraz serwer. O1–O4 zachowują historyczne dowody. Po zmianie Core objąć używające go scenariusze Behavior. Naprawić
   wykrytą regresję przed dalszą zależną zmianą. Nie powtarzać niezmienionych pełnych
   kontroli po każdym podpunkcie. Ta wielkość bramki wynika z życzenia użytkownika z 2026-09-11.
6. Zaktualizować tabelę postępu i PROJECT_STATE: faktyczne polecenia, wyniki, logi,
   ograniczenia oraz dokładny następny punkt. Jeden etap może wymagać wielu takich iteracji.
7. Kontynuować następny gotowy punkt w ramach zleconej realizacji. O postępie informować
   zwięźle, nie zostawiając użytkownika bez aktualizacji dłużej niż około 60 sekund.

### Łączenie implementacji i walidacji — decyzja 2026-09-12

Obowiązuje [OPTIMIZED_EXECUTION_PLAN](OPTIMIZED_EXECUTION_PLAN.md) i szczegółowe
[Acceptance Zoo](ACCEPTANCE_ZOO_PLAN.md). Z1–Z4 grupują wspólne zmiany i odpowiednie
kontrole przyrostowe. Z5 zamyka milestone na zamrożonych źródłach: clean build, regresje,
JVM save/load, funkcjonalny klient oraz >=3600 sekund / >=72000 ticków mieszanego Zoo.
Z6 realizuje istniejące P11 po sprawdzeniu krytycznych mechanizmów. Z7 mapuje P12.
Ręczne bramki prezentacji P2 nie są warunkiem rozpoczęcia tych prac.

Mapa: `autonomy/planning-20260912-acceptance-zoo/execution-batches.json`.
Poprzedni graf O1–O6 jest historyczny. Ukończone punkty zachowują dowody; nowe Zoo ma
własny licznik. Nie oznaczać implementacji oczekującej na rzeczywiste testy jako DONE.

Wykonawca jest jeden: nie planować delegowania ani równoległych agentów. Niezależne
odczyty można grupować; edycje i zależne testy wykonywać w kontrolowanej kolejności.
Równoległych klientów/serwerów korzystających z tego samego run directory nie uruchamiać.

Rutynowe decyzje implementacyjne podejmować samodzielnie i zapisywać. Rzeczywisty
zewnętrzny brak opisać jako BLOCKED z reprodukcją oraz kontynuować niezależne punkty.
Brak czasu, nieprzebadany kod lub trudna regresja nie są zewnętrznym blockerem.
Nie oznaczać FAIL/BLOCKED jako PASS ani nie zamykać zależnej bramki dojrzałości.

Obowiązuje istniejąca zgoda projektowa na EULA i uruchamianie serwerów testowych.
Bieżący zakres obejmuje realizację rozszerzonego planu. Obecny snapshot Core/Behavior
ma nową zgodę na publikację przez git. Końcowe wydanie i wyłączenie nadal wymagają P12.6. Heartbeat jest obecnie nieaktywny. Ponowne uzbrojenie wymaga wyraźnej
prośby użytkownika dla bieżącej sesji i uruchomienia Windows. Gdy aktywny, zapisywać heartbeat
przy rzeczywistym postępie i przed długą operacją, co najmniej co 30 minut; próg awaryjny
wynosi 40 minut. Nie wprowadzać syntetycznych pingów w tle.

Przy braku Git przed istotnym podetapem zapisywać ograniczoną kopię zmienianych
źródeł, listę plików i hash/diff w katalogu dowodów. Nie kopiować automatycznie
całego workspace ani światów użytkownika. Nie traktować kopii jako nowego źródła prawdy.

## 5. Etapy, zależności i status

| Etap | Wynik | Wymaga | Status |
|---|---|---|---|
| P00 | Aktualna baza i wiarygodne bramki | — | DONE |
| P01 | Czysty model oceny i pełny arbitraż kanałów | P00 | DONE |
| P02 | Spójny cykl akcji i sprawdzone mechaniki Core | P01 | DONE |
| P03 | Obserwacje, pomiar postępu i podstawowa diagnostyka | P02 | DONE |
| P04 | Trwały model zadania i ograniczone odzyskiwanie pracy | P03 | DONE |
| P05 | Drwal: gatunek, obszar i dostarczona ilość | P04 | DONE |
| P06 | Follow, defend, attack i bezpieczne przerwania pracy | P05 | DONE |
| P07 | Transport, mining, food gathering, farming, planting trees | P06 | DONE |
| P08 | Koordynacja kilku NPC i współdzielonych zasobów | P07 | DONE |
| P09 | Restart, reload, usuwanie, wydajność i długie próby | P08 | DONE |
| P10 | Bramka dojrzałości Core/Behavior | P09 | OPEN_PRESENTATION_P2; części krytyczne mają dowody |
| P11 | Finalizacja zewnętrznych paczek JSON | Z5: krytyczna weryfikacja mechanizmów; bez zależności od P2 | WAITING_FOR_ZOO |
| P12 | Pełna lokalna akceptacja produktu i dystrybucji | P11 | NOT_STARTED |
| F01 | Kreator GUI — daleka przyszłość | Poza aktywnym zakresem | DEFERRED |
| F02 | Integracja LLM: Translator → Supervisor → Planner | L0 mapuje P11.1/P11.7; osobny plan | IN_PROGRESS, 7/36; L0 ukończone |
| F03 | Crafting — osobny przyszły moduł samcnpc-crafting, na sam koniec | Poza aktywnym zakresem; nie blokuje F02 | DEFERRED_LAST |

Zależności oznaczają ukończenie wymaganej części, nie samą obecność kodu. Niezależne
testy skórek lub lifecycle można prowadzić wcześniej, jeśli opóźnia je dostępność
środowiska. Przy zewnętrznie zablokowanej próbie można realizować niezależny podpunkt
późniejszego etapu po zapisaniu, dlaczego nie zależy on od brakującego dowodu.
Zablokowany etap pozostaje nieukończony. Nie awansować P10/P12 z niezamkniętym
wymaganiem tylko przez zmianę statusu.

### P00 — aktualna baza

- [x] P00.1 Odczytać rzeczywiste źródła i skonfrontować PROJECT_STATE, CORE_GAP_AUDIT
  i ACCEPTANCE_CRITERIA. Starszego audytu z sierpnia nie traktować jako dowodu
  nieobecności późniejszej funkcji. Zestawić wymaganie → istniejący kod → dowód → luka.
- [x] P00.2 Ustalić Java 17, wrapper Gradle, Python, piny, dostępne taski i zasoby
  scen testowych. Odtworzyć build i oba zestawy GameTestów; zachować liczby testów,
  błędy i pominięcia. Nazwa pliku zawierająca PASS nie jest wynikiem testu.
- [x] P00.3 Sprawdzić dokładnie trzy końcowe artefakty, bytecode Java 17 i granice
  modułów. Uszczelnić verify_distribution.py testem przypadku dodatkowego JAR-a.
- [x] P00.4 Wykonać początkowy smoke Core-only, Core+Behavior i zwykłego serwera
  z trzema modami. Zachować istniejące próby konfiguracji, animacji i leśnych regresji.
- [x] P00.5 Rozdzielić znane ograniczenia od brakujących dowodów. Zapisać profil
  sprzętu, ustawienia świata, seed i punkty odniesienia dla późniejszej wydajności.

**Wyjście:** odtwarzalna baza, lista otwartych bramek, zero ukrytych regresji.
Test dwóch zalogowanych kont jest osobnym MANUAL_PENDING; zgodnie z decyzją
2026-09-20 brak kont nie blokuje autonomicznego zamknięcia P10/P12.

### P01 — granice modelu Behavior

- [x] P01.1 Zastąpić dostęp warunków do NpcFacade odczytowym kontekstem z UUID,
  jednym snapshotem i ograniczonymi obserwacjami. Oczyszczanie pamięci celu przenieść
  do jawnej fazy aktualizacji stanu poza evaluate. Testować niezmienność pamięci
  i brak wywołań modyfikujących przy wielokrotnej ocenie tych samych warunków.
- [x] P01.2 Kompilować argumenty do konkretnych wartości/typów i rozwiązywać definicje
  przy ładowaniu. Oddzielić wejściowy JSON od niezmiennego modelu wykonawczego;
  przygotować stały porządek i klucze cooldownów raz. Zachować identyczne znaczenie
  istniejących paczek lub udokumentować wymaganą migrację.
- [x] P01.3 Przejrzeć każdą zarejestrowaną akcję i zadeklarować wszystkie kanały,
  które faktycznie wykorzystuje. Intencja wielokanałowa otrzymuje wszystkie kanały
  albo żaden. Rozstrzygnąć ruch+look, używanie obu rąk, walkę, interakcje i ekwipunek.
- [x] P01.4 Ustalić znaczenie cooldownu przy ACCEPTED/RUNNING/SUCCEEDED/odrzuceniu,
  zwłaszcza dla kilku akcji jednej reguły. Nie odnawiać omyłkowo cooldownu bez końca.
- [x] P01.5 Testować stabilne remisy, zmienioną kolejność paczek, konflikt LOOK,
  brak skutków odrzuconych intencji i identyczne decyzje z identycznego wejścia.

**Wyjście:** warunki odczytowe z konstrukcji API, typowany model i rzeczywiste
pokrycie używanych kanałów. Regresje istniejących built-inów przechodzą.

### P02 — kontrakt i mechaniki Core

- [x] P02.1 Zestawić akcje natychmiastowe i długie. Dla każdej długiej akcji określić
  identyfikator, stan, kanały, wygaśnięcie sterowania, sposób kontynuacji/anulowania
  i jeden wynik terminalny. Przyjęcie żądania nie oznacza osiągnięcia celu.
- [x] P02.2 Sprawdzić cykl nawigacji do wskazanego punktu: osiągnięcie, brak postępu,
  zmiana celu, stop i utrata sterowania. Raport nie wybiera następnego celu za Behavior.
- [x] P02.3 Sprawdzić konflikty między kopaniem, ładowaniem broni, użyciem przedmiotu,
  zmianą wyposażenia i ruchem. Ponownie walidować aktualny zasięg, cel, przedmiot
  i mechaniczne uprawnienia w odpowiednich punktach długiej akcji.
- [x] P02.4 Przetestować zniknięcie celu, pęknięcie/zamianę narzędzia, pełny plecak,
  częściowy transfer, usunięcie skrzyni i odrzucone ustawienie bloku. Zasoby i trwałość
  są rozliczane zgodnie z faktyczną czynnością, bez duplikacji i podwójnego zużycia.
- [x] P02.5 Zweryfikować mechaniki gracza wymagane manifestem: melee, tarcza,
  przedmioty zużywalne, łuk/kusza/trójząb, kopanie, stawianie i interakcje.
  Sprawdzić odpowiednie enchantmenty, loot/XP i zdarzenia Forge. Dla nieobsługiwanego
  dodatku zwracać jawny UNSUPPORTED; adapter dodawać przy konkretnym potrzebnym przypadku.
- [x] P02.6 Oddzielać kontrolery akcji wewnątrz Core tylko wraz z udowodnionym
  zachowaniem: kopanie, używanie przedmiotu, broń dystansowa, transfer/pickup.
  Zachować jeden autorytatywny stan przedmiotu i alias głównej ręki do hotbara.
- [x] P02.7 Potwierdzić ciało Core-only: summon → idle → ręczne sterowanie/ekwipunek
  → zapis/restart → odczyt → dismiss/death. Zweryfikować skiny summonerów,
  classic/slim, warstwy, refresh i fallback odpowiednimi testami klienta.
- [x] P02.8 Sprawdzić pakiety i GUI wobec obcego NPC, innego wymiaru, zbyt dużej
  odległości, nieaktualnego stanu i zniknięcia celu. Walidować typy/rozmiary danych
  oraz uprawnienia summoner/operator na serwerze, także przy ponownym otwarciu GUI.

**Wyjście:** mały, jawny kontrakt wykonania; normalne akcje mają dowody z GameTestów
i klienta. Zmiana mechaniki nie wprowadza do Core zamiaru ani polityki autonomicznej.

### P03 — obserwacje i postęp

- [x] P03.1 Uzupełniać NpcWorldView/NpcSnapshot o fakty potrzebne konkretnym testom:
  kolizja stanowiska, podparcie, wynik raycastu, stan narzędzia, pojemność i stan akcji.
  Brak dostępnej obserwacji odróżnić od potwierdzonego braku obiektu.
- [x] P03.2 Ustalić granice liczby/radiusu obserwacji i wspólny odczyt dla reguł
  jednej decyzji. Nie wymuszać ładowania świata przez dowolne zapytanie sensoryczne.
- [x] P03.3 Rozwinąć istniejące watchdogi o pomiar rzeczywistego postępu: odległość,
  postęp akcji lub zmianę zasobów. Drganie kolizyjne nie zeruje w nieskończoność limitu.
- [x] P03.4 Wprowadzić ograniczony rekord decyzji: zadanie, faza, cel, wybrana reguła,
  wynik akcji, przyczyna czekania i liczba prób. Pełne napisy tworzyć przy odczycie
  diagnostyki lub ograniczonym logowaniu. Wyłączony debug ma mierzalnie mały koszt.
- [x] P03.5 Dodać liczniki zapytań, ocen reguł, intencji i postępu zadań. Oddzielić
  koszt decyzji Behavior od fizyki i nawigacji Core oraz kosztu samego świata.

**Wyjście:** Behavior może odróżnić pracę, oczekiwanie i utknięcie; każda obserwacja
ma ograniczenie, a diagnostyka wskazuje konkretną przyczynę zatrzymania.

**Zamknięcie 2026-09-10:** P03.1–P03.5 PASS. Zachowany końcowy clean build:
82 JUnit (zero błędów/pominięć), 78 Core + 21 Behavior GameTestów, realny klient drwala
i zwykły serwer trzech modów. Wcześniejsza próba P03 potwierdziła również regresję lasu.
Oba guardy zależności i kontrola dokładnie trzech JAR-ów Java 17 przeszły ponownie
przy domykaniu dokumentacji. Powiązanie każdego punktu z kodem/testem, hashe i zakres
pomiaru 24 000 decyzji: [raport P03](../autonomy/development-20260910-P03/closeout.md).
W tej sesji domykającej dokumentację nie wykonywano ponownego testu gameplay.

### P04 — zadania i ich trwały stan

Kontrakt: [ADR 0025](adr/0025-durable-tasks-and-bounded-behavior-composition.md).
Mapa P04.9: [Core operation coverage](CORE_OPERATION_COVERAGE.md). Pierwszy przekrój
ma 91 JUnit, 24 Behavior GameTesty, clean build, klienta drwala i zwykły serwer PASS;
pozostałe checkboxy nie są zamknięte za samą implementację lifecycle intencji.

- [x] P04.1 Zapisać ADR kontraktu zadania. Oddzielić niezmienną definicję/parametry,
  trwały zamiar i postęp, przejściowe wykonanie oraz końcowy raport.
- [x] P04.2 Zacząć od jednego zadania głównego na NPC i ograniczonego przerwania
  z powrotem. Zaproponowany limit zagnieżdżenia wynosi dwa poziomy; zwiększać go tylko
  po wykazaniu potrzeby. Nie zapisywać stosu wywołań ani obiektów świata.
- [x] P04.3 Określić stany uruchomione/czekające/wstrzymane/ukończone/anulowane/nieudane
  oraz powody. Czekanie ma termin lub zdarzenie wznowienia; próby mają limit i backoff.
  Wcześniej wykonane skutki świata pozostają po anulowaniu i są wykazywane w raporcie.
- [x] P04.4 Skorelować akcje Core z zadaniem. Utrata kanałów, przypisania lub paczki
  kończy/odwołuje odpowiednie sterowanie. Zwykłe zwrócenie z ticka nie jest procedurą stop.
  Nie przyjmować spóźnionego wyniku za rezultat innego, nowszego zadania.
- [x] P04.5 Wersjonować zapis zadania i przypisań, z migracją obecnych danych.
  Utrwalać UUID/wymiar/pozycję/parametry i konieczny postęp; trasy, rezerwacje czasowe,
  uchwyty runtime i cache odtwarzać z aktualnych faktów.
- [x] P04.6 Po restarcie uzgadniać świat i ekwipunek przed ponowieniem transferu,
  kopania lub ustawienia podpory. Zwykły save Minecrafta nie jest globalną transakcją;
  testować spójny restart i bezpieczne wyjście przy niezgodności zapisów.

- [x] P04.7 Ustalić typowane parametry operacji: wersjonowana definicja, filtr zasobów,
  obszar/wymiar/wyłączenia, źródło/cel, ilość lub ograniczone cykle, zasady narzędzi.
  Walidować je przez wspólny kontrakt Behavior. Zapewnić ręczne assign/status/pause/
  resume/cancel przez udokumentowane komendy, bez zależności od LLM lub kreatora GUI.
- [x] P04.8 Rozliczać zebrane, zużyte, zachowane i dostarczone zasoby oraz niedobory.
  Ustalić lokalne kroki recovery i zaopatrzenia z dozwolonych źródeł, z limitem prób,
  zagnieżdżenia i czasu. Brak LLM kończy się określonym wait/partial failure, bez pętli.
- [x] P04.9 Powiązać prymitywy wymienione w OPERATIONS z istniejącym API Core i testami:
  ruch/obrót/skok/stop, wybór slotu/wyposażenia, atak oraz użycie/zwolnienie przedmiotu.
  Brakującą mechanikę zapisać jako konkretną zależność odpowiedniego P05–P07 i uzupełnić
  tam w Core. Nie dodawać obecnie dostawcy LLM, adaptera ani jego przejmowania kanałów.

- [x] P04.10 Uwzględnić w ADR zadania kompozycję B01–B15: wyzwalacz, typowane parametry,
  pamięć, kanały, priorytet, limit czasu/prób, wynik i procedurę przerwania/wznowienia.
  Warunki są czyste, selekcja celu nie jest jeszcze atakiem. Reakcje używają wspólnego
  runtime i istniejącego limitu zagnieżdżenia; lifecycle obejmuje utratę przypisania/reload.

**Wyjście:** jedno małe zadanie można zatrzymać, wznowić i zakończyć z jednoznacznym
raportem; brak pętli bez granic i odtwarzania starego sterowania po restarcie.
Kontrakt obejmuje katalog operacji; istniejące dane/paczki zachowują migrację i walidację.

### P05 — drwal: gatunek, obszar i ilość

- [x] P05.1 Zamienić demonstrację w jawnie uruchamiane zadanie z obszarem, docelowym
  pojemnikiem i wymaganą ilością dostarczonego drewna. Przykład akceptacyjny: minimum
  64 kłody. Ilość w plecaku i ilość dostarczona to różne liczniki.
- [x] P05.2 Ustalić politykę kończenia rozpoczętego drzewa i nadwyżki. Domyślna
  propozycja: bezpiecznie dokończyć rozpoczętą pracę pionową, zebrać materiał,
  dostarczyć go i wykazać rzeczywistą nadwyżkę ponad minimum. Utrwalić to w ADR P04.
- [x] P05.3 Zachować istniejące mechanizmy topologii drzewa, stanowisk, skoku,
  rusztowania, zbierania i powrotu. Refaktoryzować etapami z zachowaniem regresji lasu,
  pniaka, zagnieżdżonego odzyskiwania materiału i zachowania liczby kłód.
- [x] P05.4 Przetestować pełny plecak, pełną/zniszczoną skrzynię, brak narzędzia,
  ostatni punkt trwałości, brak podpór, usunięty cel i zablokowane dojście.
  Respektować ustawienia Ignore missing tool/Bare hands only, trwałość i inne opcje Core.
- [x] P05.5 Ograniczyć ingerencję do uprawnionego obszaru i jawnej polityki pracy.
  Sprzątać wyłącznie rozpoznane własne tymczasowe ustawienia zadania; obcy blok
  podstawiony w miejsce podpory nie może zostać automatycznie uznany za tę podporę.
- [x] P05.6 Zaliczyć ilościowy scenariusz w GameTest i prawdziwym kliencie na
  naturalnym/nieregularnym terenie. Policzyć drewno, drop, zużyte podpory i resztki.

- [x] P05.7 Dodać wybór gatunku/zestawu drewna przez zarejestrowane ID/tagi oraz filtr
  uwzględniony w wyborze drzewa i rozliczeniu ilości. W mieszanym lesie zbierać wyłącznie
  wybrany gatunek. Udokumentować i przetestować oferowane gatunki/topologie, w tym
  dąb/brzozę i obsługiwany wariant 2×2; nie deklarować wsparcia bez próby w świecie.
- [x] P05.8 Udostępnić wybór obszaru przez narożniki lub środek/promień, wymiar,
  wyłączenia i skrzynię odbiorczą. Przetestować drzewa poza granicą i przecinające granicę;
  dokończenie drzewa nie znosi ograniczeń obszaru. Opisać ograniczenia rozpoznawania
  drzew wobec bloków ustawionych przez graczy. Opcję ponownego sadzenia włącza P07.20.

**Wyjście:** zadanie osiąga jawny wynik z wybranego drewna i obszaru albo kończy
z rozliczonym wynikiem częściowym i przyczyną. Nie zależy od ręcznego cancel jako
sposobu wybudzania NPC. Powtórne sadzenie pozostaje zależną bramką P07, bez pustej opcji.

### P06 — follow, defend, attack i przerwania

- [x] P06.1 Dopracować follow z martwą strefą, zmianą wysokości, przeszkodą i utratą
  summoner w dostępnym świecie. Rozłączyć brak obserwacji od polecenia teleportacji.
- [x] P06.2 Dopracować obronę/retaliację wobec wybranego celu: zasięg pościgu,
  uprawnienia, ważność celu, moment zakończenia i jawny powrót do zadania.
- [x] P06.3 Zdefiniować priorytety przerwania pracy i procedury oddania kanałów.
  Zapobiec oscylacji praca↔walka co tick, zachowując reakcję na zagrożenie.
- [x] P06.4 Reakcje wynikające z decyzji o leczeniu, schowaniu się, założeniu tarczy
  utrzymać w Behavior; Core dostarcza fakty i mechanikę użycia. Automatyczna ochrona
  totemem z osobnego slotu jest mechaniką Core zgodnie z życzeniem użytkownika i ADR 0038.
  Na wyraźne życzenie użytkownika z 2026-09-11 totem w rezerwie chroni automatycznie
  jako ułatwienie mechaniczne Core (ADR 0038); nie wymaga decyzji Behavior.
- [x] P06.5 Testować przerwanie podczas dojścia, kopania, transferu i rusztowania.
  Fizyka już trwającego skoku jest uwzględniona w stanie; cleanup może wymagać
  późniejszego bezpiecznego kroku, który pozostaje widoczny w raporcie zadania.
- [x] P06.6 Wszystkie built-iny uruchamiać przez wspólną ocenę i arbitraż.
  Nowo przywołany NPC pozostaje idle do jawnego przypisania zachowania.

- [x] P06.7 Udostępnić defend jako parametryzowaną operację: osłona summoner,
  wskazanego NPC oraz obrona punktu/obszaru. Określić dozwolone zagrożenia, granicę
  pościgu, czas/warunek zakończenia i miejsce powrotu; nie utrwalać referencji encji.
- [x] P06.8 Dla defend rozwinąć wybór dostępnego wyposażenia, dystansu i wycofania
  według zdrowia/amunicji. Testować warianty, zniknięcie chronionego celu i przejście
  poza obszar; obrona nie może przerodzić się w nieograniczony pościg.
- [x] P06.9 Dodać osobne jawne attack: wskazany cel albo dozwolone typy celów
  w obszarze, quota/termin, leash, preferencja melee/ranged i próg wycofania.
  Chronić summoner/sojuszników zgodnie z uprawnieniami; atakowanie graczy wymaga
  jawnej dozwolonej polityki. Dojście i atak nie nadają nowych uprawnień do świata.
- [x] P06.10 Zaliczyć pełne defend/attack w GameTest i kliencie: cele spoza filtra,
  zasięg, brak amunicji, utrata celu, ukończenie, anulowanie, przerwanie i powrót
  do pracy. Sprawdzić raport rzeczywistych wyników oraz brak walki po oddaniu kanałów.

- [x] P06.11 Dodać współdzielone wybieranie celu: najbliższy pasujący mob, wskazany UUID,
  filtr typu/tagu, napastnik NPC lub chronionej jednostki. Uwzględnić obszar, widoczność,
  osiągalność i uprawnienia, stabilne remisy oraz pamięć celu. Nie zmieniać celu co tick
  z powodu małej różnicy odległości; brak wskazanego celu nie zezwala na dowolny zamiennik.
- [x] P06.12 Dopracować odwet jako reakcję na nowe zdarzenie obrażeń, obsługiwane raz.
  Ciągłe trafienia nie mogą blokować kontrataku przez ciągłe przejmowanie kanału samą
  selekcją celu. Udostępnić jawne polityki: pasywny, odwet, ochrona, atak w obszarze;
  respektować summoner/sojusze i idle przed przypisaniem. Inspiracja: Recruits w SIMILAR_MODS.
- [x] P06.13 Zrealizować osobne, łączone B05–B07: dobór wyposażenia z realnego ekwipunku,
  utrzymywanie przedziału dystansu i wycofanie/leczenie z różnymi progami wejścia/powrotu.
  Ograniczyć częstotliwość wyboru, chronić używany przedmiot i nie tworzyć zastępczej broni.
  Weryfikować broń/amunicję, widoczność, stanowisko, granice i faktyczny efekt leczenia.
- [x] P06.14 Dodać patrol po ograniczonej liście punktów z postojem, liczbą obiegów/terminem
  i wybraną polityką reakcji. Pomoc summoner rozdzielać na ochronę przed napastnikiem
  oraz wsparcie wskazanego celu. Powrót do patrolu używa aktualnej, sprawdzonej pozycji.
- [x] P06.15 Zaliczyć macierz komponentów i pełny scenariusz drwal → trafienie → wyposażenie
  → walka/odwrót → ponowna obserwacja → wznowienie pracy. Ciągłe obrażenia, kilku wrogów,
  utrata celu, skończone leczenie, stop/reload i restart nie mogą powodować oscylacji,
  duplikacji ani powrotu starego sterowania. GameTest oraz prawdziwy klient/server.

**Wyjście:** NPC reaguje na zmianę sytuacji i wraca do poprawnie sprawdzonej pracy;
follow, warianty defend i attack oraz komponenty przypisane do P06 w BEHAVIOR_COMPONENTS
działają bez LLM. Pozostałe komponenty mają bramki w P07/P08. Istnieje test konfliktu całego
zadania z walką, a nie tylko pojedynczych intencji.

### P07 — transport i kolejne kompletne operacje

Realizować kolejno transport → mining → food gathering → farming → planting trees.
Powiązane przekroje łączyć w O2/O3 zgodnie z OPTIMIZED_EXECUTION_PLAN.
Każda operacja zachowuje własny rzeczywisty scenariusz i rozliczenie efektów; wspólny
clean build i klient/serwer w O4 zamykają wspólny milestone. Naprawić wykryte regresje przed zależnymi zmianami.
Oznaczenia wariantów i wymagania szczegółowe opisuje [OPERATIONS](OPERATIONS.md).

- [x] P07.1 Wprowadzić zadanie przeniesienia ograniczonej liczby wskazanych
  przedmiotów między wskazanymi pojemnikami, z raportem rzeczywistego dostarczenia.
- [x] P07.2 Współdzielić z drwalem sprawdzone dojście do stanowiska, transfer,
  pomiar postępu, powrót i wznawianie. Oddzielić wybór drzewa od wspólnego wykonania.
  Oddzielić źródła towaru i zaopatrzenia od odbiorców; alternatywy są jawnie dozwolone
  i wybierane w granicach zadania, z rozróżnieniem preferencji i twardego ograniczenia.
- [x] P07.3 Odczyt zawartości skrzyni jest obserwacją; transfer ponownie sprawdza
  rzeczywistą zawartość i pojemność. Częściowy transfer jest jawnym wynikiem.
- [x] P07.4 Przetestować opróżnione źródło, zapełniony cel, zmianę pojemnika,
  przedmiot niepasujący do filtra oraz przerwanie przy obu końcach trasy. Zaliczyć
  korektę ilości/odbiorcy w trakcie transferu, stare/powtórzone zlecenie oraz zmianę
  celu zasobowego bez zaliczania starych przedmiotów do nowego celu.
- [x] P07.5 Na podstawie drwala i transportu zamknąć minimalny wspólny kontrakt
  wykonania, bez kopiowania kontrolera zawodu. Kolejne punkty tego etapu wykorzystują
  sprawdzone fragmenty; mining i farming są teraz wymaganym zakresem, nie odroczeniem.
  Wdrożyć wspólną ręczną korektę aktywnych zadań według OPERATIONS: pełna walidacja,
  rewizja, bezpieczny moment przyjęcia, zachowane skutki/budżet, jawne rozliczenie
  nowego celu i odrzucenie niedozwolonej zmiany. Istniejące i kolejne operacje używają
  tego samego mechanizmu; ich walidatory określają wspierane parametry i zmiany.
- [x] P07.6 Dodać mining wskazanych odsłoniętych rud i ograniczonej połączonej żyły:
  filtr, obszar/wymiar/Y, ilość, narzędzia, odbiorca. Wybrać osiągalne stanowisko,
  zbierać faktyczne dropy i dostarczać je bez skanowania niezaładowanych chunków.
- [x] P07.7 Dodać tunel o jawnych wymiarach/kierunku i granicach. Oddzielić docelowy
  zasób od bloków dozwolonych do usunięcia na dojściu; kontrolować powrót i podparcie.
- [x] P07.8 Dodać ograniczony wykop objętościowy z kolejnością pracy, dozwolonym
  zakresem bloków i bezpiecznym dojściem. Nie kopać poza zleceniem ani pod sobą bez
  sprawdzonej procedury; kolejne wybory wynikają z aktualnych obserwacji.
- [x] P07.9 Przetestować mining wobec płynów, spadających/niezniszczalnych bloków,
  utraty narzędzia, braku podparcia, pełnego plecaka i zniszczonego odbiorcy.
  Nieobsługiwany przypadek ma ograniczony stop/wait i wynik częściowy.
- [x] P07.10 Zaliczyć każdy wariant mining w integracji oraz pełną operację w kliencie:
  granice wykopu, zmiana celu, dropy/XP/trwałość, transfer, przerwanie i cleanup.
  Brak potrzebnej obserwacji/mechaniki uzupełnić w Core, z polityką wyboru w Behavior.
- [x] P07.11 Dodać food gathering: zbieranie filtrowanych jadalnych dropów i dojrzałych,
  obsługiwanych zasobów dzikich. Rozdzielić ilość do dostarczenia od własnego zapasu.
- [x] P07.12 Dodać pobranie żywności z dozwolonego pojemnika oraz jawny wariant polowania
  wykorzystujący attack i zbiór dropów. Samo zbieranie jedzenia nie włącza polowania;
  cele i obszar są ograniczone, a zjedzony przedmiot nie liczy się jako dostarczony.
- [x] P07.13 Zaliczyć warianty food gathering w świecie/kliencie: brak żywności,
  niedojrzałe zasoby, niedozwolony cel, zmiana pojemnika, ilość i zachowany zapas.
- [x] P07.14 Dodać harvesting dojrzałych upraw wybranego typu w wyznaczonym polu.
  Fakty o dojrzałości daje Core; wybór roślin/kolejności należy do Behavior. Minimum
  macierzy oferowanych wariantów: pszenica, marchew i ziemniaki, bez niszczenia niedojrzałych.
- [x] P07.15 Dodać przygotowanie dozwolonej gleby i siew/sadzenie z prawdziwych zasobów,
  źródłem nasion i rezerwą na ponowne obsianie. Respektować warunki podłoża i interakcji.
- [x] P07.16 Złożyć zbiór + ponowne sadzenie oraz utrzymanie przez ograniczoną liczbę
  cykli. Czekanie na wzrost ma ograniczony koszt, warunek wznowienia i zakończenia;
  brak nasion lub niespełnione warunki wzrostu danego wariantu dają konkretny powód,
  a nie pozorny postęp. Nie wymagać wody tam, gdzie normalne reguły pozwalają rosnąć.
- [x] P07.17 Zaliczyć warianty farming w GameTest i kliencie: dojrzałość, granica pola,
  zużycie/rezerwa nasion, ilość plonu, transfer, przerwanie i zachowanie obsianych pól.
- [x] P07.18 Dodać planting trees: gatunek sadzonki, obszar/pozycje, odstępy, liczba,
  źródło zasobów oraz obsługiwane układy 1×1/2×2. Walidować podłoże i wolne miejsce,
  używać normalnych interakcji i realnego ekwipunku, bez tworzenia sadzonek.
- [x] P07.19 Dodać uzupełnianie dozwolonych luk w nasadzeniach. Odróżnić sadzonki,
  obsadzone układy i wyrośnięte drzewa; raport posadzenia nie obiecuje natychmiastowego
  wzrostu. Obcy blok i nieodpowiednie stanowisko pozostają nienaruszone.
- [x] P07.20 Połączyć lumberjack z ponownym sadzeniem przez tę samą sprawdzoną
  operację, z parametrami gatunku/rezerwy sadzonek i obszaru. Bez dostatecznych
  materiałów zwrócić jawny częściowy wynik; utrzymać ograniczenie zagnieżdżenia P04.
- [x] P07.21 Zaliczyć warianty planting trees i wood harvesting + replant w świecie/
  kliencie: odstępy/układy, granice, zużycie sadzonek, zajęte miejsce, braki, anulowanie
  oraz rzeczywisty wynik. Każdy oferowany gatunek/układ wymaga adekwatnego dowodu.

- [x] P07.22 Wydzielić wspólne, jawnie włączane B09–B11: uzupełnianie konkretnych potrzeb,
  odkładanie nadmiaru z zachowaniem rezerwy i pickup po drodze z limitem odejścia/czasu.
  Brak magazynu/przedmiotu, pełny cel i pełny plecak mają różne powody. Rezerwy zapobiegają
  cyklowi pobierz→odłóż→pobierz; transfer częściowy jest rozliczony, a po kroku NPC wraca
  do zadania. Zaliczyć integrację bez LLM, używając wzorców Workers z SIMILAR_MODS.

**Wyjście:** wszystkie wymagane operacje mają działające warianty i rozliczony wynik
bez LLM. Współdzielą konkretne mechanizmy wykonania, a Core nie zna zawodów. Każda
operacja ma test pełnego zadania; sama obecność prymitywu break/place nie zamyka zakresu.

### P08 — wiele NPC

- [x] P08.1 Zbieranie i przyznawanie rezerwacji pracy rozpatrywać w jawnej kolejności,
  z ograniczonym czasem życia i uczciwym ponawianiem. Pierwszy callback encji nie
  może przypadkowo wyznaczać trwałego zwycięzcy konfliktu.
- [x] P08.2 Określić tożsamość rezerwacji: NPC, zadanie, wymiar, obszar/cel i termin.
  Nie łączyć rezerwacji logistycznej z pozwoleniem na modyfikację świata.
- [x] P08.3 Zachować walidację transferu w Core przy konkurujących NPC i graczu.
  Rezerwacja Behavior nie gwarantuje, że zawartość skrzyni pozostanie niezmieniona.
- [x] P08.4 Testować trzech drwali przy sąsiednich drzewach, dwóch transportujących
  przy jednej skrzyni, śmierć uczestnika oraz dwóch summonerów z osobnymi uprawnieniami.
- [x] P08.5 Ograniczyć łączny budżet zapytań i planowania serwera, z rozłożeniem
  kosztownych decyzji na ticki. Fizyka i trwająca akcja nadal zachodzą poprawnie.
  Przy nadmiarze pracy odraczać kosztowną decyzję z diagnostyką.

- [x] P08.6 Przetestować mieszane operacje: dwaj górnicy przy tej samej żyle, rolnicy
  przy wspólnym plonie/nasionach oraz drwal i sadzący na tym samym obszarze. Rezerwacje
  nie pozwalają usunąć nowego nasadzenia ani wydać tych samych zasobów dwa razy.

- [x] P08.7 Dodać B13: ograniczone ustąpienie z zajętego przejścia/stanowiska na dozwoloną,
  bezpieczną pozycję, z terminem i stabilnym wyborem pierwszeństwa. Dwóch NPC nie może
  ustępować sobie bez końca; po zwolnieniu drogi odtwarzać zamiar. Test wąskiego przejścia
  i pracy przy magazynie z zachowaniem rezerwacji i granic obszaru.

**Wyjście:** brak duplikacji i wiecznego zagłodzenia uczestnika, zwalniane rezerwacje,
odtwarzalne rozstrzygnięcia oraz zrozumiałe oczekiwanie na zajęty zasób.

### P09 — lifecycle, wydajność i odporność

- [x] P09.1 Wykonać save/stop/start w osobnych JVM w reprezentatywnych fazach każdej
  operacji z OPERATIONS, w tym zbioru, sadzenia, transferu i walki. Odróżnić kontrolowane zapisanie od awarii; w razie niezgodności stanu
  bezpiecznie zatrzymać zadanie i pokazać przyczynę zamiast powielać skutki. Pokryć
  przyjętą korektę definicji, jej rewizję i rozliczenie oraz brak powtórzenia starej
  akcji po restarcie; podać dowód dla zmienionej ilości, odbiorcy i celu zasobowego.
- [x] P09.2 Sprawdzić usunięcie/śmierć NPC, rozłączenie summoner, zmianę wymiaru,
  unload świata i politykę chunków. Usunąć przejściowe uchwyty, akcje i rezerwacje;
  trwałe przypisania zachować/usunąć zgodnie z rodzajem zdarzenia, nie przypadkiem.
- [x] P09.3 Wykonać reload podczas pracy: nieudany kandydat zachowuje cały dobry
  rejestr; udany reload zmienia generację w kontrolowanym punkcie. Zadanie wiąże się
  ze znaną wersją definicji lub przechodzi jawną migrację/stop. Brak paczki daje idle
  oraz anulowanie sterowania i ograniczoną diagnostykę.
- [x] P09.4 Dodać powtarzalną próbę 1/8/32/64 NPC z tą samą sceną i ustawieniami.
  Mierzyć medianę/p95/p99 czasu ticka, koszt decyzji, liczbę zapytań, alokacje,
  rozmiar pamięci zadań oraz czas dojścia do wyniku. Osobno próba idle i aktywnej pracy.
- [x] P09.5 Po pomiarze bazowym ustalić w ADR liczbowy budżet na sprzęcie testowym,
  przed oceną końcowej optymalizacji. Przykładowy cel do zweryfikowania: 32 aktywne NPC,
  20 TPS i p95 całego ticka poniżej 50 ms w zapisanej scenie. To cel, nie obecny wynik.
  Dla większej liczby potwierdzić ograniczony koszt i kontrolowane odraczanie pracy.
- [x] P09.6 Wykonać co najmniej 30 minut próby mieszanych zadań i cykle
  summon/usunięcie. Sprawdzić brak narastania zatrzymanych referencji i nieograniczonych
  map/logów. Koszt optymalizować na podstawie pomiaru; bez per-tick wątków i korutyn.

**Wyjście:** konkretna koperta wydajności z podanym sprzętem i sceną, odtwarzalny
restart, transakcyjny reload oraz rozliczone zakończenie życia NPC i zadań.

### P10 — bramka dojrzałości kodu

Określenie „state of the art” jest ambicją jakościową. Ten plan mierzy dojrzałość
poniższymi właściwościami; nie deklaruje przewagi nad zewnętrznymi systemami AI.

- [x] P10.1 Core-only jest użytecznym, ręcznie sterowanym ciałem, a Core+Behavior
  wykonuje wszystkie operacje i wymagane warianty z OPERATIONS, komponenty
  BEHAVIOR_COMPONENTS oraz companion packi bez LLM. Sprawdzić brak JAR-a LLM i obecność nieskonfigurowanej powłoki; żaden krok
  rutynowej pracy/recovery nie wymaga modelu.
- [x] P10.2 Dla każdego długiego działania istnieją sprawdzone sukces, anulowanie,
  utrata warunków i restart; nie ma nieograniczonych prób ani ukrytej kontynuacji.
- [x] P10.3 Ten sam zapis wejścia daje ten sam wybór intencji i rezerwacji;
  kanały, priorytety i granice pamięci są testowane.
- [x] P10.4 Zachowanie przy błędach jest czytelne dla użytkownika. Dostępny jest
  lokalny zestaw scen regresji obejmujący świat rzeczywisty i współpracę NPC.
- [ ] P10.5 Skin/GUI/permissions/lifecycle i zwykły serwer z trzema modami mają
  odpowiednie dowody. Nie zastępować autentycznego testu skórek generowanym profilem.
  Test skórek dwóch kont: MANUAL_PENDING, poza blokującym zakresem autonomicznym;
  jego wynik wykazać osobno w raporcie. Pozostała prezentacja zachowuje P2;
  permissions, lifecycle, loading i operacje GUI
  zachowują krytyczny priorytet i wymagają regresji, kiedy zmienia się ich mechanizm.
- [x] P10.6 Budżet wydajności jest spełniony, a mechaniki zachowują zasoby i reguły gry.
- [ ] P10.7 Wszystkie wymagania akceptacji poza odłożoną finalizacją zewnętrznych
  paczek są odwzorowane na aktualne dowody. Istniejące walidacja/reload również działają.
  Ręczny test skórek odwzorować jako MANUAL_PENDING, bez blokowania zamknięcia
  zakresu autonomicznego i bez przypisywania mu PASS.

**Wyjście po aktualizacji 2026-09-12:** krytyczne mechanizmy i ich dowody są warunkiem
finalizacji P11/Z6; ręczne kryteria prezentacji P2 nie blokują tej pracy. P10.5/P10.7
pozostają niezaliczone w brakującym zakresie. Acceptance Zoo rozszerza scenariusze
infrastruktury o łowienie, eksplorację i maszyny; nie tworzy automatycznie nowych
publicznych profesji. Ogólny planner, uczenie, provider LLM i kreator GUI są odłożone.

### P11 — zewnętrzne paczki JSON

- [x] P11.1 Zinwentaryzować faktycznie sprawdzone akcje, warunki, zadania i parametry.
  Zdefiniować ich stabilne, wersjonowane ID, zakresy, kanały, rezultaty i domyślne wartości.
  Objąć katalog OPERATIONS oraz B01–B15 z BEHAVIOR_COMPONENTS, ich warianty, filtry
  gatunków/zasobów i wybór obszaru; oddzielić
  sprawdzone prymitywy Core od przyszłego adaptera i protokołu LLM.
- [x] P11.2 Ustalić końcową wersję schematu i migrację używanego wcześniej formatu.
  Oddzielić wersję dokumentu, semantyki definicji i zapisu runtime. Nie zmieniać
  znaczenia istniejącego ID po cichu.
- [x] P11.3 Ujednolicić wbudowane i zewnętrzne wejście: parser → schema → semantyka
  → kompilacja → pełny kandydat → aktywacja. Zapewnić zgodność kontraktu JSON Schema
  z walidatorem rzeczywiście używanym w runtime i testy tej zgodności.
  Dowód P11.2/.3: ADR 0076–0078; kampanie N/Q/R3/S. Dokument i semantyka v1,
  niezależny zapis runtime v9 z migracjami; 54 identyczne dokumenty oraz 16 paczek
  wbudowanych przez runtime i niezależny walidator obu schematów. Aktywacja pozostaje
  transakcyjna; ograniczenia semantyczne opisane w BEHAVIOR_AUTHORING.
- [x] P11.4 Zachować jawne odrzucanie duplikatów jako domyślną semantykę. Override
  dodawać tylko z dokładnym kontraktem i testem kolejności, nigdy przez przypadek.
  Dowód: ADR 0075, `autonomy/run-20260912-zoo/p11-input-n-evidence.json`; duplikaty pól,
  identyfikatorów i odrzucony live reload zachowują ostatni poprawny stan.
- [x] P11.5 Sprawdzić limity rozmiaru/liczby plików, głębokości, liczby reguł,
  ciągów i argumentów przed kosztownym przetwarzaniem. Ścieżki ograniczyć do katalogu
  serwera; sprawdzić linki, niekompletne zapisy, niedostępny plik i wadliwe kodowanie.
- [x] P11.6 Sprawdzić zły JSON, nieznane/pominięte pola, wersję, ID, niedozwolone
  kanały i parametry; last-known-good pozostaje aktywny przy odrzuceniu kandydata.
  Komunikaty podają plik/paczkę/regułę/pole, a nie jedynie ogólny wyjątek.
  Dowód P11.5/.6: `autonomy/run-20260912-zoo/p11-input-acceptance.json`; kampanie N/Q,
  rzeczywiste blokady i junctiony Windows oraz zachowanie aktywnego taska przy 10 błędnych reloadach.
- [x] P11.7 Udostępnić przez publiczne Behavior API walidację, katalog parametrów
  i możliwości oraz zlecanie/korektę/pause/resume/cancel/status z typowanym raportem.
  Opublikować sprawdzony mechanizm P07.5 z tą samą autoryzacją i walidacją, bez importu
  runtime internals przez klientów API. Katalog podaje wersje, limity, wartości,
  zależności pól i możliwe korekty. Przyszłe GUI/LLM używają tej samej granicy;
  brak nowej ścieżki do bezpośredniej mutacji świata lub uruchamiania kodu.
- [x] P11.8 Przygotować działające przykłady, instrukcję autora, błędne przykłady
  z oczekiwanymi komunikatami oraz realny test edycja → reload → zachowanie NPC.
  Dowód: p11-author-t-evidence.json, 355 jednostkowych i 19 lifecycle; ten sam NPC
  zatrzymuje się na 7,97 bloku, po edycji stopDistance 8→2 i reloadzie podchodzi na 1,93.

**Wyjście:** autor zewnętrznej paczki korzysta z ustabilizowanego katalogu i otrzymuje
przewidywalne wyniki walidacji/migracji. Pełne wymaganie external JSON jest ukończone.

### P12 — lokalna akceptacja końcowa

- [ ] P12.1 Przejść każdą pozycję ACCEPTANCE_CRITERIA i mapowanie z P00; dołączyć
  dokładne polecenia i dowody. Wymagany punkt BLOCKED pozostaje widoczny i ogranicza
  deklarację gotowości. Nie ogłaszać pełnego ukończenia z otwartą bramką.
  Wyjątek użytkownika 2026-09-20: test skórek dwóch kont raportować jako MANUAL_PENDING;
  nie jest blokującą bramką ukończenia autonomicznego zakresu P12.
- [ ] P12.2 Wykonać clean build w czystej lokalnej kopii źródeł, wszystkie potrzebne
  testy i start dedykowanego serwera oraz klienta ze wszystkimi trzema modami.
- [ ] P12.3 Potwierdzić dokładnie trzy własne JAR-y SAMCNPC, Java 17, piny,
  brak sterowników smoke w produkcji i brak klientowych klas na ścieżce serwera.
  Zwykła zależność Kotlin pozostaje zgodna z obowiązującą dystrybucją.
- [ ] P12.4 Zapisać ścieżki i SHA-256 artefaktów, kompatybilność save/paczek,
  procedurę instalacji i odtworzenia testów oraz znane ograniczenia.
- [ ] P12.5 Zamknąć lokalny raport i wskazać sensowne następne rozszerzenie.
- [ ] P12.6 Po zamknięciu wymaganej pracy zsynchronizować sprawdzone źródła do
  istniejących repozytoriów Core i Behavior zgodnie z ADR 0009/0010 i najnowszą zgodą.
  Zweryfikować standalone buildy, normalne commity/pushe, Core gitlink w Behavior
  i dokładne hashe zdalnych main. Zachować historię i wyłączyć z publikacji lokalne
  raporty/światy/plan. Dopiero po zapisaniu końcowego raportu i pustym aktywnym backlogu
  zatrzymać testowe procesy, zaktualizować heartbeat i wykonać zamówione wyłączenie.
  Przed komendą poinformować o opóźnieniu i anulowaniu przez shutdown /a.
  Sam upływ 20–30 minut nigdy nie spełnia warunku ukończenia.

### F01 — przyszły kreator GUI

Status DEFERRED — daleka przyszłość, poza bieżącą autonomiczną pracą.
W przyszłym, osobnym zakresie można rozważyć kreator korzystający
z katalogu definicji: wybór zadania/warunku/akcji, formularz ograniczonych parametrów,
podgląd JSON, walidacja i jawny zapis/reload. Samo przygotowanie metadanych w P11
nie zobowiązuje do realizacji GUI. W pierwszej kolejności sprawdzić, jakie operacje
użytkownik rzeczywiście wykonuje przy tworzeniu paczek i które potrzebują formularza.

### F02 — llm_integration: Translator, Supervisor i Planner

Status **IN_PROGRESS, 7/36 punktów; L0 ukończone**.
Pełny plan: [LLM_INTEGRATION_PLAN](LLM_INTEGRATION_PLAN.md), kontrakt:
[LLM_BOUNDARY](LLM_BOUNDARY.md), decyzja: [ADR 0085](adr/0085-llm-high-level-planning.md).

LLM jest warstwą planowania nad Behavior. Nie dostaje ruchu/skoku/slotów/akcji Core
ani czasowego przejęcia kanałów. Starszy wymóg sterowania prymitywami zostaje zastąpiony.
L0 finalizuje istniejący katalog operacji z P11.1/P11.7; kolejne etapy obejmują
publiczne sensory/task context, osiem typów decyzji, provider zgodny z OpenAI,
Translator, Supervisor, Planner i akceptację z rzeczywistym klientem/serwerem.

Zadania i lokalne recovery pozostają samodzielne. Inference działa zdarzeniowo,
z bounded kontekstem/pamięcią, budgets, ochroną przed pętlami i ponowną autoryzacją.
W pierwszej wersji obowiązuje aktualny połączony gracz, dimension i zasięg 256 bloków.
LM Studio/Ollama to lokalne cele testów; zgodność dodatkowych backendów wymaga dowodów.

To osobny zakres F02; P00–P12 i Zoo zachowują swoje liczniki. Test skórek dwóch kont
jest MANUAL_PENDING i nie blokuje F02. Crafting/GUI creator/MCP/fine-tuning pozostają
poza tym planem. Dodanie dokumentacji nie uruchamia implementacji ani providera.

### F03 — crafting na sam koniec, jako osobny moduł

Status DEFERRED_LAST — wyraźna decyzja użytkownika z 2026-09-11. Crafting pozostaje
pracą dla osobnego, przyszłego etapu, roboczo `samcnpc-crafting`. Nie należy do P04–P12,
O1–O6 ani warunków uruchomienia integracji LLM. Nie tworzyć teraz modułu, scaffoldingu,
wykonawcy receptur, planera autocraftingu ani API wyłącznie pod przyszły crafting.

Dzisiejszy build i akceptacja nadal wymagają dokładnie trzech JAR-ów Core/Behavior/LLM.
Przyszły osobny JAR craftingu jest zaakceptowanym kierunkiem rozszerzenia; przy jego
realizacji zaktualizować kontrakt artefaktów i granice zależności. Rozmowa o możliwej
implementacji nie uruchamia tego etapu automatycznie po zakończeniu innych prac.
Decyzja: [ADR 0040](adr/0040-crafting-deferred-to-separate-module.md).

### Mapa wejścia do źródeł

Ścieżki w tej tabeli są względne wobec katalogu głównego workspace. Nowe pakiety
zadaniowe są propozycją lokalizacji, nie istniejącym już API.

| Etapy | Istniejące miejsca do odczytania przed edycją |
|---|---|
| P00/P12 | build.gradle, settings.gradle, gradle.properties, scripts/, docs/ACCEPTANCE_CRITERIA.md |
| P01 | samcnpc-behavior/src/main/kotlin/io/samcnpc/behavior/model/, registry/, runtime/ |
| P02 | samcnpc-core/src/main/kotlin/io/samcnpc/core/api/, entity/, inventory/, client/, config/ |
| P03 | Core api/NpcWorldView.kt, api/NpcSnapshot.kt, entity/NpcEntityWorldView.kt; Behavior kernel/navigation/ |
| P04/P05 | Behavior lumberjack/model/, lumberjack/persistence/, LumberjackService.kt, kernel/; proponowany nowy pakiet behavior/task/ |
| P06 | Behavior registry/BehaviorDefinitions.kt, runtime/BehaviorTargetMemory.kt oraz zasoby built-inów |
| P07/P08 | Behavior kernel/inventory/, kernel/work/SpatialWorkClaimKernel.kt; Core API transferów, bloków i obserwacji; docs/OPERATIONS.md |
| P09 | Core activity/ i event/; Behavior runtime/, zapisy zadań i odpowiednie testy |
| P11 | contracts/behavior-pack.schema.json; Behavior registry/, api/ i zasoby paczek |

Przeczytać również odpowiednie src/test i gametest oraz instrukcje modułu.
Zachować istniejące fixture; nowy przypadek ma wykazywać konkretną właściwość lub regresję.

## 6. Polecenia i wymagane dowody

Polecenia poniżej odczytano z aktualnej konfiguracji. Uruchamiać je osobno i zapisywać
wynik każdego kroku. P00 sprawdza wrapper przez `.\gradlew.bat --version` i dostępne
taski przez `.\gradlew.bat tasks --all`.

```powershell
.\gradlew.bat :samcnpc-core:test :samcnpc-behavior:test --console=plain --no-daemon
python scripts/verify_boundaries.py
python samcnpc-core/tools/check_core_boundary.py
.\gradlew.bat clean build --console=plain --no-daemon
python scripts/verify_distribution.py
.\gradlew.bat :samcnpc-core:runGameTestServer --console=plain --no-daemon
.\gradlew.bat :samcnpc-behavior:runGameTestServer --console=plain --no-daemon
```

| Próba runtime | Istniejące zadanie Gradle | Istotny warunek |
|---|---|---|
| Core-only klient/serwer | :samcnpc-core:runClient / :samcnpc-core:runServer | Ręczne ciało, bez przypisań Behavior |
| Animacja | :samcnpc-core:runClientAnimationSmoke | Raport kończy się PASS i rzeczywiste klatki zostały sprawdzone |
| Chunk restart | :samcnpc-core:runChunkSmokeSave, następnie :samcnpc-core:runChunkSmokeLoad | Dwie JVM, ten sam testowy zapis |
| Konfiguracja | :samcnpc-behavior:runClientConfigSmoke | Core i towarzyszący raport Behavior muszą przejść |
| Drwal | :samcnpc-behavior:runClientLumberjackSmoke | Testowy świat, faktyczny raport zasobów |
| Las z zapisanej reprodukcji | :samcnpc-behavior:runClientForestRepairSmoke | Istniejący, sprawdzony -PforestSnapshot; kopia świata |
| Trzy mody | :samcnpc-llm:runClient / :samcnpc-llm:runServer | Potwierdzić faktycznie załadowane moduły; LLM bez poświadczeń |

Zwykły serwer ma dojść do gotowości i obsłużyć scenariusz; sam ekran EULA lub
uruchomienie JVM nie zalicza próby. Zamykać kontrolowanie i czekać na zapis świata.
Smoke nie może zaliczyć PASS wyłącznie na podstawie startu klienta lub wygenerowania pliku.

Nowe taski i testy z P04–P11 dopiero powstaną; ich nazwy dopisać po implementacji.
Nie wpisywać fikcyjnych poleceń do listy istniejących. Po zmianie czysto dokumentacyjnej
sprawdzić dokumenty i linki; nie raportować tego jako ukończenia milestone'u gameplay.

Szczególne dowody:

- **Skin:** dostępne, autentyczne profile dwóch różnych summonerów, aktualny wygląd,
  refresh, fallback i realny klient. Brak potrzebnych profili opisać dokładnie;
  fixture z wymyślonym profilem nie potwierdza uwierzytelnionej ścieżki skina.
- **Zasoby:** liczyć stan początkowy, wydobyte/dropnięte przedmioty, legalne zużycie,
  stan końcowy ekwipunku, pojemników i świata. Uwzględnić współdzielenie zasobów.
- **Przerwania:** sprawdzić zarówno wynik starej akcji, jak i brak jej niepożądanych
  skutków po oddaniu kanałów; rozliczyć już wykonane skutki.
- **Wydajność:** zachować scenę, liczbę NPC, ustawienia chunków, sprzęt i serię pomiarów;
  porównywać te same warunki. Nie usuwać trudnej sceny, aby uzyskać lepszy wynik.

## 7. Zapis postępu i przekazanie następnej sesji

Dla etapu używać lokalnego katalogu `autonomy/development-YYYYMMDD-Pxx/` z krótkim
raportem, dokładnymi poleceniami/logami, manifestem zmienionych plików i potrzebnymi
obrazami. Są to przyszłe ścieżki dowodów, a nie dowody już istniejące.
Jeśli istniejący runner nadpisuje raport, zachować wynik przed kolejnym uruchomieniem.

Po każdej zakończonej iteracji wpisać w PROJECT_STATE:

```text
Etap/punkt: Pxx.y
Stan: IN_PROGRESS | PASS | FAIL | BLOCKED
Zmiana zachowania i objęte pliki:
Wersja/manifest źródeł:
Testy: polecenie, wynik, liczba testów, pominięcia, ścieżka logu
Klient/serwer: scenariusz i dowód
Otwarte ograniczenia lub reprodukcja blockera:
Dokładny następny punkt i pliki do odczytania:
```

Tabela etapów otrzymuje PASS dopiero po spełnieniu kryterium wyjścia i wspólnych
bramek. Nie zaznaczać checkboxa za samą obecność implementacji, jeżeli punkt wymaga
testu w świecie. Częściowo wykonany etap zachowuje IN_PROGRESS z konkretnym miejscem wznowienia.

## 8. Bieżący punkt wznowienia

**Z1.1 — Acceptance Zoo, PLAN_READY.** Rozszerzyć istniejące testowe sceny i raporty
o kontrolowane incydenty, aktualne niezmienniki i powtarzalną reprodukcję. Kolejność
oraz pliki wejściowe podaje ACCEPTANCE_ZOO_PLAN.md. Nie uruchamiać ponownie O4 tylko
w celu rozpoczęcia pracy. P10.5/P10.7 pozostają otwarte z P2 i nie zatrzymują tej kolejki.

Stary zakres 96/112, nowe Zoo 0/23. Publikacja źródeł i prerelease JAR-ów z 2026-09-12
są ukończone; bieżące hashe i wyniki pozostają w PROJECT_STATE. Dotychczasowe źródła,
światy oraz nieudane próby zachowano. Plan nie rozpoczyna runu i nie zmienia watchdoga.
Jednorazowy reset jest zużyty. Provider LLM i crafting pozostają poza bieżącym zakresem.

### Historyczny punkt wejścia przed O1–O4

- **P00–P05 DONE; P06 IN_PROGRESS; P07–P12 NOT_STARTED.**
- **ACTIVE:** użytkownik wznowił autonomiczną implementację rozszerzonego backlogu.
- Ukończono **46 z 112 punktów; 66 pozostałych**. P04.6–P04.8 oraz P05.1/P05.2/
  P05.5/P05.6/P05.8 zamknięto po audycie kodu i wszystkich końcowych bramkach.
  Clean build, 133 JUnit, 56 Behavior GameTestów, klient zadania z trzema restartami,
  klient dawnego drwala i zwykły serwer trzech modów: PASS. Fizyczny restart podpór,
  podmieniona podpora oraz drzewa przecinające granicę/wyłączenie mają osobne próby.
- Dowody i audyt: `autonomy/development-20260911-P04/finite-restart-boundaries/evidence.json`
  i `checklist-audit.md`. Rzeczywisty klient: 64 nowe kłody dostarczone, cztery początkowe
  zachowane, zero strat/duplikacji. Stary test czasu odmowy został skorygowany i zachowany.
- P05.3/P05.4 zamknięte: osiem rzeczywistych prób trudnych warunków, 64 Behavior
  GameTesty, 82 Core GameTesty, 133 JUnit, clean build, trzy warianty klienta GUI
  (Ignore missing tool, Bare hands only, wyłączone zużycie) i serwer trzech modów PASS.
  Dowody: `autonomy/development-20260911-P05/adverse-conditions/evidence.json`.
- P05.7 zamknięte: naturalne ciemne dęby 2×2, 34/34 i 43/43 kłody dostarczone;
  poprawione stanowiska pod wiszącymi kolumnami i ograniczone oczekiwanie na lądowanie.
  Clean build, 137 JUnit, 66 Behavior GameTestów, klient 64 dąb + 43 ciemny dąb,
  wcześniejszy klient drwala i serwer trzech modów PASS. Dowody:
  `autonomy/development-20260911-P05/dark-oak-topology/evidence.json`, ADR 0033.
- P06.1 zamknięte: rzeczywiste obejście muru, trzy stopnie, martwa strefa 3/5,
  brak summoner, wznowienie obserwacji, reload i unassign. Klient: 354 ticki,
  888 klatek/481 ruchu; clean build, 142 JUnit, 67 Behavior GameTestów, serwer PASS.
  Dowody: `autonomy/development-20260911-P06/follow/evidence.json`, ADR 0034.
- P06-A (P06.2/.3/.5/.6) zamknięte: cztery rzeczywiste warianty praca → walka →
  dostawa (4/4/96/9 kłód), dziewięć przypadków cyklu walki, 82 Behavior + 93 Core
  GameTesty, 155 JUnit, clean build, klient i serwer trzech modów PASS. Poprawiono
  wznowienie rusztowania po zmianie pozycji. Dowody:
  `autonomy/development-20260911-P06/combat-interruption/evidence.json`, ADR 0036.
- Następne: **P06-B**, pierwszy otwarty P06.4; wyposażenie, obrona, atak obszarowy,
  leczenie/odwrót, patrol i pełne warianty P06.7–P06.15, następnie P07–P12.
- Bieżący dziennik: [PROJECT_STATE](../PROJECT_STATE.md), `autonomy/state/work-status.json`.
- Monitoring poprzedniego bootu jest nieaktywny; w tym wznowieniu nie został uzbrojony.
- Publikacja i końcowe wyłączenie czekają na wszystkie warunki P12.6.
- Finalizacja external JSON: **P11**. Kreator GUI: **F01, DEFERRED**.
- Katalog zadań: [OPERATIONS](OPERATIONS.md), komponenty [BEHAVIOR_COMPONENTS](BEHAVIOR_COMPONENTS.md),
  inspiracje [SIMILAR_MODS](SIMILAR_MODS.md). Integracja LLM: **F02, DEFERRED**.
