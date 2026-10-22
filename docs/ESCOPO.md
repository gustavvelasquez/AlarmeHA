# Escopo do AlarmeHA (fonte oficial)

Este arquivo é a fonte oficial do escopo do AlarmeHA (app `br.casa.alarmeha` e integração `custom_components/alarmeha`). Fonte inicial: a especificação original do projeto e os pedidos dos usuários, conferidos com o código em 2026-10-21 (0.9.2). Toda versão atualiza esta tabela junto com o CHANGELOG, o README e o guia.

Status: **feito**, **pendente** (pedido e ainda não feito ou aguardando decisão) ou **futuro** (previsto na especificação original do projeto, sem data).

## Regras do projeto

| Item | Status | Versão | Observação |
|---|---|---|---|
| Mesmo número de versão no app e na integração | feito | 0.7.0 (regra reforçada na 0.9.1) | `gradle.properties`, `manifest.json` e `const.py`; toda versão sai nos dois |
| Documentação atualizada antes de publicar | feito | 0.9.2 | Especificação, CHANGELOG, README, guia e este arquivo |
| Não disparar nada no celular de uma segunda pessoa da casa sem consentimento | feito | — | Outras pessoas só recebem algo com consentimento |

## Comunicação e pareamento

| Item | Status | Versão | Observação |
|---|---|---|---|
| Comandos HA → app pelo Companion (`command_broadcast_intent`) com token | feito | 0.5.x | `criar`, `editar`, `ligar`, `desligar`, `apagar`, `listar` |
| Eventos app → HA por webhook | feito | 0.5.x | `lista`, `alterado`, `tocou`, `soneca`, `desligado`, `pulado`, `religado`, `erro`... |
| Pareamento pelo painel (QR code / código de uso único) | feito | 0.5.x | Sem copiar token |
| Re-pareamento no mesmo celular sem entidades presas | feito | 0.8.0 | Webhooks antigos desregistrados |
| Envio imediato com fila de 24 h | feito | 0.8.0 | WorkManager só de reserva |
| Sensor `ultimo_evento_recebido` e alerta após 30 min sem eventos | feito | 0.8.0 | Evento `alarmeha_silencio` |
| Comandos no log em INFO, sem token | feito | 0.8.1 | |

## Agenda e decisão

| Item | Status | Versão | Observação |
|---|---|---|---|
| Agenda pelo iCal secreto via Remote Calendar | feito | 0.5.x | |
| Próximos 14 dias enviados; app aplica sozinho, mesmo offline | feito | 0.5.x | `DIAS_PREVIA = 14` |
| Antecedência (padrão 1h15) | feito | 0.5.x | Ajustável por tipo de evento |
| Tipos de evento no painel com vagas fixas `ag_<tipo>_<n>` | feito | 0.5.x | O HA só reajusta as vagas |
| Sincronização 00/15/30/45 e ao carregar | feito | 0.5.13 | |
| Guarda de 5 min à frente / 10 min atrás, tocando ou em soneca | feito | 0.5.12–0.5.14 | |
| Opção Agrupar por tipo: `por_evento` / `por_tipo` | feito | 0.8.0 | Padrões: Trabalho `por_evento`, Remédio `por_tipo` |
| Agenda indisponível congela o tipo | feito | 0.8.1 | Nada é apagado |
| Reconciliação de refs sumidos | feito | 0.8.1 | ≤1×/15 min por ref, espaçando até 4 h |
| `ref_inexistente` conta como sucesso | feito | 0.8.1 | |
| Limpeza de `ag_*` órfãos | feito | 0.8.0 | |
| Refs estáveis | pendente | — | Planejado para a 0.9.x |
| Várias fontes com prioridade (exceções, escala, grade semanal, Workday) | futuro | — | — |
| Recálculo avisando a pessoa quando o horário muda | futuro | — | — |

## App (visual e comportamento)

| Item | Status | Versão | Observação |
|---|---|---|---|
| Desligar grupo/linha pula só o próximo toque | feito | 0.6.0 / app 0.4.0 | Eventos `pulado` e `religado` |
| Soneca de 5 min | feito | 0.6.0 / app 0.4.0 | |
| Visual igual ao Relógio da Samsung | feito | 0.9.0 | Cabeçalho "Alarme em X horas e Y minutos" |
| Um cartão por compromisso, agrupado pelo título | feito | 0.9.2 | Antes era por dia |
| Cartão mostra o total de alarmes, sem chave | feito | 0.9.0 | |
| Linhas do mesmo horário juntas com D S T Q Q S S | feito | 0.9.2 | Vagas por dia continuam; chave age no próximo toque |
| Horário 24 h, data por extenso, selo "Pulado" | feito | 0.9.0 | |
| Ativar/Desativar tudo na tela do grupo | feito | 0.8.0 | |
| Grupos do HA sem editar, renomear ou adicionar | feito | 0.9.0 | Só alarmes avulsos se gerenciam no app |
| Barra de abas em pílula consertada | feito | 0.9.2 | |
| Dispensar um alarme do conjunto desliga os outros do dia ("Dispensar todos de hoje") | pendente | — | Na especificação original do projeto, não implementado. Aguarda decisão: (1) dispensar um desliga o resto do dia (nunca no Remédio); (2) botão separado "Dispensar todos de hoje"; (3) escolha por tipo no painel |
| Espaço vazio grande na tela do grupo | pendente | — | Diferença visual da Samsung |
| Cabeçalho da lista recolhido como na Samsung | pendente | — | Hoje fica expandido |

## Decisões aprovadas — sincronização e cards

Regras combinadas para a próxima versão. Exemplos fictícios: tipo "Trabalho" com 4 alarmes antes do evento e agrupamento por evento; tipo "Remédio" com antecedência 0 e agrupamento do tipo inteiro.

| Regra | Status | Observação |
|---|---|---|
| Agrupamento `por_evento`: um cartão por título de evento da agenda do tipo, cada um com os N alarmes das antecedências do tipo | aprovado | Ex.: eventos "Trabalho" e "Reunião" no tipo Trabalho = dois cartões, 4 alarmes cada |
| Agrupamento `por_tipo`: um cartão com o nome do tipo, um alarme por evento na hora do evento | aprovado | Ex.: cartão "Remédio" com um alarme por evento ("Dose manhã", "Dose noite") |
| Janela de sincronização: hoje + 6 dias (7 dias) | aprovado | Limite de 7 porque o alarme repete por dia da semana (D S T Q Q S S); com 8 ou mais, dois dias iguais da semana se confundiriam |
| Em cada sincronização: marca os dias da janela que têm o evento; desmarca os dias que já passaram e os dias sem evento | aprovado | O dia vira à meia-noite |
| Número de alarmes por cartão fixo no configurado | aprovado | Muda só os dias marcados; nunca um alarme a mais por dia |
| Cartão cujo evento não aparece na janela de 7 dias é apagado | aprovado | |
| Sem data guardada no alarme | aprovado | A sincronização deve rodar pelo menos 1×/dia; a janela de 7 dias é margem de segurança |
| Aviso de comunicação parada | aprovado | O alerta do HA de celular sem notícias avisa se a comunicação parar por mais de um dia |
| Conjunto de alarmes = (título, horário) | aprovado | Mesmo título no mesmo horário reutiliza os mesmos N alarmes do tipo, com os dias da semana marcados; não cria N alarmes por dia |
| Mesmo título em outro horário gera outro conjunto dentro do mesmo cartão | aprovado | Ex.: "Trabalho" às 06:00 na sexta e às 08:00 no sábado = dois conjuntos de N alarmes no cartão "Trabalho" |
| Depois de tocar, o alarme só desmarca o dia que passou | aprovado | Os demais dias seguem ligados; o alarme não é desligado inteiro |
| Vagas (refs) estáveis por cartão, por título e horário | aprovado | Os alarmes não trocam de cartão entre uma sincronização e outra |
| Dispensar um alarme do conjunto | pendente | Em discussão; risco de dispensar os demais meio dormindo |

## Decisões aprovadas — roadmap

| Decisão | Status | Observação |
|---|---|---|
| Ordem das fases: 0 Processo de teste; 1 Estabilizar; 2 Completar a visão; 3 Comunidade; 4 Depois | aprovado | Nenhuma fase começa sem aprovação; cada item passa por entender, desenhar e aprovar antes do código |
| Fase 1 — Estabilizar | aprovado | Regras de cartões e janela de 7 dias (seção acima); espaço vazio na tela do grupo; cabeçalho recolhido; refs estáveis |
| Teste antes de cada versão no celular do usuário | aprovado | Roteiro de teste rodado antes de liberar a versão |
| Chave "Modo diagnóstico" no app | aprovado | Desligada por padrão. Ligada, envia ao HA pelo webhook um registro técnico detalhado (recebido, agendado, tocou, soneca, pulou, erro), lido pelo mantenedor no HA; sem dados pessoais |
| Distribuição de testes pelo Firebase | descontinuado | O APK sai só pela release do GitHub (e pelo link do painel) |
| README completo em inglês e em português | aprovado | Fase 3 |
| Publicação no HACS padrão | aprovado | Fase 3. Preparar para a validação: `hacs.json`, hassfest, HACS Action, releases e brand |
| F-Droid | fora por enquanto | — |
| Aviso em tela cheia com confirmação; confirmação de remédio | desejado | Fase 2, a discutir depois |
| Escalonamento para outra pessoa; regras por pessoa; pausa/férias; aviso de mudança de horário; várias fontes com prioridade; blueprints; iOS | fora do escopo | — |
| Dispensar um alarme do conjunto | pendente | Em análise; risco de dispensar os demais meio dormindo |

## Decisão aprovada — versão 0.10.0 (fase 0)

| Item | Status | Observação |
|---|---|---|
| Chave "Modo diagnóstico" em Configurações do app | aprovado | Desligada por padrão e sem desligamento automático |
| Eventos técnicos do Modo diagnóstico | aprovado | Comando recebido, alarme agendado, alarme apagado, tocou, soneca, pulou, dispensou e erro (com mensagem técnica), sempre com versão do app e horário; pelo mesmo webhook e pela mesma fila de envio. Sem dados pessoais: só vaga (`ref`), id, horário e dias; nunca o rótulo |
| Registro no HA com os últimos 500 eventos | aprovado | Em armazenamento próprio da integração, fora do histórico (recorder); preservado ao reiniciar o HA; os mais antigos saem primeiro |
| Leitura do registro | aprovado | Ação `alarmeha.diagnostico` com resposta (só administradores) e diagnósticos da integração |
| Remover a distribuição pelo Firebase | aprovado | Script e menções removidos; APK só pela release do GitHub e pelo link do painel |
| Versão 0.10.0 no app e na integração | aprovado | CHANGELOG, README, guia e este arquivo atualizados |

## Distribuição

| Item | Status | Versão | Observação |
|---|---|---|---|
| APK release pela release do GitHub ou link do painel | feito | 0.5.x | `AlarmeHA.apk` |
| Firebase só com `br.casa.alarmeha.debug` | feito | — | Outro app; obriga a parear de novo |
| Integração pelo HACS | feito | 0.5.x | |

## Futuro (previsto na especificação original do projeto)

| Item | Status | Versão | Observação |
|---|---|---|---|
| Aviso grande em tela cheia com confirmação ("Acordei"/"Tomei") | futuro | — | — |
| Escalonamento para outra pessoa sem confirmação | futuro | — | — |
| Remédios com confirmação `TOMEI_<n>` | futuro | — | — |
| Pausa/férias por pessoa e exceções manuais | futuro | — | — |
| Blueprints de ação | futuro | — | — |
| Regras por pessoa (ex.: uma segunda pessoa da casa), com consentimento | futuro | — | — |
| iOS | futuro | — | Só notificações |
