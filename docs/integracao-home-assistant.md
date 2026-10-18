# Integração AlarmeHA ↔ Home Assistant

O Home Assistant (HA) controla os alarmes do AlarmeHA. O caminho é **HA → app Companion → AlarmeHA**, pelo comando `command_broadcast_intent`. O AlarmeHA responde por um **webhook** do HA.

- **Ida:** o HA pede para criar, editar, ligar, desligar, apagar ou listar alarmes.
- **Volta:** o AlarmeHA avisa quando um alarme tocou, entrou em soneca ou foi desligado, confirma as mudanças e manda a lista de alarmes.
- **Segurança:** todo comando leva um **token** (gerado pelo HA na ativação pelo painel, ou pelo app no modo manual). Comando sem o token certo é ignorado em silêncio.
- **Regra:** o HA só edita ou apaga alarmes que ele mesmo criou (com `ref`). Alarmes criados à mão no celular aparecem só na lista.
- **Som:** sempre o padrão do app.
- **Webhook:** um por pessoa (ex.: um para o Usuario e outro para a Segunda pessoa).

## 0. Jeito recomendado: integração HACS "AlarmeHA" + painel Despertador

Este repositório também traz uma integração para o Home Assistant (`custom_components/alarmeha`). Ela faz pelo HA o que as seções 1 a 4 fazem à mão: webhook, token, comandos com novas tentativas e sensores. Desde a versão 0.3.0 ninguém copia token: cada pessoa ativa o próprio celular pelo painel **Despertador**.

### Passo a passo
1. **Admin, uma vez:** HACS → menu ⋮ → **Repositórios personalizados** → URL `https://github.com/gustavvelasquez/AlarmeHA`, tipo **Integração** → Adicionar. Procure **AlarmeHA** → Baixar → reinicie o HA.
2. **Admin, uma vez:** Configurações → Dispositivos e serviços → **Adicionar integração** → **AlarmeHA**. Confirme a **URL externa do HA** (a que os celulares usam, também fora de casa). Só existe uma entrada para a casa toda.
3. **Cada pessoa, no próprio celular:** entre no app **Home Assistant (Companion)** com a sua conta e abra o painel **Despertador** na barra lateral.
4. **Baixar e instalar:** toque em **Baixar e instalar**. O próprio HA entrega o APK (`/api/alarmeha/apk`, link assinado de curta duração), que ele baixa da última release do GitHub e guarda em cache. Se o Play Protect avisar, toque em **Instalar mesmo assim**. Se o download falhar, o painel tem o link para a página da release.
5. **Abra o app AlarmeHA e toque em Ativar.** O app abre o painel no Companion (`homeassistant://navigate/despertador?ativar=1`). Só nesse caso o painel mostra o cartão **Ativar este celular**: ele gera o código sozinho e mostra o botão grande **Confirmar ativação**. O AlarmeHA pergunta **"Ativar com o HA da casa?"** → Ativar.
   - O código é de **uso único** e vale 10 minutos. O app troca o código, em `POST /api/alarmeha/ativar`, pelo webhook e pelo token gerados no HA. O app guarda só o hash do token.
   - O HA acha o celular do Companion pelo usuário logado e cria o dispositivo **AlarmeHA &lt;pessoa&gt;** sozinho.
   - Se não voltar para o app: no AlarmeHA, Configurações → Home Assistant → Avançado → **Colar configuração** com o texto `{"v":2,...}` que o cartão mostra.
   - **Ativar com o Home Assistant** também fica no menu ⋮ do app e em Configurações → Home Assistant. Sem o app Home Assistant instalado, o AlarmeHA avisa e oferece a Play Store.
6. Pronto: o painel lista os alarmes desse celular e permite criar, editar, ligar/desligar e apagar.

Na primeira abertura, o AlarmeHA oferece **Ativar**, que abre o painel no Companion. O app também mostra o próximo alarme do sistema, para você recriá-lo no AlarmeHA e **desativar os alarmes do relógio antigo**.

**Quem vê o quê:** usuários comuns só veem e controlam o próprio celular, no painel e nas ações. Admins veem todos. Automações (sem usuário) podem mirar qualquer celular.

**Ativar de novo** (troca de celular, reinstalação ou token perdido): repita o passo 5 a partir do app. O mesmo dispositivo é reaproveitado e o webhook e o token antigos deixam de valer. No painel, **Desvincular** remove o celular do HA.

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

### Ações (serviços)
`alarmeha.criar`, `alarmeha.editar`, `alarmeha.ligar`, `alarmeha.desligar`, `alarmeha.apagar` e `alarmeha.listar`.

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
| `soneca` | idem + `minutos` |
| `desligado` | idem |
| `criado` / `alterado` | `pedido`, `ref`, `id`, `origem_mudanca` (`"ha"`), `alarme` (objeto completo) |
| `apagado` | `pedido`, `ref`, `id`, `origem_mudanca` |
| `lista` | `pedido`, `alarmes`: lista de `{ref, id, hora, minuto, dias, rotulo, ativo, vibrar, unico, criado_pelo_ha, proximo_disparo}` |
| `erro` | `pedido`, `op`, `ref`, `motivo` (`ref_inexistente`, `ref_invalida`, `ref_ausente`, `horario_invalido`, `dias_invalido`, `hora_ausente`, `op_invalida`, `erro_interno`...) |
| `pareado` | `token_definido` |

Exemplo:

```json
{"origem":"alarmeha","evento":"alterado","dispositivo":"Celular do Usuário","pessoa":"Usuario",
 "versao":"0.2.0","quando":"2026-10-17T21:00:03-03:00","pedido":"17607456031234",
 "ref":"usuario_aula","id":12,"origem_mudanca":"ha",
 "alarme":{"ref":"usuario_aula","id":12,"hora":7,"minuto":0,"dias":31,"rotulo":"Aula 07:45",
 "ativo":true,"vibrar":false,"unico":false,"criado_pelo_ha":true,"proximo_disparo":"2026-10-19T07:00:00-03:00"}}
```

## 5. Limites conhecidos
- Sem rede no celular, o comando não chega. O script do item 3.1 tenta de novo, mas o ideal é mandar as mudanças com antecedência, na noite anterior.
- O comando viaja como push (FCM), que aceita no máximo uns 4 KB; mantenha os comandos curtos.
- Na primeira vez, o app precisa ser aberto manualmente. Também convém tirar o AlarmeHA da otimização de bateria.
- Alarmes criados à mão não são alterados pelo HA, só listados.
