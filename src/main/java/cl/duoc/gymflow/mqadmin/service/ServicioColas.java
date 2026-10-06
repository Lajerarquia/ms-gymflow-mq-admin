package cl.duoc.gymflow.mqadmin.service;

import cl.duoc.gymflow.mqadmin.config.Topologia;
import cl.duoc.gymflow.mqadmin.dto.ColaResponse;
import cl.duoc.gymflow.mqadmin.dto.MensajePruebaRequest;
import cl.duoc.gymflow.mqadmin.dto.MensajePruebaResponse;
import cl.duoc.gymflow.mqadmin.error.ErrorApi;
import cl.duoc.gymflow.mqadmin.management.ClienteManagement;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Lista las colas de GymFlow y publica mensajes de prueba (ambas cosas por la API de Management). */
@Service
public class ServicioColas {

    public static final String TIPO_POR_DEFECTO = "MENSAJE_PRUEBA";

    private static final Logger log = LoggerFactory.getLogger(ServicioColas.class);

    private final ClienteManagement management;
    private final ObjectMapper objectMapper;

    public ServicioColas(ClienteManagement management, ObjectMapper objectMapper) {
        this.management = management;
        this.objectMapper = objectMapper;
    }

    public List<ColaResponse> listar() {
        Map<String, String> colaDeCadaDlq = Topologia.DLQ_A_COLA;
        return management.colas().stream()
                .filter(cola -> cola.name() != null && cola.name().startsWith(Topologia.PREFIJO_COLAS))
                .map(cola -> new ColaResponse(cola.name(), cola.node(), cero(cola.messages()), cero(cola.messagesReady()),
                        cero(cola.messagesUnacknowledged()), cola.consumers() == null ? 0 : cola.consumers(),
                        colaDeCadaDlq.get(cola.name())))
                .sorted(Comparator.comparing(ColaResponse::name))
                .toList();
    }

    /**
     * Arma el envelope común y lo publica. Si RabbitMQ no lo enruta a ninguna cola, responde 422: el mensaje se
     * descartó (por ejemplo, una routing key sin binding).
     */
    public MensajePruebaResponse publicarPrueba(MensajePruebaRequest datos) {
        if (!Topologia.EXCHANGES_PUBLICABLES.contains(datos.exchange())) {
            throw new ErrorApi(HttpStatus.BAD_REQUEST,
                    "Solo se puede publicar en " + String.join(" o ", Topologia.EXCHANGES_PUBLICABLES.stream().sorted().toList()));
        }
        String tipo = datos.type() == null ? TIPO_POR_DEFECTO : datos.type();
        String eventId = datos.eventId() == null || datos.eventId().isBlank() ? UUID.randomUUID().toString() : datos.eventId().trim();

        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("type", tipo);
        envelope.put("eventId", eventId);
        envelope.put("timestamp", Instant.now().toString());
        envelope.put("traceId", "mq-admin-" + UUID.randomUUID());
        envelope.put("correlationId", "mq-admin-prueba");
        envelope.set("payload", datos.payload() == null ? objectMapper.createObjectNode() : datos.payload());

        Map<String, Object> propiedades = Map.of(
                "content_type", "application/json",
                "delivery_mode", 2,
                "message_id", eventId,
                "type", tipo,
                "correlation_id", "mq-admin-prueba");
        boolean enrutado = management.publicar(datos.exchange(), datos.routingKey(), json(envelope), propiedades);
        log.info("Mensaje de prueba {} eventId={} en {} ({}): {}", tipo, eventId, datos.exchange(), datos.routingKey(),
                enrutado ? "enrutado" : "SIN COLA DE DESTINO");
        if (!enrutado) {
            throw new ErrorApi(HttpStatus.UNPROCESSABLE_ENTITY, "El mensaje no llegó a ninguna cola: ninguna está enlazada a "
                    + datos.exchange() + " con la routing key '" + datos.routingKey() + "'");
        }
        return new MensajePruebaResponse(eventId, tipo, datos.exchange(), datos.routingKey(), true);
    }

    private String json(ObjectNode nodo) {
        try {
            return objectMapper.writeValueAsString(nodo);
        } catch (JsonProcessingException imposible) {
            throw new IllegalStateException(imposible);
        }
    }

    private static long cero(Long valor) {
        return valor == null ? 0 : valor;
    }
}
