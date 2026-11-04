# Sekurak

Alarm na Samsung Galaxy Z Fold6 („misiekfon”) odpalany z Home Assistant przez `input_boolean.input_boolean_alarm_telefoniczny`.
Start/stop z HA idzie przez Companion App. Powrót (wyciszenie na telefonie) idzie webhookiem – apka łączy się z HA tylko w momencie wyciszenia, bez tokenu.

## Jak to działa
| Kierunek | Sygnał | Co się dzieje |
|---|---|---|
| HA → telefon | `pl.sekurak.START` | przełącznik ON → pełny ekran, dzwonek, wibracje |
| HA → telefon | `pl.sekurak.STOP` | przełącznik OFF w HA → telefon gasi alarm |
| telefon → HA | webhook | alarm wyciszony na telefonie → HA wyłącza przełącznik |

## Instalacja
1. Ściągnij `Sekurak.apk` z zakładki **Releases** i zainstaluj.
2. Otwórz apkę **raz** i nadaj wszystkie uprawnienia (mają być ✅).
3. Samsung: Ustawienia → Bateria → Limity użycia w tle → **Aplikacje nigdy nieusypiane** → dodaj Sekurak i Home Assistant.
4. W apce wpisz adres webhooka i kliknij „Testuj webhook”.
5. Wklej automatyzację z `home_assistant_automatyzacje.yaml`.
6. W apce „TESTUJ ALARM”, potem przełącz `input_boolean` w HA.

## Ustawienia w apce
Dzwonek systemowy lub własny plik, głośność + wymuszanie, wibracje, auto-wyłączenie po X minutach, opcjonalny sekret.
