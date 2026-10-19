"""Constantes da integração AlarmeHA."""

DOMAIN = "alarmeha"
VERSION = "0.5.7"

CONF_HA_URL = "ha_url"
# Chaves da versão 1 (uma entrada por celular), usadas só na migração.
LEGACY_PERSON = "pessoa"
LEGACY_NOTIFY_SERVICE = "notify_service"
LEGACY_PACKAGE = "package"
LEGACY_WEBHOOK_ID = "webhook_id"
LEGACY_TOKEN = "token"
LEGACY_PHONE = "legacy_phone"

PACKAGES = ["br.casa.alarmeha", "br.casa.alarmeha.debug"]
ACTION_COMANDO = "br.casa.alarmeha.action.COMANDO"

PANEL_URL = "despertador"
PANEL_TITLE = "Despertador"
PANEL_ICON = "mdi:alarm"
STATIC_URL = "/alarmeha_static"
ACTIVATE_URL = "/api/alarmeha/ativar"
APK_PATH = "/api/alarmeha/apk"
APK_CACHE_HOURS = 6
GITHUB_LATEST_API = "https://api.github.com/repos/gustavvelasquez/AlarmeHA/releases/latest"
RELEASES_URL = "https://github.com/gustavvelasquez/AlarmeHA/releases/latest"
APK_URL = "https://github.com/gustavvelasquez/AlarmeHA/releases/latest/download/AlarmeHA.apk"

EVENT_ALARMEHA = "alarmeha_evento"
SIGNAL_UPDATE = "alarmeha_update_{}"
SIGNAL_NEW_PHONE = "alarmeha_new_phone"

CODE_TTL_SECONDS = 600
MAX_FAILED_ACTIVATIONS = 30
DEFAULT_TIMEOUT = 45
MAX_ATTEMPTS = 3
CONNECTED_MAX_AGE_HOURS = 26
HEARTBEAT_HOURS = 12

OPS = ["criar", "editar", "ligar", "desligar", "apagar", "listar"]
