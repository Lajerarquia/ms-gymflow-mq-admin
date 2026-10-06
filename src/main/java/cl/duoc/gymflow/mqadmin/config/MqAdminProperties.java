package cl.duoc.gymflow.mqadmin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración de ms-gymflow-mq-admin ({@code gymflow.mq-admin.*}).
 *
 * @param managementUrl    URL base de la API HTTP de RabbitMQ Management, p. ej. {@code http://10.0.1.20:15672}
 * @param reprocesoMaximo  tope de mensajes que mueve un reproceso por llamada
 */
@ConfigurationProperties(prefix = "gymflow.mq-admin")
public record MqAdminProperties(String managementUrl, int reprocesoMaximo) {
}
