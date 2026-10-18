# AlarmeHA

> **AlarmeHA** é um fork do [Fossify Clock](https://github.com/FossifyOrg/Clock), baseado no Fossify Clock 1.6.0 (release [1.6.0](https://github.com/FossifyOrg/Clock/releases/tag/1.6.0)).
>
> Identidade própria: versão atual em `gradle.properties` (versionCode no esquema X*10000+Y*100+Z), applicationId `br.casa.alarmeha` (o pacote Kotlin `org.fossify.clock` foi mantido para facilitar merges do upstream).

**Objetivo:** ser o mesmo app de relógio do Fossify, com integração ao Home Assistant:

- controle externo de alarmes (criar, ligar, desligar) protegido por token;
- webhooks para o Home Assistant (alarme disparou, soneca, confirmação etc.).

**Status:** em desenvolvimento.

**Integração com o Home Assistant (HACS):** este repositório também contém a integração `custom_components/alarmeha`. O fluxo é:
1. o admin instala pelo HACS (Repositórios personalizados → `https://github.com/gustavvelasquez/AlarmeHA`, tipo Integração), reinicia o HA e adiciona a integração **AlarmeHA** uma única vez;
2. cada pessoa abre o painel **Despertador** no app Home Assistant do próprio celular;
3. toca em **Baixar e instalar** (o próprio HA entrega o APK da última release);
4. abre o app AlarmeHA, toca em **Ativar**, confirma no painel (**Confirmar ativação**) e depois no app. Não há token para copiar.

Guia completo: [docs/integracao-home-assistant.md](docs/integracao-home-assistant.md). A versão da integração (`custom_components/alarmeha/manifest.json`) acompanha as tags de release do GitHub (ex.: release `v0.3.4` = integração 0.3.4); a versão do app Android fica em `gradle.properties` e pode ser diferente.

**Atualizações do upstream:** as mudanças do Fossify Clock só são trazidas para cá por decisão deliberada, nunca de forma automática.

**Instalação:** baixe `AlarmeHA.apk` da [última release](https://github.com/gustavvelasquez/AlarmeHA/releases/latest) (ou pelo painel Despertador). A release é assinada com a chave própria do AlarmeHA; builds de teste (`.debug`) são distribuídos pelo Firebase App Distribution.

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

