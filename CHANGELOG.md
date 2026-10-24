# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## AlarmeHA

Versões do fork (app `br.casa.alarmeha` e integração `custom_components/alarmeha`). Até a 0.6.0 o app e a integração tinham números próprios; desde a 0.7.0 usam o mesmo número. As versões do Fossify Clock (upstream) seguem abaixo.

### 0.14.0 - 2026-10-24 (app; integração só o número)

**O que mudou**
- App: com a tela ligada e desbloqueada, o alarme aparece num cartão grande desenhado pelo próprio app no topo da tela, por cima de qualquer app (nome, hora grande, Adiar e Desligar, cores do tema do Fossify). Não depende do estilo de aviso do sistema, que em alguns aparelhos vira uma pílula pequena ("Pop-up breve"). Fica até uma ação ou até acabar o tempo (aí conta como `perdido`, como na 0.13.0).
- App: precisa da permissão "Sobrepor a outros apps". O app pede uma vez, com explicação, e dá para ligar depois em Configurações > "Aviso por cima dos apps". Não é obrigatória e não bloqueia o app; sem ela fica o aviso do sistema da 0.13.0.
- App: com o cartão na tela, a notificação do alarme fica só na gaveta (com Adiar e Desligar), sem aviso do sistema junto. O cartão sai ao desligar, adiar, acabar o tempo ou o alarme ser substituído.
- Tela desligada ou bloqueada: tela cheia de sempre, sem mudança.
- Integração: só o número (0.14.0), sem mudança funcional.

**Ainda não mudou:** pulo travando a atualização dos dias (opção A ou B, aguardando testes); interruptor de alarme que já tocou hoje (aguardando testes); dispensar do conjunto (em aberto); tela cheia com confirmação e "Tomei" do remédio (mais à frente).

### 0.13.0 - 2026-10-24 (app e integração)

**O que mudou**
- App: aviso de alarmes perdidos. Alarme que toca até acabar o tempo sem desligar nem adiar entra numa notificação única que acumula os perdidos ("2 alarmes perdidos: 03:33 e 03:43", com a lista dos nomes ao expandir). Some ao abrir o app ou limpar a notificação. Alarme pulado não conta.
- App: com a tela ligada e desbloqueada (celular em uso), o alarme aparece como aviso grande no topo, com nome, hora grande e os botões Adiar e Desligar, e fica até uma ação. Cores do tema do Fossify. Tela desligada ou bloqueada: tela cheia de sempre.
- App: cartão de grupo com margem lateral maior (ícones um pouco mais para o centro).
- App: no Modo diagnóstico, `pulou` e o novo `religado` levam `origem_pulo` (`switch_lista`, `switch_cartao`, `desativar_tudo`, `notificacao`) e `grupo`; os eventos `pulado`/`religado` também levam `origem_pulo`. O "Pular" da notificação de alarme próximo, em alarme da agenda, usa o mesmo caminho do interruptor.
- App e integração: evento novo `perdido` (no lugar de `desligado` quando acaba o tempo). A integração o trata como `desligado` (libera o alarme e pede a lista) e o grava no diagnóstico.

**Ainda não mudou:** pulo travando a atualização dos dias (opção A ou B, aguardando testes); interruptor de alarme que já tocou hoje (aguardando testes); dispensar do conjunto (em aberto); tela cheia com confirmação e "Tomei" do remédio (mais à frente).

### 0.12.0 - 2026-10-22 (app; integração só o número)

**O que mudou**
- App: sai a paleta fixa da 0.9.0 (roxo `#8C88F3`, chaves roxas/cinzas, fundo preto `#010101`, cartões `#17171A`, textos e divisórias cinza fixos). Tudo passa a usar as cores do tema do Fossify: cor do texto, cor principal e cor de fundo de "Personalizar aparência" (tema claro/escuro incluídos). Cartões e selos são o fundo do tema levemente puxado para a cor do texto; divisórias e esmaecido (desligado/pulado) são a cor do texto com transparência; chaves com as cores do tema, como no Fossify original.
- App: cartão de grupo em 3 colunas fixas: ícone com largura fixa à esquerda, título centralizado no espaço restante (até 2 linhas, depois reticências) e contagem + ícone com largura fixa à direita. Os ícones ficam alinhados entre os cartões, qualquer que seja o título.
- Formato dos cartões e da tela do grupo continua o mesmo.
- Integração: só o número (0.12.0), sem mudança funcional.

**Ainda não incluído:** dispensar um alarme do conjunto desligando os outros do dia; aviso em tela cheia com confirmação; confirmação de remédio.

### 0.11.0 - 2026-10-22 (app e integração)
- Integração: a sincronização olha hoje + 6 dias (7 dias).
- Integração: conjunto = (título, horário do evento). O mesmo título no mesmo horário reaproveita os mesmos N alarmes do tipo, com os dias da semana marcados (não mais N alarmes por dia). O mesmo título em outro horário vira outro conjunto de N no mesmo cartão.
- Integração: a cada sincronização entram os dias novos e saem os que passaram; o alarme que tocou hoje só perde o dia de hoje e segue nos demais (acabou o "tocou"/grupo encerrado que desligava o alarme inteiro).
- Integração: título sem evento nos 7 dias tem os alarmes apagados do celular e o cartão some.
- Integração: refs estáveis `ag_<tipo>_<hash>_<n>` (tipo + título + horário + n). As refs antigas `ag_<tipo>_<n>` são apagadas na primeira sincronização, antes de criar as novas.
- Integração: `por_tipo` (ex.: Remédio) = um cartão, um alarme por evento/horário com os dias marcados. Limite de 10 conjuntos por tipo, no lugar de 12 alarmes por tipo.
- App: o diagnóstico do comando recebido passa a trazer horário, dias e ativo.
- App: tela do grupo sem o espaço vazio em cima; cabeçalho da lista recolhe ao rolar (fica a linha com o título curto, + e ⋮).
- App: barra de abas e cores de volta ao Fossify original; cartões de grupo e tela do grupo continuam como estavam.
- Compatibilidade: o app 0.10.0 aceita os comandos da integração 0.11.0.

**Ainda não incluído:** dispensar um alarme do conjunto desligando os outros do dia; aviso em tela cheia com confirmação; confirmação de remédio.

### 0.10.0 - 2026-10-22 (app e integração)
- App: chave **Modo diagnóstico** em Configurações (desligada por padrão, sem desligamento automático). Ligada, envia ao HA pelo webhook, na mesma fila de envio, o evento `diagnostico` para: comando recebido, alarme agendado/desagendado, apagado, tocou, soneca, pulou, dispensou e erro (com mensagem técnica). Sempre com versão do app e horário; sem o rótulo do alarme.
- Integração: guarda os últimos 500 eventos de diagnóstico em `.storage/alarmeha.diagnostico`, fora do barramento e do histórico, preservados ao reiniciar. Nova ação `alarmeha.diagnostico` (com resposta, só administradores; filtros `device_id` e `limite`) e diagnósticos da integração com o registro.
- Distribuição: removido o script de envio pelo Firebase; o APK sai só pela release do GitHub e pelo painel.
- Testes novos da integração: registro circular de 500, persistência, ação e diagnósticos.

### 0.9.2 - 2026-10-21 (app e integração)
- Integração: no agrupamento "por evento", o grupo passa a ser o compromisso (tipo + título do evento), não o dia. "Teste Alarme" na quarta e na quinta vira um cartão só, chamado "Teste Alarme"; títulos diferentes = cartões separados. Sem título, o nome é o do tipo. As vagas (`ag_<tipo>_<n>`) e a lógica não mudam; quem atualiza só tem os alarmes editados (nada duplicado). "Por tipo" continua com o nome do tipo.
- Tela do grupo: alarmes do mesmo horário e rótulo viram uma linha com D S T Q Q S S (dias com alarme em roxo com ponto; dia pulado/desligado apagado). A chave da linha age só no próximo toque (pular a quarta mantém a quinta). Linha esmaecida só quando todos estão desligados. Horários diferentes continuam em linhas separadas com a data por extenso. "Ativar/Desativar tudo" continua.
- Barra de abas: refeita como pílula flutuante com os 4 ícones inteiros e a aba ativa destacada. Causa do defeito: o recuo da barra de navegação do sistema era aplicado na própria pílula de altura fixa, que cortava os ícones e deixava só um risco.
- Cartão de grupo na lista: pílula mais baixa, ícone, nome e contador numa linha só (sem data embaixo), como na Samsung. Removido o espaço vazio extra acima da lista.
- Decidido e mantido: o cartão mostra o total de alarmes, sem chave; grupos do HA não se editam, renomeiam nem recebem alarmes no app; mesma versão no app e na integração.
- **Pendente (não implementado):** dispensar um alarme de um conjunto (ex.: o 1/4) ainda não desliga os outros do dia. Aguarda a decisão sobre qual opção adotar: (1) dispensar um desliga o resto do dia (nunca no Remédio); (2) botão separado "Dispensar todos de hoje" na tela do alarme tocando; (3) escolha por tipo no painel. Diferenças visuais abertas: espaço vazio grande na tela do grupo e cabeçalho expandido em vez de recolhido.

### 0.9.1 - 2026-10-21 (app e integração)
- Só a numeração: app e integração com o mesmo número (0.9.1). Sem mudança funcional em relação à 0.9.0 (app) e à 0.8.1 (integração).
- Observação: a tag/release v0.9.0 ficou com a integração marcando 0.8.1 no manifest (o app era 0.9.0). Use a 0.9.1.

### 0.9.0 - 2026-10-21 (app e integração)
- Visual novo no estilo do Relógio da Samsung (One UI, escuro):
  - Aba Alarme com cabeçalho grande "Alarme em X horas e Y minutos" e a data/hora do próximo toque; + e ⋮ logo abaixo (a barra de cima some nessa aba).
  - Cartão de grupo em pílula: ícone roxo, só o nome do tipo ("Trabalho", "Remédio") e o total de alarmes; sem chave; tocar abre o grupo. Se dois grupos do mesmo tipo aparecem juntos (ex.: Trabalho de hoje e de amanhã), uma linha pequena com o dia diferencia.
  - Grupos em cima, alarmes soltos embaixo num cartão único com divisórias; cada parte pela ordem do próximo toque.
  - Hora grande em negrito, chave roxa. Alarme que repete: D S T Q Q S S com os dias ativos em roxo, negrito e ponto em cima. Alarme de uma vez: data por extenso ("qui., 22 de out."). Desligado esmaecido. Pulado: esmaecido, selo "Pulado" e quando volta.
  - Tela do grupo: título centralizado, "Ativar tudo" / "Desativar tudo" (só o que faz sentido; os dois quando misto), barra ‹ ⋮ e um cartão com rótulo acima da hora e chave por alarme. Grupo do HA não tem + nem editar/renomear; o ⋮ leva às configurações.
  - Barra de abas em pílula, só ícones.
- Sem mudança de lógica: pular o próximo toque, soneca, eventos ao HA e ordem por próximo toque continuam iguais.
- Integração sem mudança funcional em relação à 0.8.1. Na tag v0.9.0 o manifest da integração ainda marcava 0.8.1; corrigido na 0.9.1.

### 0.8.1 - 2026-10-21 (só integração; o app continua 0.8.0)
- Agenda indisponível não apaga mais alarmes: se uma agenda de um tipo não puder ser lida (indisponível, sem estado, ainda carregando depois de reiniciar ou com erro na leitura), as vagas desse tipo ficam como estão até ela voltar, com aviso (WARNING) no log. Com várias agendas no tipo, uma falhando já congela o tipo. Caso real: `calendar.remedio` indisponível por 3 min apagou os remédios na 0.8.0.
- Reconciliação: se a lista do celular chegar sem alarmes que o HA enviou ligados, o HA recria só esses, respeitando a janela de 5/10 min, toque/soneca e pulos. No máximo 1 vez a cada 15 min por alarme, espaçando (30 min, 1 h ... até 4 h) se continuar sumindo. Celular sem resposta: o ciclo para no primeiro comando não confirmado, sem empilhar.
- `apagar` de um alarme que já não existe (`ref_inexistente`) conta como sucesso e não vira "erro" no último evento.
- Comandos enviados ao celular aparecem no log em nível INFO (operação, ref, horário; sem token).

### 0.8.0 - 2026-10-20 (app e integração)
- Lista do app em cartões: todo compromisso do HA vira um cartão de grupo (cantos arredondados) com nome, próximo toque e quantos alarmes estão ligados/desligados, inclusive os de um alarme só. Alarmes manuais continuam soltos. Ordem pelo próximo toque.
- Tocar no cartão abre a tela do grupo: nome grande, "Desativar tudo" / "Ativar tudo" (os dois quando o grupo está misto) e a chave de cada alarme. Desativar pula só aquele toque (e cancela soneca em andamento); Ativar desfaz o pulo. Horário e nome dos alarmes do HA não se editam no app.
- Datas por extenso ("qua., 21 de out."; recorrente: "Toda ter, qua · qua., 21 de out."); desligados bem esmaecidos; "Pulado" no lugar de "Tocou às" quando o toque tinha sido pulado.
- Opção por tipo no painel, **Agrupar no app**: "Um cartão por evento" ou "Um cartão para o tipo inteiro". Padrões: Trabalho e tipos novos = por evento; Remédio(s) = tipo inteiro. Tipos já salvos recebem o padrão. Trocar a opção só reagrupa os alarmes já enviados (mesmas vagas e horários), respeitando a guarda, o toque e a soneca. Com "por evento", tipo de um alarme só vira um cartão por remédio.
- Envio celular → HA: o envio começa na hora; o WorkManager (expedited) fica só de reserva. Fila em disco de até 24 h, sem descartar por número de tentativas, em ordem e com o `quando` original; uma `lista` nova substitui as pendentes; a fila do webhook antigo é descartada ao ativar de novo.
- Tela do Home Assistant no app: último envio ok (hora e URL), último erro (URL e HTTP/exceção), itens na fila e descartados. Aviso fixo na lista quando a bateria está restrita, o Android pôs o app em espera ou o envio está falhando.
- HA: sensor `ultimo_evento_recebido` por celular e alerta (notificação persistente + evento `alarmeha_silencio`) quando o celular fica mais de 30 min calado tendo comando sem resposta ou alarme que deveria ter tocado. Some no próximo evento.
- HA: a URL de fora de casa conhecida pelo HA (externa ou Nabu Casa) entra na lista de URLs do app na ativação.
- Corrigido: ativar de novo deixava sensores e pendências presos ao pareamento antigo. Agora o mesmo celular recebe as credenciais novas no mesmo objeto; webhooks antigos são desregistrados; o HA registra só um webhook por celular.
- Corrigido: alarmes `ag_*` sem uso que sobravam no celular (ex.: duplicados desligados depois de ativar de novo) são apagados, respeitando a guarda de 5/10 min e alarmes tocando/em soneca.

### 0.7.0 - 2026-10-20 (app e integração)
- Compromisso não se mescla mais entre dias: cada dia é um grupo; o de amanhã leva " · amanhã" no nome.
- Remédios (tipo com um alarme só) continuam se mesclando por dia da semana quando o horário se repete.
- Lista do app ordenada pelo próximo toque; só aparece o botão pertinente (desligar ou religar).
- Alarmes do grupo que já tocaram ficam esmaecidos, com "Tocou às HH:MM", até o grupo acabar.
- Problema conhecido: depois de ativar de novo, as entidades ficam presas ao pareamento antigo. Contorno: recarregar a integração. Corrigido na 0.8.0.

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
