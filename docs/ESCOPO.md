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
