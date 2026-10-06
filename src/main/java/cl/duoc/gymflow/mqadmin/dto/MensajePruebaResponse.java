package cl.duoc.gymflow.mqadmin.dto;

/** Resultado de publicar un mensaje de prueba: {@code routed} indica si llegó al menos a una cola. */
public record MensajePruebaResponse(String eventId, String type, String exchange, String routingKey, boolean routed) {
}
