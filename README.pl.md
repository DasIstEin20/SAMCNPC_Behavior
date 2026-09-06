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
tego samego formatu, z którego korzystają własne zachowania. Akcje podążania i walki używają obecnie
bezpośredniego sterowania ruchem; nie są pełnymi algorytmami nawigacji omijającymi przeszkody.

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

## Demo drwala

To **eksperymentalne zadanie integracyjne zmieniające prawdziwe bloki**. Wypróbuj je w testowym świecie z kopią zapasową.

Postaw przy NPC dostępną skrzynię z siekierą oraz opcjonalnie zbroją, łopatą i blokami budowlanymi,
np. ziemią. Zapewnij dostępne drzewa w ograniczonym obszarze pracy 50×50 wokół początkowej pozycji
NPC, a następnie wykonaj:

```text
/samcnpc behavior lumberjack Sam
/samcnpc behavior lumberjack status Sam
```

Zadanie wybiera pobliską skrzynię, pobiera dostępne wyposażenie, szuka drewna, podchodzi na pozycję
roboczą, niszczy wskazane kłody, podnosi ich drop i oddaje zebrane drewno do skrzyni.
Mechanizmy pomocnicze obsługują ograniczone usuwanie liści, wejście na pień, tymczasowe podpory
z przedmiotów w ekwipunku, rozładunek po zapełnieniu, wykrywanie zastoju i koordynację pracowników.

Każdą fizyczną akcję nadal wykonuje Core; wybór celów i plan pracy drwala należą do Behavior.
Demo tymczasowo zastępuje paczki NPC i przywraca je po zakończeniu lub anulowaniu.
Anulowanie może pozostawić wcześniej postawione podpory. Naturalne korony drzew, trudny teren
i odzyskiwanie postępu przez wiele NPC nadal wymagają szerszych testów w grze; nie jest to uniwersalna AI leśnika.

Znany problem: ścieżka podpór budowanych wyłącznie z drewna może zawieść podczas weryfikacji
stawiania lub odzyskiwania materiału. Istniejący GameTest drwala nie przeszedł zarówno w kopii
do publikacji, jak i oryginalnym workspace; pozostaje włączony. Demo nie jest gotowe do zastosowań produkcyjnych.

## Uruchamianie i testy

```powershell
.\gradlew.bat clean build
.\gradlew.bat :runGameTestServer
.\gradlew.bat :runClient
.\gradlew.bat :runServer
python tools/check_behavior_boundary.py
python core/tools/check_core_boundary.py
```

Początkowy `:` wybiera zadanie uruchomieniowe Behavior; Core udostępnia też własne zadania developerskie.
Klient/serwer Behavior ładuje oba mody. Zwykły start `:runServer` wymaga zaakceptowania
EULA Minecrafta przez użytkownika.

Testy jednostkowe obejmują walidację paczek, arbitraż oraz mechanizmy nawigacji, wspinania i pracy.
Dedykowany GameTest sprawdza wyposażenie ze skrzyni, usuwanie liści, pracę przy wysoko położonych kłodach,
odzyskanie prawdziwego materiału na podpory, sprzątanie, podnoszenie przedmiotów i końcowy rozładunek przez Core.
Przejście testu nie gwarantuje poprawności dla dowolnych drzew, modpacków czy terenu.

Dodatki mogą sprawdzić dokument JSON bez jego aktywowania przez
[`BehaviorPackValidationApi.validateCandidate(json)`](src/main/kotlin/io/samcnpc/behavior/api/BehaviorPackValidationApi.kt).
Nowe wykonywalne akcje należą do zarejestrowanych handlerów Kotlin, a nie danych paczki.

## Stan projektu i licencja

Wersja rozwojowa **0.1.0**. Repozytorium publikuje Behavior i przypina jego zależność Core;
nie zawiera opcjonalnego modułu LLM. Zgodność API i zaawansowane przypadki rozgrywki pozostają
przedmiotem prac. Żaden zewnętrzny model nie otrzymuje obejścia walidacji paczek ani granicy akcji Core.

[MIT](LICENSE). Projekt społecznościowy, niepowiązany oficjalnie z Mojang ani Microsoft.
