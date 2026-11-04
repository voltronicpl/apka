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

Priorytet przy kilku naraz: zalanie > ogólny > domofon. Każdy typ: własny dzwonek, wibracje i opcjonalna karta HA do otwarcia po wyciszeniu (przycisk WYCISZ I OTWÓRZ).

## Instalacja
1. Ściągnij `Sekurak.apk` z zakładki **Releases** i zainstaluj.
2. Otwórz apkę **raz** i nadaj wszystkie uprawnienia (mają być ✅).
3. Samsung: Ustawienia → Bateria → Limity użycia w tle → **Aplikacje nigdy nieusypiane** → dodaj Sekurak i Home Assistant.
4. W apce wpisz adres webhooka i kliknij „Testuj webhook”.
5. Wklej automatyzację z `home_assistant_automatyzacje.yaml`.
6. W apce „TESTUJ ALARM”, potem przełącz `input_boolean` w HA.

## Ustawienia w apce
Per typ: dzwonek (systemowy/plik), wibracje, karta HA do otwarcia, test. Wspólne: głośność + wymuszanie, auto-wyłączenie, webhook, sekret.
