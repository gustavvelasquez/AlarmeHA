# Integração AlarmeHA ↔ Home Assistant

O Home Assistant (HA) controla os alarmes do AlarmeHA. O caminho é **HA → app Companion → AlarmeHA**, pelo comando `command_broadcast_intent`. O AlarmeHA responde por um **webhook** do HA.

- **Ida:** o HA pede para criar, editar, ligar, desligar, apagar ou listar alarmes.
- **Volta:** o AlarmeHA avisa quando um alarme tocou, entrou em soneca, foi desligado, perdido (tocou até acabar o tempo), pulado ou religado, confirma as mudanças e manda a lista de alarmes.
- **Versão:** este guia descreve a **0.15.0** (app e integração). App e integração saem sempre com o mesmo número (`gradle.properties`, `manifest.json` e `const.py`). Exceção: na 0.14.1 só a integração mudou; o app continua 0.14.0 e a release 0.14.1 traz o mesmo APK da 0.14.0. O que mudou nas últimas versões está resumido na seção 7.
- **Segurança:** todo comando leva um **token** (gerado pelo HA na ativação pelo painel, ou pelo app no modo manual). Comando sem o token certo é ignorado em silêncio.
- **Regra:** o HA só edita ou apaga alarmes que ele mesmo criou (com `ref`). Alarmes criados à mão no celular aparecem só na lista.
- **Som:** o padrão do app, a não ser que o comando traga `som_uri` (toque por tipo de evento na agenda).
- **Webhook:** um por pessoa (ex.: um para a pessoa principal e outro para uma segunda pessoa da casa).

## 0. Jeito recomendado: integração HACS "AlarmeHA" + painel Despertador

Este repositório também traz uma integração para o Home Assistant (`custom_components/alarmeha`). Ela faz pelo HA o que as seções 1 a 4 fazem à mão: webhook, token, comandos com novas tentativas e sensores. Desde a versão 0.3.0 ninguém copia token: cada pessoa ativa o próprio celular pelo painel **Despertador**.

### Passo a passo
1. **Admin, uma vez:** HACS → menu ⋮ → **Repositórios personalizados** → URL `https://github.com/gustavvelasquez/AlarmeHA`, tipo **Integração** → Adicionar. Procure **AlarmeHA** → Baixar → reinicie o HA.
2. **Admin, uma vez:** Configurações → Dispositivos e serviços → **Adicionar integração** → **AlarmeHA**. Confirme a **URL externa do HA** (a que os celulares usam, também fora de casa). Só existe uma entrada para a casa toda.
3. **Cada pessoa, no próprio celular:** entre no app **Home Assistant (Companion)** com a sua conta e abra o painel **Despertador** na barra lateral.
4. **Baixar e instalar:** toque em **Baixar e instalar**. O próprio HA entrega o APK (`/api/alarmeha/apk`, link assinado de curta duração), que ele baixa da última release do GitHub e guarda em cache. Se o Play Protect avisar, toque em **Instalar mesmo assim**. Se o download falhar, o painel tem o link para a página da release.
5. **Abra o app AlarmeHA e toque em Ativar.** O app abre o painel no Companion (`homeassistant://navigate/despertador?ativar=1`). Só nesse caso o painel mostra o cartão **Ativar este celular**: ele gera o código sozinho e mostra o botão grande **Confirmar ativação**. O AlarmeHA pergunta **"Ativar com o HA da casa?"** → Ativar.
   - O código é de **uso único** e vale 10 minutos. O app troca o código, em `POST /api/alarmeha/ativar`, pelo webhook e pelo token gerados no HA. O app guarda só o hash do token.
   - O link de ativação usa o endereço pelo qual você está acessando o HA agora (Nabu Casa, IP local, proxy reverso...). A URL externa configurada na integração é só reserva.
   - Na ativação o HA manda ao app todas as URLs que conhece: a usada na ativação, a externa e a interna. Como o Companion, o app envia cada evento tentando primeiro a última URL que funcionou e depois as outras.
   - O HA acha o celular do Companion pelo usuário logado e cria o dispositivo **AlarmeHA &lt;pessoa&gt;** sozinho.
   - Se não voltar para o app: no AlarmeHA, Configurações → Home Assistant → Avançado → **Colar configuração** com o texto `{"v":2,...}` que o cartão mostra.
   - **Ativar com o Home Assistant** também fica no menu ⋮ do app e em Configurações → Home Assistant. Sem o app Home Assistant instalado, o AlarmeHA avisa e oferece a Play Store.
6. Pronto: o painel lista os alarmes desse celular e permite criar, editar, ligar/desligar e apagar.

Enquanto o celular nunca foi ativado, o AlarmeHA mostra só a tela **Ativar o AlarmeHA**, sem acesso aos alarmes. O botão **Ativar** abre o painel no Companion; **Não abriu? Ativar manualmente** leva ao QR / Colar configuração; Voltar fecha o app. Sem o app Home Assistant (Companion) instalado (`io.homeassistant.companion.android` ou `.minimal`), a tela mostra o aviso de que ele é obrigatório, com o botão **Abrir a Play Store**; tocar em Ativar mostra o mesmo aviso (0.15.0). Depois da primeira ativação essa tela nunca mais aparece: o app e os alarmes funcionam normalmente mesmo sem HA, internet ou token válido. O app também mostra o próximo alarme do sistema, para você recriá-lo no AlarmeHA e **desativar os alarmes do relógio antigo**.

**Quem vê o quê:** usuários comuns só veem e controlam o próprio celular, no painel e nas ações. Admins veem todos. Automações (sem usuário) podem mirar qualquer celular.

**Ativar de novo** (troca de celular, reinstalação ou token perdido): repita o passo 5 a partir do app. O mesmo dispositivo é reaproveitado, o HA cria token e webhook novos (os antigos deixam de valer) e reenvia o plano da agenda. No painel, **Desvincular** remove o celular do HA.

> **0.7.0:** depois de ativar de novo, as entidades podiam ficar presas ao pareamento antigo (contorno: recarregar a integração). **Corrigido na 0.8.0:** o celular recebe as credenciais novas no mesmo objeto, os webhooks antigos são desregistrados e a fila do app do webhook antigo é descartada.

### Vindo da 0.2.x (uma entrada por celular)
- A **primeira** entrada antiga vira a entrada única e mantém o celular dela, com o mesmo webhook, token, dispositivo e entidades. Esse celular aparece só para admins (não tem usuário). Para ligá-lo à pessoa, ative de novo pelo painel e depois desvincule o celular antigo duplicado, se aparecer.
- As **outras** entradas antigas ficam com erro de migração e uma notificação pede para removê-las. Depois de remover, cada pessoa ativa o próprio celular pelo painel (passos 3 a 5).
- Nas automações, troque `config_entry_id` por `device_id`.

### Entidades (por celular)
| Entidade | O que mostra |
|---|---|
| `sensor.alarmeha_<pessoa>_proximo_alarme` | horário do próximo alarme ativo (timestamp) |
| `sensor.alarmeha_<pessoa>_ultimo_evento` | último evento recebido (`tocou`, `soneca`, `desligado`, `lista`...); atributos com os detalhes |
| `sensor.alarmeha_<pessoa>_alarmes` | quantidade de alarmes; atributo `alarmes` com a lista completa |
| `binary_sensor.alarmeha_<pessoa>_conectado` | ligado se o celular mandou algum evento nas últimas 26 h |

Os nomes dos ids seguem o idioma do HA; em inglês, por exemplo, `..._next_alarm`.

A integração pede a lista de alarmes 30 s depois de iniciar, a cada 12 h e depois de cada alarme desligado. Isso mantém os sensores atualizados e funciona como "batimento".

Todo evento do celular também vira o evento `alarmeha_evento` no barramento do HA, com `phone_id`, `user_id`, `pessoa` e os campos da seção 4.

### Agenda (alarmes automáticos)
Na aba **Agenda** do painel, o admin liga agendas do HA (ex.: o iCal secreto do Google adicionado pela integração **Remote Calendar**) a **tipos de evento** (ex.: Trabalho, Remédio).

- **Escolha do tipo:** o tipo vale se a agenda do evento está nele e, se o tipo tem palavras-chave, o título tem uma delas. Tipo com palavra que bate vence o tipo sem palavras; o tipo sem palavras pega o resto; empate fica com o primeiro da lista.
- **Janela (0.11.0):** o plano cobre **hoje + 6 dias** (7 dias; o app repete por dia da semana, então 7 é o limite).
- **Conjuntos:** cada (título do evento, horário do evento) é um conjunto com os N alarmes do tipo. O mesmo título no mesmo horário em vários dias usa os mesmos N alarmes, com os dias da semana marcados (`dias` = soma seg=1 … dom=64). O mesmo título em outro horário vira outro conjunto, no mesmo cartão. Até 10 conjuntos por tipo.
- **A cada sincronização** (00/15/30/45): entram os dias novos da janela e saem os que passaram; um alarme que já tocou hoje só perde o dia de hoje. Conjunto sem ocorrência futura na janela é apagado do celular (o cartão some).
- **Refs estáveis:** `ag_<tipo>_<hash>_<n>`, derivadas de tipo + título + horário + n; não mudam entre sincronizações. As vagas antigas `ag_<tipo>_<n>` (até a 0.10.0) são apagadas na primeira sincronização da 0.11.0, antes de criar as novas.
- **Grupos:** um compromisso com mais de um alarme vira um grupo (`grupo`, `grupo_nome`). Desde a 0.9.2 o grupo é o compromisso (tipo + título do evento), não o dia: o mesmo título hoje e amanhã fica num cartão só, com o título como nome (sem título, o nome do tipo). Tipos com um alarme só (ex.: remédios) não têm grupo e se mesclam por dia da semana quando o horário se repete.
- **Já tocou:** alarme do grupo que já tocou fica desligado e esmaecido no app, com "Tocou às HH:MM", até o último alarme do grupo passar.
- **Quando sincroniza:** nos minutos 00, 15, 30 e 45, 5 s depois de a integração carregar e logo depois de um alarme ser desligado.
- **Guarda:** o HA não mexe num alarme tocando ou em soneca, nem num alarme com toque entre 5 min à frente e 10 min atrás; depois da janela, sincroniza de novo.

### No app
- A lista é ordenada pelo próximo toque.
- Desligar um alarme do HA no celular **pula só o próximo toque** (evento `pulado`); religar desfaz (evento `religado`). Só aparece o botão pertinente.
- Cada compromisso é um cartão (pelo título) com o total de alarmes; dentro dele, **Ativar tudo** / **Desativar tudo** vale para todos os alarmes (pulando só o próximo toque). Grupos do HA não se editam, renomeiam nem recebem alarmes no app.
- **Ainda não incluído:** dispensar um alarme do conjunto desligando os outros do dia (em discussão: risco de dispensar meio dormindo); aviso em tela cheia com confirmação; confirmação de remédio.
- Soneca padrão: 5 min.

### Ações (serviços)
`alarmeha.criar`, `alarmeha.editar`, `alarmeha.ligar`, `alarmeha.desligar`, `alarmeha.apagar` e `alarmeha.listar`. Para o Modo diagnóstico: `alarmeha.diagnostico` (seção 4.1).

- O celular é escolhido por `device_id` (dispositivo AlarmeHA). Se houver um só celular, pode omitir.
- Cada ação espera a confirmação com o mesmo `pedido` (padrão: 45 s, campo `timeout`) e reenvia até 3 vezes.
- Se o celular responder `erro`, ou não confirmar, a ação falha com a mensagem.
- Com `response_variable`, a ação devolve o evento de confirmação (em `listar`, a lista completa).

```yaml
- action: alarmeha.criar
  data:
    device_id: 0123456789abcdef
    ref: usuario_aula
    hora: 6
    minuto: 30
    dias: 31                          # seg a sex
    rotulo: "Aula 07:45"
- action: alarmeha.desligar
  data: { device_id: 0123456789abcdef, ref: usuario_aula }
- action: alarmeha.listar
  response_variable: lista
```

As seções abaixo descrevem o protocolo e o jeito **sem a integração** (só automações YAML).

## 1. Sem a integração: preparar o HA (uma vez por pessoa)

### 1.1 Criar o id do webhook
O id do webhook é um segredo. Gere um aleatório em qualquer computador:

```bash
python3 -c "import secrets; print('alarmeha_' + secrets.token_urlsafe(24))"
```

### 1.2 `secrets.yaml`

```yaml
alarmeha_webhook_usuario: "alarmeha_COLE_AQUI_O_ID_GERADO"
alarmeha_token_usuario: "COLE_AQUI_O_TOKEN_MOSTRADO_PELO_APP"   # preencher no passo 2
```

### 1.3 Automação que recebe os eventos do celular
A automação repassa tudo como evento `alarmeha_evento`. Assim, outras automações e scripts podem esperar pela confirmação de um `pedido`.

```yaml
automation:
  - alias: AlarmeHA - eventos do celular do usuário
    mode: queued
    triggers:
      - trigger: webhook
        webhook_id: !secret alarmeha_webhook_usuario
        allowed_methods: [POST]
        local_only: false        # o app usa a URL externa do HA
    actions:
      - event: alarmeha_evento
        event_data:
          pessoa: usuario
          dados: "{{ trigger.json }}"
      - choose:
          - conditions: "{{ trigger.json.evento == 'tocou' }}"
            sequence:
              - action: persistent_notification.create
                data:
                  title: "Alarme tocou"
                  message: "{{ trigger.json.rotulo }} ({{ trigger.json.ref or 'manual' }}) às {{ trigger.json.quando }}"
          - conditions: "{{ trigger.json.evento == 'erro' }}"
            sequence:
              - action: persistent_notification.create
                data:
                  title: "AlarmeHA: erro"
                  message: "{{ trigger.json.op }} {{ trigger.json.ref }}: {{ trigger.json.motivo }}"
```

## 2. Parear o celular

1. No celular, instale e **abra o AlarmeHA uma vez**. O Android só entrega comandos a apps que já foram abertos.
2. Monte o texto de pareamento com a URL **externa** do HA e o id do webhook:
   ```json
   {"v":1,"url":"http://homeassistant.local:8123","webhook":"alarmeha_COLE_AQUI_O_ID_GERADO","nome":"Usuario"}
   ```
3. Gere um QR code desse texto **sem usar sites externos**, porque o webhook é secreto. Por exemplo, num computador com Linux:
   ```bash
   qrencode -t ANSIUTF8 '{"v":1,"url":"https://...","webhook":"alarmeha_...","nome":"Usuario"}'
   # ou, para uma imagem:  qrencode -o pareamento.png '...'
   ```
   Alternativa sem QR: mande o texto para o celular, copie e use **"Colar configuração"** no app.
4. No AlarmeHA, vá em **Configurações → Home Assistant → Avançado → "Ler QR code"**.
5. O app gera e mostra o **token uma única vez**. Toque em **"Copiar token"** e cole em `alarmeha_token_usuario` no `secrets.yaml`. Depois recarregue a configuração YAML do HA (ou reinicie o HA).
6. O app manda o evento `pareado`. Use **"Testar: enviar lista de alarmes ao HA"** para conferir que o HA recebe os eventos.

Botões do app:
- "Gerar novo token": invalida o token antigo; atualize o `secrets.yaml`.
- "Desativar": apaga a URL, o webhook e o token. Os alarmes continuam no relógio.
- O status mostra quantos comandos foram recusados por token errado.

## 3. Comandos (HA → AlarmeHA)

- `message: command_broadcast_intent`
- `intent_package_name`: `br.casa.alarmeha` (versão de teste: `br.casa.alarmeha.debug`)
- `intent_action`: `br.casa.alarmeha.action.COMANDO`
- `intent_extras`: `nome:valor:tipo`, separados por vírgula, **numa linha só**. Textos com `,` `:` `;` ou acentos vão com `| urlencode` e o tipo `String.urlencoded`.

| Extra | Tipo | Uso |
|---|---|---|
| `token` | String | obrigatório |
| `op` | String | `criar`, `editar`, `ligar`, `desligar`, `apagar`, `listar` |
| `ref` | String | id do alarme no HA: `a-z 0-9 _ -`, até 64 caracteres. Em `ligar`, `desligar` e `apagar`, `*` = todos os criados pelo HA |
| `pedido` | String | id da requisição; volta nos eventos de resposta |
| `hora`, `minuto` | int | `criar` (obrigatórios) e `editar` |
| `dias` | int | soma: seg=1, ter=2, qua=4, qui=8, sex=16, sáb=32, dom=64; `-1` = hoje, `-2` = amanhã. Ex.: seg a sex = 31 |
| `rotulo` | String.urlencoded | nome do alarme (até 64 caracteres) |
| `ativo` | boolean | padrão `true` ao criar |
| `vibrar` | boolean | padrão `false` |
| `unico` | boolean | apaga o alarme depois de tocar (só para alarme sem repetição) |
| `grupo` | String.urlencoded | id do grupo do compromisso (`a-z 0-9 _ -`); vazio ou ausente no plano completo = sem grupo |
| `grupo_nome` | String.urlencoded | nome do grupo mostrado no app (ex.: `Reunião`) |
| `som_uri`, `som_titulo` | String.urlencoded | toque do alarme (o HA usa a lista de `op: toques`) |

`op` também aceita `toques`, que devolve o evento `toques` com os sons disponíveis no celular.

`criar` com um `ref` que já existe vira `editar`.

### 3.1 Script reutilizável com nova tentativa
O script envia o comando, espera o evento de confirmação com o mesmo `pedido` e tenta de novo até 3 vezes.

```yaml
script:
  alarmeha_usuario:
    alias: AlarmeHA - comando para o celular do usuário
    mode: queued
    fields:
      op: { description: "criar | editar | ligar | desligar | apagar | listar" }
      ref: { description: "id do alarme no HA, ex.: usuario_aula" }
      extras: { description: "extras adicionais, ex.: hora:6:int,minuto:30:int,dias:31:int" }
    variables:
      token: !secret alarmeha_token_usuario
      pedido: "{{ now().timestamp() | int }}{{ range(1000, 9999) | random }}"
      linha: >-
        token:{{ token }}:String,op:{{ op }}:String,pedido:{{ pedido }}:String{% if ref is defined and ref %},ref:{{ ref }}:String{% endif %}{% if extras is defined and extras %},{{ extras }}{% endif %}
    sequence:
      - repeat:
          sequence:
            - action: notify.mobile_app_celular_usuario
              data:
                message: command_broadcast_intent
                data:
                  intent_package_name: br.casa.alarmeha
                  intent_action: br.casa.alarmeha.action.COMANDO
                  intent_extras: "{{ linha }}"
            - wait_for_trigger:
                - trigger: event
                  event_type: alarmeha_evento
                  event_data: { pessoa: usuario }
              timeout: "00:02:00"
              continue_on_timeout: true
          until: >-
            {{ (wait.trigger is not none and wait.trigger.event.data.dados.pedido == pedido) or repeat.index >= 3 }}
```

Com vários comandos seguidos, outro evento pode chegar antes da resposta certa. Para o uso normal, isso basta.

### 3.2 Exemplos

```yaml
# Criar (ou atualizar) "Aula" às 06:30, de segunda a sexta
- action: script.alarmeha_usuario
  data:
    op: criar
    ref: usuario_aula
    extras: "hora:6:int,minuto:30:int,dias:31:int,rotulo:{{ 'Aula 07:45' | urlencode }}:String.urlencoded,ativo:true:boolean"

# Mudar só o horário
- action: script.alarmeha_usuario
  data: { op: editar, ref: usuario_aula, extras: "hora:7:int,minuto:0:int" }

# Ligar / desligar / apagar
- action: script.alarmeha_usuario
  data: { op: desligar, ref: usuario_aula }
- action: script.alarmeha_usuario
  data: { op: apagar, ref: "*" }          # todos os criados pelo HA

# Alarme único amanhã às 05:45, apagado depois de tocar
- action: script.alarmeha_usuario
  data:
    op: criar
    ref: usuario_plantao
    extras: "hora:5:int,minuto:45:int,dias:-2:int,unico:true:boolean,rotulo:Plantao:String"

# Pedir a lista
- action: script.alarmeha_usuario
  data: { op: listar }
```

Chamada direta, sem script (o token não pode ficar escrito na automação; use uma variável com `!secret`):

```yaml
- variables:
    token: !secret alarmeha_token_usuario
- action: notify.mobile_app_celular_usuario
  data:
    message: command_broadcast_intent
    data:
      intent_package_name: br.casa.alarmeha
      intent_action: br.casa.alarmeha.action.COMANDO
      intent_extras: "token:{{ token }}:String,op:ligar:String,ref:usuario_aula:String,pedido:manual1:String"
```

## 4. Eventos (AlarmeHA → HA)

O app faz `POST <url externa>/api/webhook/<id>` com JSON. Se a rede falhar, ele tenta de novo com intervalo crescente, até 8 vezes.

Todos os eventos têm: `origem` (`"alarmeha"`), `evento`, `dispositivo`, `pessoa` (o `nome` do QR), `versao` e `quando` (ISO 8601 com fuso).

| `evento` | Campos |
|---|---|
| `tocou` | `ref` (null se manual), `id`, `rotulo`, `hora`, `minuto` |
| `soneca` | idem + `minutos` (padrão 5) |
| `desligado` | idem |
| `criado` / `alterado` | `pedido`, `ref`, `id`, `origem_mudanca` (`"ha"`), `alarme` (objeto completo) |
| `apagado` | `pedido`, `ref`, `id`, `origem_mudanca` |
| `perdido` | idem (0.13.0: tocou até acabar o tempo sem desligar nem adiar; vem no lugar de `desligado`) |
| `pulado` / `religado` | `ref`, `id`, `origem_mudanca` (`"app"`), `origem_pulo` (0.13.0: `switch_lista`, `switch_cartao`, `desativar_tudo` ou `notificacao`), `ocorrencia` (toque pulado, ou null), `alarme` |
| `lista` | `pedido`, `alarmes`: lista de `{ref, id, hora, minuto, dias, rotulo, ativo, vibrar, som_titulo, unico, criado_pelo_ha, proximo_disparo}`; alarmes do HA também têm `pulado` (toque pulado, ISO 8601, ou null), `grupo` e `grupo_nome` |
| `toques` | `pedido`, `toques`: lista de `{uri, titulo}` |
| `erro` | `pedido`, `op`, `ref`, `motivo` (`ref_inexistente`, `ref_invalida`, `ref_ausente`, `horario_invalido`, `dias_invalido`, `hora_ausente`, `op_invalida`, `erro_interno`...) |
| `pareado` | `token_definido` |

Exemplo:

```json
{"origem":"alarmeha","evento":"alterado","dispositivo":"Celular do Usuário","pessoa":"Usuario",
 "versao":"0.7.0","quando":"2026-10-17T21:00:03-03:00","pedido":"17607456031234",
 "ref":"usuario_aula","id":12,"origem_mudanca":"ha",
 "alarme":{"ref":"usuario_aula","id":12,"hora":7,"minuto":0,"dias":31,"rotulo":"Aula 07:45",
 "ativo":true,"vibrar":false,"unico":false,"criado_pelo_ha":true,"proximo_disparo":"2026-10-19T07:00:00-03:00",
 "pulado":null,"grupo":null,"grupo_nome":null}}
```

## 4.1 Modo diagnóstico (0.10.0)

No app: **Configurações → Home Assistant → Modo diagnóstico**. Vem desligado e só desliga quando a pessoa desligar.

Ligado, o app manda o evento `diagnostico` pelo mesmo webhook e pela mesma fila (se o HA estiver fora do ar, espera na fila e vai depois, até 7 dias; o que chega com mais de 24 h de atraso fica só no registro). Campos: `tipo` (`recebido`, `agendado`, `desagendado`, `apagado`, `tocou`, `soneca`, `pulou`, `religado`, `dispensou`, `perdido`, `erro`), `ref`, `id`, `hora`, `minuto`, `dias`, `ativo` e, conforme o tipo, `op`, `pedido`, `motivo`, `mensagem`, `minutos`, `ocorrencia` e, em `pulou`/`religado` (0.13.0), `origem_pulo` e `grupo`. Sempre com `versao` e `quando`. Nunca vai o rótulo do alarme.

No HA, esses eventos não vão para o barramento nem para o histórico: ficam num armazenamento próprio (`.storage/alarmeha.diagnostico`) com os **últimos 500** (os mais antigos saem), preservado ao reiniciar. Nome da pessoa e do aparelho não são gravados.

Leitura (só administradores):

```yaml
- action: alarmeha.diagnostico
  data: { limite: 100 }            # opcional; device_id também é opcional
  response_variable: registro
```

Pela API REST: `POST /api/services/alarmeha/diagnostico?return_response` com um token de administrador. Também aparece em **Dispositivos e serviços → AlarmeHA → ⋮ → Baixar diagnósticos**.

## 5. Limites conhecidos
- Sem rede no celular, o comando não chega. O script do item 3.1 tenta de novo, mas o ideal é mandar as mudanças com antecedência, na noite anterior.
- O comando viaja como push (FCM), que aceita no máximo uns 4 KB; mantenha os comandos curtos.
- Na primeira vez, o app precisa ser aberto manualmente. Também convém tirar o AlarmeHA da otimização de bateria.
- Alarmes criados à mão não são alterados pelo HA, só listados.

`tocou` também aparece no plano interno da agenda (horário em que um alarme do grupo já tocou), mas não é um extra de comando: o app mostra "Tocou às" para alarme do grupo desligado cujo toque já passou.

## 6. Solução de problemas

| Sintoma | O que fazer |
|---|---|
| Sensores do celular parados depois de ativar de novo; ações dão "não confirmou após 3 tentativas" | Era o problema da 0.7.0, corrigido na 0.8.0. Em versão antiga: recarregue a integração (Dispositivos e serviços → AlarmeHA → ⋮ → Recarregar). |
| Nenhum evento chega ao HA | Veja `sensor.alarmeha_<pessoa>_ultimo_evento_recebido` e a notificação "AlarmeHA: celular sem resposta". No app, abra a tela **Home Assistant**: último envio ok, último erro (URL e HTTP/exceção) e itens na fila. Abra a URL externa no navegador do celular, fora do Wi-Fi. Deixe o AlarmeHA em bateria **Sem restrição**. |
| Comandos não chegam ao celular | Mande uma notificação comum para `notify.mobile_app_<celular>`. Se ela não chegar, o problema é no Companion/FCM, não no AlarmeHA. |
| Alarmes do HA sumiram do celular | Na 0.8.0, uma agenda indisponível por alguns minutos podia apagar os alarmes do tipo (corrigido na 0.8.1). Na 0.8.1 o HA recria sozinho os que sumirem; para forçar já, salve o painel ou recarregue a integração. |
| Alarmes duplicados desligados (`ag_<tipo>_<n>` com os mesmos horários) | Sobra de re-pareamento na 0.7.0. Na 0.8.0 o HA apaga as vagas `ag_*` sem uso na sincronização seguinte (00/15/30/45), fora da janela de 5/10 min. |
| Alarme da agenda não mudou | O HA não mexe em alarme tocando, em soneca ou entre 5 min à frente e 10 min atrás; espere a próxima sincronização (00/15/30/45). |

## 7. Novidades da 0.15.0 (app e integração)

- App: a fila de eventos para o HA (quando o HA não responde, por exemplo sem acesso externo fora de casa) agora guarda os eventos por até 7 dias, em vez de 24 horas. Os de mais de 7 dias são descartados.
- Integração: um evento que chega com mais de 24 horas de atraso (campo `quando`) só vai para o registro do diagnóstico, como `atrasado_<evento>` (ex.: `atrasado_tocou`). Ele não vai para o barramento (não dispara automações), não marca o alarme como tocando, não muda pulos nem a lista do celular e não muda o "último evento". Atrasos de até 24 horas continuam valendo como antes.
- App: se o app Home Assistant (Companion) não estiver instalado (versão completa ou `minimal`), a tela "Ative o AlarmeHA" mostra um aviso de que ele é obrigatório, com o botão "Abrir a Play Store" (abre o app da Play Store ou, sem ele, o site). Tocar em Ativar sem o Companion mostra o mesmo aviso. "Não abriu? Ativar manualmente" continua.

## 7.0.1 Novidades da 0.14.1 (só integração)

- Integração: o HA baixa o APK pela API de assets do GitHub e aceita cabeçalhos maiores. Antes, o redirecionamento do GitHub vinha com um cabeçalho grande demais, o download falhava e o painel entregava, sem avisar, a cópia antiga guardada no HA.
- Integração: se o download da versão nova falhar, o painel mostra "não consegui baixar a versão X; este é o APK da Y", com o erro e o link da release. O download pelo painel também traz o cabeçalho `X-AlarmeHA-Aviso`.
- App: sem mudança (0.14.0). A release 0.14.1 traz o mesmo `AlarmeHA.apk` da 0.14.0.

## 7.0.0 Novidades da 0.14.0 (app; integração só o número)

- App: com a tela ligada e desbloqueada, cartão grande do próprio app no topo, por cima de qualquer app (nome, hora, Adiar e Desligar), em vez do aviso do sistema (que em alguns aparelhos vira uma pílula pequena). Fica até uma ação ou até acabar o tempo (`perdido`).
- App: permissão "Sobrepor a outros apps", pedida uma vez e opcional (Configurações > "Aviso por cima dos apps"); sem ela, o aviso do sistema da 0.13.0.
- Integração: só o número; eventos e comandos iguais aos da 0.13.0.
- Ainda não incluído: correção do pulo que trava os dias (A/B); interruptor de alarme que já tocou; dispensar do conjunto; tela cheia com confirmação e "Tomei".

## 7.0.0 Novidades da 0.13.0 (app e integração)

- App: notificação única de alarmes perdidos, acumulando até abrir o app ou limpar; alarme pulado não conta.
- App: com a tela ligada e desbloqueada, aviso grande e fixo no topo (nome, hora, Adiar e Desligar); tela desligada ou bloqueada continua com a tela cheia.
- App: cartão de grupo com margem lateral maior.
- App e integração: evento `perdido`; `origem_pulo` e `grupo` no registro do Modo diagnóstico.
- Ainda não incluído: correção do pulo que trava os dias (A/B); interruptor de alarme que já tocou; dispensar do conjunto; tela cheia com confirmação e "Tomei".

## 7.0.0 Novidades da 0.12.0 (só visual do app)

- App: cores do tema do Fossify em todo o app (cartões, linhas, chaves, selos, divisórias, tela do grupo, cabeçalho); esmaecido com transparência sobre a cor do texto. Respeita "Personalizar aparência" e tema claro/escuro.
- App: cartão de grupo em 3 colunas fixas: ícone à esquerda, título centralizado (até 2 linhas), contagem à direita; os ícones ficam alinhados entre os cartões.
- Integração: só o número (0.12.0); sem mudança funcional.
- Ainda não incluído: dispensar do conjunto; tela cheia com confirmação; confirmação de remédio.

## 7.0.1 Novidades da 0.11.0 (app e integração)

- Integração: janela de 7 dias, conjuntos por título e horário com dias da semana, alarme que tocou só perde o dia de hoje, título fora da janela apagado, refs estáveis (migração automática das antigas), limite por conjunto.
- App: diagnóstico com horário e dias também no comando recebido; tela do grupo sem o espaço vazio; cabeçalho da lista recolhe ao rolar e deixa fixa a linha com título curto, + e ⋮; barra de abas e cores do Fossify original (cartões de grupo e tela do grupo como estavam).
- Compatível com o app 0.10.0: os comandos são os mesmos (`criar` com `dias` em bits, `apagar`).
- Ainda não incluído: dispensar do conjunto; tela cheia com confirmação; confirmação de remédio.

## 7.0.0 Novidades da 0.10.0 (app e integração)

- **Modo diagnóstico** (seção 4.1): chave no app, desligada por padrão; registro técnico dos últimos 500 eventos no HA, fora do histórico, lido pela ação `alarmeha.diagnostico` ou pelos diagnósticos da integração.
- Distribuição só pela release do GitHub e pelo painel; o canal de teste pelo Firebase foi removido.

## 7.0.1 Novidades da 0.9.2 (app e integração)

- **Um cartão por compromisso**, agrupado pelo título do evento (não por dia): "Teste Alarme" na quarta e na quinta = um cartão. O cartão mostra o total de alarmes, sem chave.
- **Linhas por horário:** alarmes do mesmo horário e rótulo viram uma linha com D S T Q Q S S; as vagas por dia continuam e a chave age só no próximo toque.
- Barra de abas em pílula consertada; cartão de grupo como o da Samsung.
- A 0.9.1 só igualou a numeração do app e da integração.
- **Ainda não incluído:** dispensar um alarme do conjunto desligando os outros do dia (em discussão: risco de dispensar meio dormindo); aviso em tela cheia com confirmação; confirmação de remédio.

## 7.0 Novidades da 0.9.0 (app; integração sem mudança funcional)

- **Visual do Relógio da Samsung.** A aba Alarme mostra no alto "Alarme em X horas e Y minutos" e a data do próximo toque, com + e ⋮ logo abaixo.
- **Cartão do grupo** (um por compromisso/tipo do HA): pílula com ícone roxo, o nome do tipo e o total de alarmes. Não tem chave: toque nele para abrir o grupo. Os alarmes manuais ficam embaixo, num cartão único.
- **Dentro do grupo:** "Ativar tudo" / "Desativar tudo" e uma chave por alarme. Desligar um alarme do HA continua sendo "pular o próximo toque" (selo **Pulado** e quando volta); ligar desfaz o pulo. Horário e nome continuam vindo da agenda do HA.
- **Dias da semana:** alarme que repete mostra D S T Q Q S S com os dias ativos em roxo; alarme de uma vez mostra a data por extenso.

## 7.1 Novidades da 0.8.1 (integração)

- **Agenda fora do ar não mexe nos alarmes.** Se alguma agenda de um tipo estiver indisponível, sem estado (ex.: logo depois de reiniciar o HA) ou der erro na leitura, a sincronização pula esse tipo inteiro: nada é apagado, desligado ou editado até a agenda voltar. O log mostra um aviso com a agenda e os tipos afetados. O dia sem compromisso também espera, porque depende de todas as agendas.
- **Reconciliação automática.** Quando a lista do celular chega sem alarmes `ag_*` que o HA enviou ligados, o HA recria só esses (mesma vaga), respeitando a janela de 5/10 min, toque/soneca e pulos. Limite: 1 recriação por alarme a cada 15 min, espaçando se repetir (até 4 h). Celular sem resposta: a sincronização para no primeiro comando sem confirmação e tenta no próximo ciclo.
- **Apagar o que já não existe** (`ref_inexistente`) conta como sucesso e não aparece como erro no último evento.
- **Log:** os comandos enviados aparecem em INFO (operação, ref, horário), sem token.

## 7.2 Novidades da 0.8.0

- **Cartões de grupo:** todo compromisso do HA vira um cartão na lista (nome, próximo toque, ligados/desligados), inclusive os de um alarme só; alarmes manuais ficam soltos. Ordem pelo próximo toque.
- **Tela do grupo** (tocar no cartão): "Desativar tudo" / "Ativar tudo" (os dois quando misto) e a chave de cada alarme. Desativar pula só aquele toque e cancela soneca em andamento; Ativar desfaz. Horário e nome vêm da agenda e não se editam no app.
- **Datas por extenso** ("qua., 21 de out."; recorrente "Toda ter, qua · …"), desligados bem esmaecidos, e **"Pulado"** no lugar de "Tocou às" quando o toque tinha sido pulado.
- **Agrupar no app** (opção de cada tipo, no editor do tipo): "Um cartão por evento" (um cartão por compromisso, juntando os dias; padrão do Trabalho e de tipos novos) ou "Um cartão para o tipo inteiro" (padrão de Remédio/Remédios). Tipos salvos antes recebem o padrão. Trocar só reagrupa: as vagas `ag_<tipo>_<n>` e os horários ficam, e alarmes tocando, em soneca ou na janela de 5/10 min são ajustados depois. A chave de cada alarme continua dentro do grupo.
- **Envio celular → HA:** envio na hora, WorkManager só de reserva, fila de até 7 dias em ordem (até a 0.14: 24 h) (`quando` original), `lista` repetida colapsa na mais recente. A tela Home Assistant do app mostra último envio ok, último erro e a fila; um aviso fixo na lista aparece com bateria restrita, app em espera ou envio falhando.
- **No HA:** sensor `ultimo_evento_recebido` e alerta (notificação persistente + evento `alarmeha_silencio`) após 30 min de silêncio com comando sem resposta ou alarme que deveria ter tocado.
- **Re-pareamento:** mesmo objeto do celular (sensores e pendências seguem), webhooks antigos desregistrados e vagas `ag_*` órfãs apagadas.
