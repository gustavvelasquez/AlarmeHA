package org.fossify.clock.ha

/**
 * Regras puras da fila de envio ao HA (sem Android: testadas em src/test).
 *
 * Nome do arquivo: "<epoch ms, 13 dígitos>_<seq 6 dígitos>_<evento>_<hash do webhook>.json".
 * Arquivos antigos (0.7.0 e antes): "<epoch ms>-<uuid>.json" (sem evento/hash: valem para o webhook atual).
 * - Ordem de envio = ordem do nome (horário de criação), um de cada vez; parou no erro, espera.
 * - Mais de 24 h na fila: descarta (o HA já não teria o que fazer com ele).
 * - De outro webhook (pareamento antigo): descarta.
 * - Uma "lista" nova substitui as "lista" ainda não enviadas (só a mais recente importa).
 */
object HaOutboxLogic {
    const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

    data class Entry(val name: String, val createdAt: Long?, val event: String?, val webhookHash: String?)

    fun parse(name: String): Entry {
        val base = name.removeSuffix(".json")
        val parts = base.split("_")
        if (parts.size >= 4 && parts[0].length == 13) {
            return Entry(name, parts[0].toLongOrNull(), parts[2], parts.last())
        }
        return Entry(name, base.substringBefore("-").toLongOrNull(), null, null)
    }

    fun fileName(createdAt: Long, seq: Int, event: String, webhookHash: String): String =
        "%013d_%06d_%s_%s.json".format(createdAt, seq % 1_000_000, event.filter { it.isLetterOrDigit() }.ifEmpty { "x" }, webhookHash)

    data class Plan(val send: List<String>, val drop: List<String>)

    fun plan(names: List<String>, now: Long, currentHash: String): Plan {
        val send = ArrayList<String>()
        val drop = ArrayList<String>()
        names.map { parse(it) }.sortedBy { it.name }.forEach {
            val old = it.createdAt == null || now - it.createdAt > MAX_AGE_MS
            val otherWebhook = it.webhookHash != null && it.webhookHash != currentHash
            if (old || otherWebhook) drop.add(it.name) else send.add(it.name)
        }
        return Plan(send, drop)
    }

    /** Ao enfileirar [event]: quais pendentes saem (só "lista" colapsa). */
    fun superseded(names: List<String>, event: String): List<String> =
        if (event != "lista") emptyList() else names.filter { parse(it).event == "lista" }

    fun hash(webhookId: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(webhookId.toByteArray())
            .take(4).joinToString("") { "%02x".format(it) }
}
