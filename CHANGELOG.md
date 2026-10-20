# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## AlarmeHA

Versões do fork (app `br.casa.alarmeha` e integração `custom_components/alarmeha`). Até a 0.6.0 o app e a integração tinham números próprios; desde a 0.7.0 usam o mesmo número. As versões do Fossify Clock (upstream) seguem abaixo.

### Próxima versão (0.8.0, em desenvolvimento, ainda não publicada)
- Planejado: cada compromisso do HA vira um cartão de grupo na lista; alarmes manuais ficam soltos.
- Planejado: tela do grupo com "Ativar tudo" / "Desativar tudo" (desativar pula só o dia) e chave em cada alarme.
- Planejado: data por extenso em cada alarme e "Pulado" no lugar de "Tocou às" para alarmes pulados.
- Planejado: envio confiável celular → HA (fila de até 24 h, aviso de restrição de bateria, alerta no HA após 30 min sem eventos, último erro no Sobre).
- Planejado: re-pareamento religa as entidades ao pareamento novo, desregistra webhooks antigos e limpa refs órfãos (corrige o problema conhecido da 0.7.0).
- Em decisão: forma dos remédios (um cartão "Remédios" com todos ou um cartão por remédio).

### 0.7.0 - 2026-10-20 (app e integração)
- Compromisso não se mescla mais entre dias: cada dia é um grupo; o de amanhã leva " · amanhã" no nome.
- Remédios (tipo com um alarme só) continuam se mesclando por dia da semana quando o horário se repete.
- Lista do app ordenada pelo próximo toque; só aparece o botão pertinente (desligar ou religar).
- Alarmes do grupo que já tocaram ficam esmaecidos, com "Tocou às HH:MM", até o grupo acabar.
- Problema conhecido: depois de ativar de novo, as entidades ficam presas ao pareamento antigo. Contorno: recarregar a integração.

### App 0.4.0 e integração 0.6.0 - 2026-10-20
- Grupos por compromisso (`grupo` e `grupo_nome` nos comandos); "Desligar o grupo" / "Religar o grupo".
- Desligar um alarme do HA no celular pula só o próximo toque (eventos `pulado` e `religado`; campo `pulado` na lista).
- Soneca padrão de 5 min.
- Ativar de novo cria token e webhook novos e reenvia o plano.

### Integração 0.5.0 a 0.5.14 - 2026-10-18 e 19
- Aba Agenda no painel: tipos de evento ligados a agendas, com palavras-chave (palavra vence; tipo sem palavra pega o resto; empate fica com o primeiro da lista).
- Alarmes fixos `ag_<tipo>_<n>` (até 12 por tipo) com 📅 no rótulo e o título do evento como nome; o plano cobre hoje e amanhã.
- Sincronização nos minutos 00/15/30/45 e 5 s depois de carregar a integração.
- Toque por tipo de evento e toque padrão.
- 0.5.12: não mexe no alarme enquanto toca ou está em soneca.
- 0.5.14: não mexe em alarme com toque entre 5 min à frente e 10 min atrás; ressincroniza depois da janela.

### 0.3.0 a 0.3.10 - 2026-10-18
- Painel Despertador, entrada única para a casa e ativação por código de uso único (sem copiar token).
- APK entregue pelo próprio HA; webhook tenta todas as URLs conhecidas, a última que funcionou primeiro.
- Ativação obrigatória na primeira abertura; tela de permissões; registro do último erro fatal.

### 0.2.0 - 2026-10-17
- Integração HACS `alarmeha` (uma entrada por celular).

### 0.1.0 - 2026-10-17
- Identidade própria (`br.casa.alarmeha`), comandos por token e eventos por webhook.

## [Unreleased]

## [1.6.0] - 2026-01-30
### Added
- Added support for custom fonts

### Changed
- Updated translations

### Fixed
- Fixed overlap/truncation in stopwatch lap times ([#346])

## [1.5.0] - 2025-12-16
### Changed
- Updated timer reset icon ([#335])
- Updated translations

### Fixed
- Fixed invisible status bar icons on the alarm screen

## [1.4.0] - 2025-10-29
### Changed
- Compatibility updates for Android 15 & 16
- Updated translations

## [1.3.1] - 2025-07-22
### Changed
- Updated translations

### Fixed
- Fixed crash on startup ([#247])

## [1.3.0] - 2025-07-13
### Changed
- Updated translations

### Fixed
- It's now possible to reselect the default system alarm sound ([#144])

## [1.2.2] - 2025-06-21
### Changed
- Replaced lap text button with an icon button
- Updated stopwatch layout animation
- Updated translations

### Fixed
- Fixed inaccuracy in stopwatch over long durations ([#207])
- Fixed text jitter in clock, stopwatch, and timer ([#11])
- Fixed invisible stopwatch laps in landscape mode ([#107])
- Fixed overlapping text in right-to-left languages ([#206])

## [1.2.1] - 2025-05-08
### Changed
- Updated translations

### Fixed
- Fixed gradual alarm volume increase on Samsung devices ([#158])

## [1.2.0] - 2025-04-15
### Added
- Added notifications for missed or replaced alarms
- Added placeholders for empty alarms and timers (#124)

### Changed
- Replaced checkboxes with switches (https://github.com/orgs/FossifyOrg/discussions/78)
- Other minor bug fixes and improvements
- Added more translations

### Removed
- Removed support for Android 7 and older
  versions (https://github.com/orgs/FossifyOrg/discussions/241)

### Fixed
- Fixed issue where alarms could be silenced unintentionally (#77, #93)
- Fixed issue where alarms didn't go off in some cases (#89)
- Fixed broken behavior when re-enabling one-time alarms (#110)

## [1.1.0] - 2025-03-24
### Added
- Added option to import/export alarms and timers (#105)
- Added option to choose between 12-hour and 24-hour time format (#52)
- Added option to choose first day of week (#19)
- Added option to choose default tab (#5)

### Changed
- Improved sorting options for alarms and timers (#7, #8)
- Other minor fixes and improvements
- Added more translations

### Fixed
- Fixed some issues with alarms not going off (#89, #113)
- Fixed delayed/early alarms due to daylight time saving (#61)
- Fixed issue with snooze button in landscape mode (#85)

## [1.0.0] - 2024-03-24
### Added
- Initial release.

[#11]: https://github.com/FossifyOrg/Clock/issues/11
[#107]: https://github.com/FossifyOrg/Clock/issues/107
[#144]: https://github.com/FossifyOrg/Clock/issues/144
[#158]: https://github.com/FossifyOrg/Clock/issues/158
[#206]: https://github.com/FossifyOrg/Clock/issues/206
[#207]: https://github.com/FossifyOrg/Clock/issues/207
[#247]: https://github.com/FossifyOrg/Clock/issues/247
[#335]: https://github.com/FossifyOrg/Clock/issues/335
[#346]: https://github.com/FossifyOrg/Clock/issues/346

[Unreleased]: https://github.com/FossifyOrg/Clock/compare/1.6.0...HEAD
[1.6.0]: https://github.com/FossifyOrg/Clock/compare/1.5.0...1.6.0
[1.5.0]: https://github.com/FossifyOrg/Clock/compare/1.4.0...1.5.0
[1.4.0]: https://github.com/FossifyOrg/Clock/compare/1.3.1...1.4.0
[1.3.1]: https://github.com/FossifyOrg/Clock/compare/1.3.0...1.3.1
[1.3.0]: https://github.com/FossifyOrg/Clock/compare/1.2.2...1.3.0
[1.2.2]: https://github.com/FossifyOrg/Clock/compare/1.2.1...1.2.2
[1.2.1]: https://github.com/FossifyOrg/Clock/compare/1.2.0...1.2.1
[1.2.0]: https://github.com/FossifyOrg/Clock/compare/1.1.0...1.2.0
[1.1.0]: https://github.com/FossifyOrg/Clock/compare/1.0.0...1.1.0
[1.0.0]: https://github.com/FossifyOrg/Clock/releases/tag/1.0.0
