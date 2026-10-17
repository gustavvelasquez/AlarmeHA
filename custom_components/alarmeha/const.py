"""Constantes da integração AlarmeHA."""

DOMAIN = "alarmeha"

CONF_PERSON = "pessoa"
CONF_NOTIFY_SERVICE = "notify_service"
CONF_PACKAGE = "package"
CONF_HA_URL = "ha_url"
CONF_WEBHOOK_ID = "webhook_id"
CONF_TOKEN = "token"

PACKAGES = ["br.casa.alarmeha", "br.casa.alarmeha.debug"]
ACTION_COMANDO = "br.casa.alarmeha.action.COMANDO"

EVENT_ALARMEHA = "alarmeha_evento"
SIGNAL_UPDATE = "alarmeha_update_{}"

DEFAULT_TIMEOUT = 45
MAX_ATTEMPTS = 3
CONNECTED_MAX_AGE_HOURS = 26
HEARTBEAT_HOURS = 12

OPS = ["criar", "editar", "ligar", "desligar", "apagar", "listar"]
