<p align="center">
  <img src="assets/samcnpc-behavior-logo.png" width="720" alt="SAMCNPC Behavior" />
</p>

# SAMCNPC Behavior

[English](README.md) | Polski

**Deterministyczne decyzje i przeładowywane paczki zachowań dla NPC podobnych do gracza — Minecraft Forge 1.20.1.**

[SAMCNPC Core](https://github.com/DasIstEin20/SAMCNPC_Core) zapewnia ciało i mechanikę NPC.
Behavior decyduje, kiedy i po co ich używać: podążać za przywołującym graczem, patrzeć na cel,
odpowiedzieć na atak lub wykonać ograniczone zadanie drwala. Steruje NPC przez publiczne API Core.

Behavior wymaga Core. Nie potrzebuje LLM, kluczy API ani usługi w chmurze.
Sama instalacja nie przypisuje automatycznie paczek zachowań nowo przywołanym NPC.

## Możliwości

- **Deklaratywne paczki JSON:** wersjonowane warunki i akcje, priorytety reguł, odstępy między
  wykonaniami oraz wyrażenia warunkowe `all` / `any` / `not`. JSON jest danymi, nigdy kodem wykonywalnym.
- **Deterministyczny arbitraż:** akcje konkurują o jawne kanały. Priorytet reguły, priorytet paczki,
  ID paczki, ID reguły i indeks akcji zapewniają stałą kolejność rozstrzygania konfliktów.
  Akcja wielokanałowa działa tylko wtedy, gdy dostępne są wszystkie jej kanały.
- **Transakcyjne przeładowanie:** zasoby wbudowane i własne pliki serwera przechodzą walidację
  przed aktywacją. Odrzucone przeładowanie zachowuje poprzedni poprawny rejestr; duplikaty ID go nie nadpisują.
- **Trwałe przypisania:** NPC może mieć maksymalnie osiem jawnie przypisanych paczek.
  Behavior zapisuje własne przypisania i trwały stan zadania drwala oddzielnie od Core.
- **Diagnostyka i odzyskiwanie postępu:** komendy pokazują aktywne paczki, wybrane akcje i postęp pracy;
  ograniczone mechanizmy ruchu, podnoszenia przedmiotów, wspinania i koordynacji wspierają demo drwala.
- **Ścisła granica zależności:** działania na świecie przechodzą przez Core na wątku serwera.
  Behavior nie importuje wnętrza encji/renderera Core ani nie zależy od opcjonalnego modułu LLM.

Przebieg wykonania to `snapshot -> evaluate -> arbitrate -> execute -> record`.
Kanały: `movement`, `look`, `main_hand`, `off_hand`, `combat`, `interaction`, `block_action`
i `inventory`. Szerszy podział modułów opisuje [wizja projektu](PROJECT_VISION.md).

## Wymagania

| Element | Wersja używana do budowania i testów |
| --- | --- |
| Minecraft Java Edition | 1.20.1 |
| Minecraft Forge | 47.4.21 |
| Kotlin for Forge | 4.12.0 |
| SAMCNPC Core | 0.1.0 |
| Java | 17 |

Zainstaluj Behavior, Core i Kotlin for Forge po stronie klienta i serwera.
W grze jednoosobowej wystarczy instalacja w używanym profilu klienta Forge.

## Budowanie i instalacja

Użyj JDK 17 i dołączonego wrappera Gradle. Core jest submodułem Git w `core/`, przypiętym do
konkretnego opublikowanego commita, a nie zmiennej gałęzi. Klonowanie wymaga dostępu do obu repozytoriów.
Aktualne przypięcie obejmuje lifecycle akcji, obserwacje, respawn, zachowanie ekwipunku, dropy,
punkty odradzania i automatyczne totemy w rezerwie Core. Używaj
pasującego JAR-a Core z tego buildu; starsze buildy rozwojowe też mogą mieć numer `0.1.0`.

```powershell
git clone --recurse-submodules https://github.com/DasIstEin20/SAMCNPC_Behavior.git
cd SAMCNPC_Behavior
.\gradlew.bat clean build
```

Na Linux/macOS użyj `./gradlew clean build`. Jeśli repozytorium sklonowano bez submodułów,
najpierw wykonaj `git submodule update --init --recursive`. Powtórz tę komendę po pobraniu zmiany przypięcia Core.
Pierwsze budowanie pobiera przypięte zależności.

Build tworzy dwa osobne pliki moda:

- `build/libs/samcnpc-behavior-0.1.0.jar`
- `core/build/libs/samcnpc-core-0.1.0.jar`

Behavior nie pakuje Core do swojego JAR-a. Zamknij grę/serwer przed skopiowaniem tych plików do
`mods` i zastąpieniem poprzednich wersji. Pozostaw zainstalowany Kotlin for Forge. Źródła LLM nie są potrzebne.

## Pierwsze kroki

Przywołaj NPC przez Core, a następnie przypisz wybrane paczki Behavior:

```text
/samcnpc summon Sam
/samcnpc behavior packs
/samcnpc behavior assign Sam samcnpc:follow_summoner,samcnpc:retaliate
/samcnpc behavior diagnostics Sam
```

Przypisanie zastępuje aktualną listę paczek NPC; kilka ID oddzielaj przecinkami.
Komendy dotyczące NPC przyjmują nazwę, UUID lub jednoznaczny prefiks w promieniu wyszukiwania
256 bloków w bieżącym wymiarze. Sterować daną postacią i sprawdzać ją może jej przywołujący gracz lub operator.

| Komenda | Działanie |
| --- | --- |
| `/samcnpc behavior packs` | Lista aktywnych ID paczek. |
| `/samcnpc behavior assign <npc> <pack_ids>` | Zastąpienie listy przypisanych paczek. |
| `/samcnpc behavior diagnostics <npc>` | Przypisane paczki, wybrane intencje i ostatni problem. |
| `/samcnpc behavior reload` | Walidacja i przeładowanie paczek; wymaga poziomu uprawnień operatora 2. |
| `/samcnpc behavior lumberjack <npc>` | Uruchomienie demonstracyjnego zadania drwala. |
| `/samcnpc behavior lumberjack status <npc>` | Faza zadania, cel, postęp skanowania i stan odzyskiwania postępu. |
| `/samcnpc behavior lumberjack cancel <npc>` | Anulowanie dema i przywrócenie wcześniejszych przypisań paczek. |

## Wbudowane paczki

| ID paczki | Przeznaczenie |
| --- | --- |
| `samcnpc:idle_look` | Patrzenie na przywołującego gracza z niskim priorytetem. |
| `samcnpc:follow_summoner` | Ruch w stronę oddalonego przywołującego gracza i patrzenie na niego z bliska. |
| `samcnpc:retaliate` | Wybór ostatniego napastnika NPC, podejście do niego i żądania ataków wręcz. |
| `samcnpc:demo_lumberjack` | Eksperymentalne zadanie drwala; uruchamiaj je osobną komendą. |

[Wbudowane pliki JSON](src/main/resources/data/samcnpc_behavior/behaviors) są edytowalnymi przykładami
tego samego formatu, z którego korzystają własne zachowania. Podążanie i walka ze wskazanym celem
używają ograniczonej nawigacji Core z obserwacją rzeczywistego dojścia i gotowości ataku.
Wybór celu i reakcje należą do Behavior.

## Własne paczki zachowań

Umieść pliki `.json` zapisane jako UTF-8 bezpośrednio w katalogu serwera `config/samcnpc/behaviors/`
(`run/config/samcnpc/behaviors/` przy uruchomieniu developerskim), a następnie użyj `/samcnpc behavior reload`.
Nadaj nowe ID z przestrzenią nazw: powtórzenie ID nie pozwala nadpisywać paczek wbudowanych ani własnych.

Na początek skopiuj [idle_look.json](src/main/resources/data/samcnpc_behavior/behaviors/idle_look.json),
zmień jego `id` na `myserver:idle_look` i zapisz jako `my_idle_look.json` we wskazanym katalogu.
Po poprawnym przeładowaniu przypisz go komendą:

```text
/samcnpc behavior assign Sam myserver:idle_look
```

[Schemat v1](src/main/resources/samcnpc/behavior-pack.schema.json) opisuje strukturę dokumentu.
Kompilator w Kotlinie dodatkowo sprawdza zarejestrowane ID akcji/warunków i ich argumenty.
Rzeczywista lista dozwolonych operacji znajduje się w
[BehaviorDefinitions](src/main/kotlin/io/samcnpc/behavior/registry/BehaviorDefinitions.kt).
Nieznane pola, nieobsługiwane ID, błędne argumenty i naruszenia kanałów są odrzucane;
paczki nie mogą ładować klas, skryptów, komend ani integracji HTTP.

Aktualne limity obejmują 64 pliki zewnętrzne, 128 KiB na plik zewnętrzny, 256 reguł w paczce,
16 akcji w regule i osiem poziomów wyrażeń warunkowych. Pliki są ładowane przy starcie/przeładowaniu,
a nie skanowane ani parsowane w każdym ticku NPC. Brak przypisanej paczki wstrzymuje wykonywanie
reguł danej postaci i ustawia diagnostykę bezpiecznego bezczynnego stanu.

## Trwałe zadania

Nawigacja, dostawy, zbieranie drewna z ponownym sadzeniem, kopanie, rolnictwo, zdobywanie żywności,
sadzenie drzew, transport między kontenerami, praca maszyn, łowienie, eksploracja, obrona, patrol i walka korzystają z trwałych ID zadań,
ograniczonych budżetów, pauzy/wznowienia/anulowania, rozliczania zasobów i ograniczonych przerwań.
Komendy pozwalają określić ilości, zasoby, obszary pracy, odbiorców, zaopatrzenie oraz obsługiwane
taktyki. Obsługiwane zmiany ilości, odbiorcy i zasobu przechodzą walidację także w trwającym zadaniu.

```text
/samcnpc behavior task assign Sam navigate 10 64 10 6000
/samcnpc behavior task assign Sam deliver 14 64 0 minecraft:oak_log 12 4
/samcnpc behavior task assign Sam lumberjack 0 64 4 23 73 26 14 64 0 samcnpc:oak 64 6000
/samcnpc behavior task reaction Sam retaliate 24 600 false
/samcnpc behavior task status Sam
/samcnpc behavior task pause Sam
/samcnpc behavior task resume Sam
/samcnpc behavior task cancel Sam
```

Współrzędne są przykładowe: zapewnij w swoim świecie dostępny grunt, kontenery, narzędzia i zasoby.
`deliver` przekazuje noszony zapas i zachowuje końcowe `keepAtLeast`; osobne zadanie transportu
pobiera ładunek ze wskazanego kontenera. Podpowiedzi komend pokazują dostępne rodzaje zadań
i argumenty. Dokładne ograniczenia zawierają [implementacje komend](src/main/kotlin/io/samcnpc/behavior/command).

Presety drewna obejmują `samcnpc:oak`, `samcnpc:birch`, `samcnpc:dark_oak` i `samcnpc:oak_and_birch`.
Początkowy zapas jest chroniony, podpory zużywają prawdziwe materiały, a raporty rozróżniają przedmioty
pobrane, zebrane, zużyte, dostarczone i zachowane. Osiągnięcie wymaganej dostawy może zakończyć pracę
przed usunięciem całego drzewa. Rezerwacje pracy/kontenerów i ustępowanie w przejściach koordynują
zadania; ponowne sadzenie i dobór zaopatrzenia mają jawne reguły. Raport kopania podaje przyczyny
odmowy, m.in. spadające bloki, niezniszczalne bloki i brak narzędzia. Zbieranie plonów obejmuje
płytką wodę nawadniającą pole.

Obrona może chronić przywołującego gracza lub innego NPC; patrol i walka obszarowa mają ograniczony
obszar i sprawdzane cele. Obsługiwane taktyki obejmują broń dystansową, tarcze oraz leczenie. Włączone
reakcje przerywają pracę i wznawiają ją po sprawdzeniu aktualnego świata. Akcje nie tworzą darmowych zasobów.

NPC ma jedno główne zadanie i najwyżej dwie ramki przerwań. Ręczna pauza zatrzymuje budżet i zwalnia
sterowanie; restart nie odnawia limitu. Format zapisu zadań 8 migruje starsze rekordy i zachowuje
odrzucone dane z diagnostyką. Magazyn zadań ma limit 4096 wpisów; zapełnienie jawnie odrzuca nowy wpis.

Stabilne publiczne API zlecania/kontroli operacji i rozszerzony katalog JSON pozostają do wykonania.
Obecne operacje obsługuje się komendami; dotychczasowa walidacja JSON paczek zachowań jest dostępna.
Opcjonalny dostawca LLM i integracja craftingu są odłożone.

## Łowienie, eksploracja i praca maszyn

Te operacje korzystają ze wspólnych budżetów, pauzy/wznowienia/anulowania, rozliczania
zasobów i przerwania pracy walką:

- [Łowienie](docs/FISHING.md): wskaż wodę, bezpieczne stanowisko, posiadaną wędkę i limit
  połowów; NPC zarzuca, obserwuje spławik, zwija raz, zbiera rzeczywisty loot i wraca.
- [Eksploracja](docs/EXPLORER.md): ograniczony obszar, fizyczne potwierdzenie odwiedzin
  i powrót po waypointach; limity komórek, wysokości, chunków i czasu.
- [Praca maszyn](docs/MACHINES.md): włóż dostarczone przedmioty do prawdziwego endpointu,
  poczekaj na wynik i dostarcz go. Pełny output, brak postępu lub usunięcie maszyny mają
  ograniczony wynik. Recepturę wykonuje maszyna, a nie ekwipunek NPC.
- [Kopanie](docs/MINING.md) i [praca z ekwipunkiem](docs/INVENTORY_WORK.md) opisują limity,
  chroniony zapas początkowy, rozliczanie zebranego łupu i powody zakończenia.

Działa tu promień zbierania Core 2–8 bloków. Rezerwacje nadal chronią pracę innych NPC.
Dojście do widocznego dropu zachowuje krótki czas na zebranie, podczas gdy oryginalny
deadline nadal maleje. Celowe zatrzymanie drogi nie staje się spóźnionym błędem kolejnej drogi.

## Starsze demo drwala

To **eksperymentalne zadanie integracyjne zmieniające prawdziwe bloki**. Wypróbuj je w testowym świecie z kopią zapasową.

Postaw przy NPC dostępną skrzynię z siekierą oraz opcjonalnie zbroją i blokami budowlanymi.
Do podpór z ziemi dodaj łopatę, a do bruku/netherracku kilof; zadanie pobiera te narzędzia ze skrzyni.
Bez bloków budowlanych NPC może odzyskać i wykorzystać prawdziwe drewno ze ściętego pnia.
Zapewnij dostępne drzewa o pniu 1×1 w ograniczonym obszarze pracy 50×50 wokół początkowej pozycji
NPC, a następnie wykonaj:

```text
/samcnpc behavior lumberjack Sam
/samcnpc behavior lumberjack status Sam
```

Fizyczne ustawienia NPC są w Core: **Mody → SAMCNPC Core → Config**. Globalne Yes/No
wymusza wartość dla wszystkich zapisów; Global Default przekazuje decyzję do In world
settings. **Ignore missing tool = Yes** pozwala pracować ręką przy braku siekiery, ale NPC
nadal pobierze dostępną siekierę ze skrzyni. **Praca wyłącznie ręką = Yes** pomija pobieranie
narzędzi i zawsze wymusza pustą rękę. Oba warianty domyślnie mają No; praca wyłącznie ręką
ma pierwszeństwo. Zasady dropu vanilli pozostają. Logo Behavior jest także na liście modów Forge.

Zadanie wybiera pobliską skrzynię, pobiera dostępne wyposażenie, szuka drewna, podchodzi na pozycję
roboczą, niszczy wskazane kłody, podnosi ich drop i oddaje zebrane drewno do skrzyni.
Mechanizmy pomocnicze obsługują ograniczone usuwanie liści, wejście na pień, tymczasowe podpory
z przedmiotów w ekwipunku, rozładunek po zapełnieniu, wykrywanie zastoju i koordynację pracowników.
Usuwanie liści zachowuje przerwane zadanie i istniejące stanowisko na podporze. Drewniane podpory
nie są mylone z pozostałym pniem. Stawianie jest sprawdzane synchronicznie, zanim kolejne podniesienie
zmieni licznik stosu. Przed końcowym rozładunkiem NPC sprząta podpory i kończy zbieranie dropów.
Wejście na pień zachowuje cel rozpoczętego skoku aż do prawdziwego lądowania; samo osiągnięcie
wysokości pnia w powietrzu nie wystarcza. Po ścięciu górnych kłód NPC rozbiera rusztowanie,
zanim odejdzie na bok, by ściąć zachowany dolny klocek pod nim.

Obie połówki podwójnej skrzyni udostępniają teraz wspólny ekwipunek przez Core. Po drodze
do skrzyni zadanie może usunąć widoczny blok liści lub pień, zebrać uzyskane drewno i wznowić
przerwaną pracę. Nie próbuje stale kopać ukrytego liścia przez paproć/pień. Usuwanie liści
na wysokości zachowuje pozycję na podporze, a powtarzanie całej próby podpory zużywa
ograniczony budżet odzyskiwania. Format zapisu zadania 19 migruje starsze zadania i zachowuje
ich wznowienia. Zadania wcześniej anulowane wymagają ponownej komendy startu.

Podpora ma limit ośmiu poziomów. Próba zbierania ma łączny limit 240 ticków i normalnie kończy się
po 20 tickach bez dropów, gdy NPC stoi na ziemi; udane podniesienia nie resetują limitu. Odzyskiwanie
materiału ma limit trzech prób na drzewo. Nieosiągalne bloki/drop uruchamiają ograniczone odzyskiwanie
lub diagnostykę zamiast nieskończonej pętli. Stan zadania i jego wznowienia są zapisywane, a starsze
stany niedokończonej weryfikacji podpór mają migrację.

Każdą fizyczną akcję nadal wykonuje Core; wybór celów i plan pracy drwala należą do Behavior.
Demo tymczasowo zastępuje paczki NPC i przywraca je po zakończeniu lub anulowaniu.
Anulowanie lub przerwane/niebezpieczne sprzątanie może pozostawić wcześniej postawione podpory;
ich pozycje są zapisywane w logu. Sprzątanie ma limit 600 ticków i odmawia pracy w cieczy,
podczas wspinania lub jazdy. Przetestowano naturalne korony dębu/brzozy
i pionowe pnie o wysokości dziewięciu bloków. Duże rozgałęzione drzewa/pnie 2×2, dowolny teren/modpack
i odzyskiwanie postępu przez wiele NPC nie są zweryfikowaną możliwością; to ograniczone demo, nie uniwersalna AI leśnika.

## Uruchamianie i testy

```powershell
.\gradlew.bat clean build
.\gradlew.bat :runGameTestServer
.\gradlew.bat :runClientLumberjackSmoke
.\gradlew.bat :runClientTaskSmoke :runClientTaskCombatSmoke
.\gradlew.bat :runClientFollowSmoke :runClientRetaliationSmoke
.\gradlew.bat :runClientLumberjackSmoke -PlumberjackGuiProbe=dirt
.\gradlew.bat :runClientLumberjackSmoke -PlumberjackGuiProbe=wood
.\gradlew.bat :runClientConfigSmoke
.\gradlew.bat :runClient
.\gradlew.bat :runServer
python tools/check_behavior_boundary.py
python core/tools/check_core_boundary.py
```

Początkowy `:` wybiera zadanie uruchomieniowe Behavior; Core udostępnia też własne zadania developerskie.
Klient/serwer Behavior ładuje oba mody. Zwykły start `:runServer` wymaga zaakceptowania
EULA Minecrafta przez użytkownika.
GameTesty mają własny płaski świat w `run-gametest/`; nie używają zwykłych światów developerskich z `run/`.

Testy jednostkowe obejmują walidację paczek, arbitraż, nawigację/wspinanie/pracę, limity zbierania
i migrację zapisanego stanu. 195 testów serwerowych sprawdza wyposażenie, usuwanie liści, pracę
na wysokości, podpory z drewna/ziemi/bruku, podnoszenie podczas stawiania, ustawienie na krawędzi,
zagnieżdżone odzyskiwanie, pełne sprzątanie, niebezpieczne/za długie oczekiwanie i dokładne rozliczenie drewna.
Regresje na płaskim podłożu wymagają też prawdziwego lądowania na pniu i rozebrania podpór
przed ścięciem zachowanej podstawy.
Osobny test klienta generuje
dąb i brzozę w izolowanym świecie i sprawdza prawdziwe animacje chodzenia/rąbania, pełny rozładunek,
brak pozostawionych podpór i spóźnionych podniesień drewna. Zapisuje lokalny wynik i zrzut w
`run-lumberjack-smoke/`, po czym sam zamyka klienta. Kod sterownika testowego nie trafia do JAR-a moda.
Warianty `dirt` i `wood` dodają trzeci, dziewięciokłodowy pień z koroną: pierwszy dostarcza ziemię,
drugi wymaga wykorzystania zebranego drewna na podpory. Oba wymagają wszystkich 20 oryginalnych
kłód w skrzyni, pełnego sprzątnięcia podpór i 40 ticków bez spóźnionego drewna po zakończeniu.
Przejście testu nie gwarantuje poprawności dla dowolnych drzew, modpacków czy terenu.

`runClientConfigSmoke` otwiera oba logotypy Forge i ekran Core, zapisuje ustawienia przez
prawdziwe pakiety serwera, sprawdza niezależność zapisów oraz ponowne wczytanie i kończy
zadanie drwala na dwóch kłodach bez siekiery. Światy/wyniki zostają w `run-config-smoke/`,
a sterowniki testów nie trafiają do JAR-ów. `runClientForestRepairSmoke -PforestSnapshot=<ścieżka>`
odtwarza pierwotną regresję z trzema NPC; wymaga przechwyconego świata i kopiuje go do
`run-forest-repair/`. Ten zapis nie jest dołączany do repozytorium.

Dodatki mogą sprawdzić dokument JSON bez jego aktywowania przez
[`BehaviorPackValidationApi.validateCandidate(json)`](src/main/kotlin/io/samcnpc/behavior/api/BehaviorPackValidationApi.kt).
Nowe wykonywalne akcje należą do zarejestrowanych handlerów Kotlin, a nie danych paczki.


`runClientTaskSmoke` sprawdza trwały zbiór drewna, trzy ponowne otwarcia świata i dostawy dębu/ciemnego dębu.
`runClientTaskCombatSmoke` przerywa dojście, kopanie, częściowy transfer i skok na podporę, po czym
sprawdza walkę oraz wznowioną dostawę. `runClientFollowSmoke` i `runClientRetaliationSmoke` sprawdzają
odpowiednie fizyczne zachowania. Behavior ma obecnie 290 testów jednostkowych; asercje runtime pozostają
włączone. Warianty GUI `runClientConfigSmokeBare` i `runClientConfigSmokeDurable` używają osobnych
katalogów i sprawdzają rzeczywiście wybrane ustawienia.

## Stan projektu i licencja

Kampanie z 12–13 września przeszły 290 testów jednostkowych Behavior, 195 GameTestów
Behavior i 132 Core, 24 żywe checkpointy zapisu/wczytania w osobnych JVM, 18 scenariuszy
cyklu życia, 21 scenariuszy terenowych i 12 przypadków w prawdziwym kliencie.
Pełna mieszana próba ukończyła 660 zadań ośmiu rodzin w 3641 sekundach aktywnej pracy;
osobne pomiary nawigacji przeszły dla 1, 8, 32 i 64 NPC. Przeszły też uruchomienie
trzech modów i kontrola dystrybucji. Poprawiony odczyt JSON odrzucił dziesięć błędnych
reloadów bez przerwania aktywnej pracy. [Zakres dowodów](docs/VALIDATION.md)
i [reguły wejścia JSON](docs/JSON_INPUT.md). Finalizacja publicznego API/katalogu
oraz osobny test skórek na dwóch uwierzytelnionych kontach pozostają otwarte.

Wersja rozwojowa **0.1.0**. Repozytorium publikuje Behavior i przypina jego zależność Core;
nie zawiera opcjonalnego modułu LLM. Zgodność API i zaawansowane przypadki rozgrywki pozostają
przedmiotem prac. Żaden zewnętrzny model nie otrzymuje obejścia walidacji paczek ani granicy akcji Core.

[MIT](LICENSE). Projekt społecznościowy, niepowiązany oficjalnie z Mojang ani Microsoft.
