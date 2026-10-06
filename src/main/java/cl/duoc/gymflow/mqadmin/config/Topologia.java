package cl.duoc.gymflow.mqadmin.config;

import java.util.Map;
import java.util.Set;

/**
 * Nombres de la topología del caso (sección 8). mq-admin no declara nada (el dueño es ms-gymflow-notify): solo
 * los usa para limitar qué se puede hacer desde esta API.
 */
public final class Topologia {

    /** Prefijo de las colas de GymFlow; el listado muestra solo estas. */
    public static final String PREFIJO_COLAS = "q.cmd.";

    /** Exchanges donde se permite publicar un mensaje de prueba (no el DLX: a la DLQ solo se llega por NACK). */
    public static final Set<String> EXCHANGES_PUBLICABLES = Set.of("cmd.direct", "cmd.topic");

    /** DLQ que se pueden reprocesar y la cola principal a la que vuelven sus mensajes. */
    public static final Map<String, String> DLQ_A_COLA = Map.of(
            "q.cmd.email.dlq", "q.cmd.email",
            "q.cmd.checkin.dlq", "q.cmd.checkin",
            "q.cmd.invoice.dlq", "q.cmd.invoice");

    private Topologia() {
    }
}
