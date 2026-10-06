package cl.duoc.gymflow.mqadmin.dto;

/**
 * Estado de una cola de GymFlow según la API de Management. Los contadores se actualizan cada ~5 s
 * (intervalo de estadísticas de RabbitMQ), no al instante.
 *
 * @param messages     total = listos + entregados sin confirmar
 * @param ready        esperando consumidor
 * @param unacked      entregados a un consumidor que todavía no hace ACK/NACK
 * @param dlqDe        si es una DLQ, la cola principal a la que pertenece; si no, {@code null}
 */
public record ColaResponse(String name, String node, long messages, long ready, long unacked, int consumers,
                           String dlqDe) {
}
