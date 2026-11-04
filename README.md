# Misiekfon Alarm

Alarm na Samsung Galaxy Z Fold6 odpalany z Home Assistant przez `input_boolean.input_boolean_alarm_telefoniczny`.

## Jak to działa
1. `input_boolean` → `on` → automatyzacja HA wysyła `command_broadcast_intent` przez `notify.mobile_app_misiekfon`.
2. Companion App HA przekazuje sygnał `pl.misiekfon.alarm.START` do tej apki (zero dodatkowej baterii – apka śpi do sygnału).
3. Apka: dzwonek w pętli + wibracje + wybudzenie ekranu + pełny ekran nad blokadą z przyciskiem **WYCISZ ALARM**.
4. WYCISZ → lokalnie gaśnie natychmiast, potem `input_boolean.turn_off` do HA (3 próby). Jak HA nie odpowie – toast + powiadomienie o braku synchronizacji.
5. Wyłączenie w HA → automatyzacja wysyła `pl.misiekfon.alarm.STOP` → alarm gaśnie na telefonie.

## Budowanie
1. Otwórz folder `MisiekfonAlarm` w Android Studio (Ladybug lub nowsze). Jak zapyta o Gradle Wrapper – zgódź się.
2. Run na telefonie (USB/Wi-Fi debug) albo Build → Build APK(s).

## Pierwsze uruchomienie na telefonie (ważne!)
1. Otwórz apkę **raz** – Android nie dostarcza sygnałów do apki, której nigdy nie otwarto.
2. Wpisz adres HA (np. `http://192.168.1.10:8123` albo adres Nabu Casa) i token (Profil w HA → Bezpieczeństwo → Tokeny o długim terminie ważności).
3. „Testuj połączenie” – ma pokazać ✅ i stan encji.
4. Nadaj uprawnienia z sekcji „Uprawnienia” – wszystkie mają być ✅:
   - powiadomienia,
   - pełny ekran,
   - bez optymalizacji baterii (bez tego Samsung może zablokować start alarmu z tła),
   - wyświetlanie nad innymi (żeby pełny ekran wyskakiwał też, gdy akurat używasz telefonu – bez tego pokaże się tylko wyskakujące powiadomienie z przyciskiem WYCISZ ALARM).
5. Samsung: Ustawienia → Bateria → Limity użycia w tle → dodaj **Misiekfon Alarm** i **Home Assistant** do „Aplikacje nigdy nieusypiane”.
6. „TESTUJ ALARM”.

## Home Assistant
Gotowe automatyzacje: `home_assistant_automatyzacje.yaml`.

## Ustawienia w apce
- dzwonek systemowy albo własny plik audio, przywrócenie domyślnego,
- głośność alarmu (10–100%) + wymuszanie jej na czas alarmu (potem wraca poprzednia),
- wibracje on/off,
- auto-wyłączenie po X minutach (0 = nigdy),
- opcjonalny sekret – żeby inna apka na telefonie nie mogła odpalić alarmu.
