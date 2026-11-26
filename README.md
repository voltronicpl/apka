# Sekurak

Alarm na Samsung Galaxy Z Fold6 („misiekfon”) odpalany z Home Assistant przez `input_boolean.input_boolean_alarm_telefoniczny`.
Start/stop z HA idzie przez Companion App. Powrót (wyciszenie na telefonie) idzie webhookiem – apka łączy się z HA tylko w momencie wyciszenia, bez tokenu.

## Jak to działa
| Kierunek | Sygnał | Co się dzieje |
|---|---|---|
| HA → telefon | `pl.sekurak.START` + typ | przełącznik ON → pełny ekran (tło/kolor/dzwonek wg typu), wibracje |
| HA → telefon | `pl.sekurak.STOP` + typ | przełącznik OFF w HA → telefon gasi ten typ |
| telefon → HA | webhook (lista typów) | alarm wyciszony na telefonie → HA wyłącza właściwe przełączniki |

## Typy alarmów
| Typ | Przełącznik w HA | Wygląd |
|---|---|---|
| ogólny | `input_boolean.input_boolean_alarm_telefoniczny` | czerwony, dzwonek |
| domofon | `input_boolean.alarm_domofon` | zielony, zdjęcie furtki |
| zalanie | `input_boolean.alarm_zalanie` | niebieski, zdjęcie kropli |
| alarm | `input_boolean.alarm_ropam` (włącza go alarm Ropam) | czerwony, dom z obwodem alarmu |
| szukaj | `input_boolean.alarm_szukaj_fon` | turkusowy, telefon z radarem |

Priorytet przy kilku naraz: alarm > zalanie > ogólny > domofon > szukaj. Każdy typ: własny dzwonek, wibracje i opcjonalna karta HA do otwarcia po wyciszeniu (przycisk WYCISZ I OTWÓRZ).

## Integracja Home Assistant (HACS)
Zamiast automatyzacji YAML możesz użyć integracji **Sekurak** – encje wybierasz w UI HA.
1. HACS → ⋮ → Własne repozytoria → `https://github.com/voltronicpl/apka`, typ **Integracja** → pobierz **Sekurak**, restart HA.
2. Ustawienia → Urządzenia i usługi → Dodaj integrację → **Sekurak** → wybierz telefon (`notify.mobile_app_…`).
3. **Konfiguruj**: dla Alarmu, Zalania, Domofonu i Ogólnego wybierz dowolnie wiele encji
   (`alarm_control_panel` odpala przy `triggered`, `binary_sensor`/`input_boolean`/`switch` przy `on`).
   Tu też widać adres webhooka. Domyślnie integracja słucha też na starym `sekurak_wycisz_pudaul75`
   przez `https://pusiloft.ovh`, a apka bez wpisanego adresu używa właśnie jego – nic nie trzeba wpisywać.
   Jak w apce jest lokalny `http://…`, apka po porażce sama próbuje tego samego webhooka przez adres zewnętrzny.
4. Integracja tworzy przełącznik na każdy typ (ON = dzwoni, OFF = gasi telefon) i przycisk **Gdzie jest fon**.
5. Wyłącz starą automatyzację z `home_assistant_automatyzacje.yaml`, inaczej alarmy pójdą podwójnie.

## Instalacja
1. Ściągnij `Sekurak.apk` z zakładki **Releases** i zainstaluj.
2. Otwórz apkę **raz** i nadaj wszystkie uprawnienia (mają być ✅).
3. Samsung: Ustawienia → Bateria → Limity użycia w tle → **Aplikacje nigdy nieusypiane** → dodaj Sekurak i Home Assistant.
4. W apce wpisz adres webhooka i kliknij „Testuj webhook”.
5. Wklej automatyzację z `home_assistant_automatyzacje.yaml`.
6. W apce „TESTUJ ALARM”, potem przełącz `input_boolean` w HA.

## Ustawienia w apce
Per typ: dzwonek (systemowy/plik), wibracje, karta HA do otwarcia, test. Wspólne: głośność + wymuszanie, auto-wyłączenie, webhook, sekret.
