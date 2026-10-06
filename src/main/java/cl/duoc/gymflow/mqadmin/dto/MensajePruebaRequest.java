package cl.duoc.gymflow.mqadmin.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de POST /api/mq/test-messages. Se envuelve en el envelope común antes de publicar.
 *
 * @param exchange   {@code cmd.direct} o {@code cmd.topic}
 * @param routingKey p. ej. {@code email.send}, {@code checkin.ticket.created}, {@code invoice.gen}
 * @param type       tipo del comando (por defecto {@code MENSAJE_PRUEBA}, que notify no conoce: termina en la DLQ)
 * @param eventId    opcional; repetir uno sirve para probar la idempotencia de notify
 * @param payload    opcional; datos del comando (por defecto {@code {}})
 */
public record MensajePruebaRequest(
        @NotBlank(message = "El exchange es obligatorio")
        String exchange,

        @NotBlank(message = "La routing key es obligatoria")
        @Size(max = 255, message = "La routing key no puede superar 255 caracteres")
        String routingKey,

        @Pattern(regexp = "[A-Z0-9_]{1,64}", message = "El tipo debe ser MAYUSCULAS_CON_GUION_BAJO (máx. 64)")
        String type,

        @Size(max = 64, message = "El eventId no puede superar 64 caracteres")
        String eventId,

        JsonNode payload) {
}
