# Sekurak

Alarm na Samsung Galaxy Z Fold6 („misiekfon”) odpalany z Home Assistant przez `input_boolean.input_boolean_alarm_telefoniczny`.
Apka **nie łączy się z HA bezpośrednio** – wszystko idzie przez Companion App HA. Zero dodatkowej baterii.

## Jak to działa
| Kierunek | Sygnał | Co się dzieje |
|---|---|---|
| HA → telefon | `pl.sekurak.START` | przełącznik ON → pełny ekran, dzwonek, wibracje |
| HA → telefon | `pl.sekurak.STOP` | przełącznik OFF w HA → telefon gasi alarm |
| telefon → HA | `pl.sekurak.ALARM` | potwierdzenie: telefon naprawdę dzwoni |
| telefon → HA | `pl.sekurak.KONIEC` | alarm zgaszony na telefonie → HA wyłącza przełącznik |

Sygnały telefon → HA lądują w czujniku `sensor.misiekfon_last_update_trigger`.

## Instalacja
1. Ściągnij `Sekurak.apk` z zakładki **Releases** i zainstaluj.
2. Otwórz apkę **raz** i nadaj wszystkie uprawnienia (mają być ✅).
3. Samsung: Ustawienia → Bateria → Limity użycia w tle → **Aplikacje nigdy nieusypiane** → dodaj Sekurak i Home Assistant.
4. Companion App HA: Ustawienia → Companion App → Zarządzaj czujnikami → **Last update trigger** → włącz i dodaj intenty `pl.sekurak.ALARM` oraz `pl.sekurak.KONIEC`.
5. Wklej 3 automatyzacje z `home_assistant_automatyzacje.yaml`.
6. W apce „TESTUJ ALARM”, potem przełącz `input_boolean` w HA.

## Ustawienia w apce
Dzwonek systemowy lub własny plik, głośność + wymuszanie, wibracje, auto-wyłączenie po X minutach, opcjonalny sekret.
