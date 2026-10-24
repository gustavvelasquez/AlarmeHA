# AlarmeHA

> **AlarmeHA** é um fork do [Fossify Clock](https://github.com/FossifyOrg/Clock), baseado no Fossify Clock 1.6.0 (release [1.6.0](https://github.com/FossifyOrg/Clock/releases/tag/1.6.0)).
>
> Identidade própria: versão atual em `gradle.properties` (versionCode no esquema X*10000+Y*100+Z), applicationId `br.casa.alarmeha` (o pacote Kotlin `org.fossify.clock` foi mantido para facilitar merges do upstream).

**Objetivo:** ser o mesmo app de relógio do Fossify, controlado pelo Home Assistant (HA) da casa:

- **HA → app:** comandos (criar, editar, ligar, desligar, apagar, listar) pelo app Home Assistant (Companion), com `command_broadcast_intent`, protegidos por token;
- **app → HA:** eventos por webhook (`lista`, `criado`, `alterado`, `apagado`, `tocou`, `soneca`, `desligado`, `perdido`, `pulado`, `religado`, `erro` e, com o Modo diagnóstico ligado, `diagnostico`), enviados em segundo plano (WorkManager);
- **agenda:** a integração lê a agenda (ex.: iCal secreto do Google pela Remote Calendar) e mantém no celular os alarmes dos próximos 7 dias (marcados com 📅): um conjunto por título e horário do evento, com os dias da semana marcados, num cartão por título (ou um cartão para o tipo inteiro).

**Status:** em uso na casa. Versão publicada: **0.14.0**. Regra: o app e a integração saem sempre com o mesmo número (`gradle.properties`, `manifest.json` e `const.py`), mesmo quando um lado não muda.

**No app (0.9.2, visual do Relógio da Samsung):** um cartão por compromisso, agrupado pelo título (não por dia), mostrando o total de alarmes e sem chave; alarmes do mesmo horário numa linha com D S T Q Q S S; grupos do HA não se editam, renomeiam nem recebem alarmes no app; "Ativar tudo"/"Desativar tudo" pulam só o próximo toque.

**Modo diagnóstico (0.10.0):** chave em Configurações, desligada por padrão. Ligada, o app manda ao HA um registro técnico (comando recebido, agendado, apagado, tocou, soneca, pulou, dispensou, erro), sem dados pessoais. O HA guarda os últimos 500 eventos fora do histórico; leitura pela ação `alarmeha.diagnostico` ou pelos diagnósticos da integração.

**Novidades da 0.11.0:** janela de 7 dias; mesmo título no mesmo horário reaproveita os mesmos alarmes com vários dias marcados; o alarme que tocou só perde o dia de hoje; título sem evento na janela some do celular; refs estáveis. No app: tela do grupo sem o espaço vazio, cabeçalho da lista que recolhe ao rolar e barra de abas e cores do Fossify original.

**Novidades da 0.12.0 (visual):** o app usa as cores do tema do Fossify em tudo (respeita "Personalizar aparência" e tema claro/escuro), no lugar da paleta fixa roxa/preta; cartão de grupo em 3 colunas fixas (ícone, título centralizado em até 2 linhas, contagem).

**Novidades da 0.14.0:** com o celular em uso, o alarme aparece num cartão grande do próprio app no topo, por cima de qualquer app, com Adiar e Desligar (precisa da permissão "Sobrepor a outros apps", pedida uma vez e opcional; sem ela fica o aviso do sistema). Tela desligada continua com a tela cheia.

**Novidades da 0.13.0:** aviso único de alarmes perdidos (acumula até abrir o app ou limpar; evento `perdido` no HA); com o celular em uso, o alarme aparece como aviso grande no topo com Adiar e Desligar (tela desligada continua com a tela cheia); cartão de grupo com mais margem lateral; o registro do Modo diagnóstico diz de onde veio cada pulo.

**Ainda não incluído:** correção do pulo que trava a atualização dos dias; ajuste do interruptor de alarme que já tocou hoje; dispensar um alarme do conjunto desligando os outros do dia (em discussão: risco de dispensar meio dormindo); aviso em tela cheia com confirmação; confirmação de remédio.

**Integração com o Home Assistant (HACS):** este repositório também contém a integração `custom_components/alarmeha`. O fluxo é:
1. o admin instala pelo HACS (Repositórios personalizados → `https://github.com/gustavvelasquez/AlarmeHA`, tipo Integração), reinicia o HA e adiciona a integração **AlarmeHA** uma única vez;
2. cada pessoa abre o painel **Despertador** (`/despertador`) no app Home Assistant do próprio celular;
3. toca em **Baixar e instalar** (o próprio HA entrega o APK da última release);
4. abre o app AlarmeHA, toca em **Ativar**, confirma no painel (**Confirmar ativação**) e depois no app. Não há token para copiar.

Guia completo (instalação, pareamento, agenda, protocolo, solução de problemas): [docs/integracao-home-assistant.md](docs/integracao-home-assistant.md). Histórico: [CHANGELOG.md](CHANGELOG.md). Escopo oficial (feito/pendente/futuro): [docs/ESCOPO.md](docs/ESCOPO.md).

**Problema da 0.7.0** (sensores presos ao pareamento antigo depois de ativar de novo): corrigido na 0.8.0.

**Atualizações do upstream:** as mudanças do Fossify Clock só são trazidas para cá por decisão deliberada, nunca de forma automática.

**Instalação:** pelo painel Despertador ou baixando `AlarmeHA.apk` da [última release](https://github.com/gustavvelasquez/AlarmeHA/releases/latest). A release (`br.casa.alarmeha`) é assinada com a chave própria do AlarmeHA. A distribuição é só pela release do GitHub e pelo painel (não há canal de teste separado).

**Créditos e licença:** todo o mérito do app original é do projeto [Fossify](https://github.com/FossifyOrg). Este fork mantém a licença GPL-3.0 (veja `LICENSE`). O texto original do Fossify Clock segue abaixo.

---

# Fossify Clock
<img alt="Logo" src="graphics/icon.webp" width="120" />

<a href='https://play.google.com/store/apps/details?id=org.fossify.clock'><img alt='Get it on Google Play' src='https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png' height=80/></a> <a href="https://f-droid.org/packages/org.fossify.clock/"><img src="https://fdroid.gitlab.io/artwork/badge/get-it-on-en.svg" alt="Get it on F-Droid" height=80/></a> <a href="https://apt.izzysoft.de/fdroid/index/apk/org.fossify.clock"><img src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroid.png" alt="Get it on IzzyOnDroid" height=80/></a>

Introducing Fossify Clock – the ultimate timekeeping companion designed to enhance your daily routines and promote better sleep habits. With a multitude of functions tailored to your needs, Fossify Clock seamlessly integrates into your life, offering unparalleled convenience and versatility.

**⌚ MULTIFUNCTIONAL TIMEKEEPING:**  
Experience the power of versatile time management with Fossify Clock. From serving as a clock widget to functioning as an alarm clock and stopwatch, this app is your go-to tool for regulating your daily activities and improving your overall lifestyle.

**⏰ FEATURE-RICH ALARM:**  
Wake up refreshed with Fossify Clock's comprehensive alarm features. Set multiple alarms with options like day selection, vibration toggle, custom labels and ringtone customization. Enjoy gradual volume increase and a customizable snooze button for a pleasant waking experience. With a user-friendly interface, setting up alarms has never been easier.

**⏱️ CONVENIENT STOPWATCH:**  
Track your activities with precision using Fossify Clock's stopwatch function. Measure longer periods or individual laps effortlessly. You can also sort your laps in ascending or descending order.

**⏳ PRECISE TIMER FUNCTIONALITY:**  
Stay on top of your tasks with Fossify Clock's versatile timer feature. Customize ringtone preferences, toggle vibrations, and pause countdowns to suit your needs. Whether you're timing cooking intervals, managing study sessions, or ensuring timely breaks, Fossify Clock has you covered with precision and ease.

**🌈 CLOCK WIDGET WITH CUSTOMIZABLE FEATURES:**  
Transform your home screen with Fossify Clock's customizable clock widget. Adjust text color, background color, and transparency. Choose between analog or digital clock to suit your style and easily access essential time information at a glance.

**🎨 CUSTOMIZABLE INTERFACE AND THEMES:**  
Enjoy a personalized experience with Fossify Clock's material design and dark theme options. Tailor the app to your preferences with customizable colors and themes, enhancing usability and reducing eye strain, especially in low-light environments.

**🔒 PRIVACY-FIRST APPROACH:**  
Rest assured knowing your privacy is protected with Fossify Clock's offline operation. Experience maximum privacy, security, and stability without sacrificing functionality or convenience.

**🌐 AD-FREE & OPEN-SOURCE:**  
Say goodbye to intrusive ads and unnecessary permissions. Fossify Clock is ad-free, fully open-source, and grants you complete control over your timekeeping experience.

Upgrade your time management skills, optimize your routines, and prioritize better sleep with Fossify Clock. Download now and take control of your time like never before.

➡️ Explore more Fossify apps: https://www.fossify.org<br>
➡️ Open-Source Code: https://www.github.com/FossifyOrg<br>
➡️ Join the community on Reddit: https://www.reddit.com/r/Fossify<br>
➡️ Connect on Telegram: https://t.me/Fossify

<div align="center">
<img alt="App image" src="fastlane/metadata/android/en-US/images/phoneScreenshots/1_en-US.png" width="30%">
<img alt="App image" src="fastlane/metadata/android/en-US/images/phoneScreenshots/2_en-US.png" width="30%">
<img alt="App image" src="fastlane/metadata/android/en-US/images/phoneScreenshots/3_en-US.png" width="30%">
</div>

