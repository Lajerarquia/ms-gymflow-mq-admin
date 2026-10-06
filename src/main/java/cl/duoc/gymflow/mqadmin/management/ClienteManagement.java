package cl.duoc.gymflow.mqadmin.management;

import cl.duoc.gymflow.mqadmin.config.MqAdminProperties;
import cl.duoc.gymflow.mqadmin.error.ErrorApi;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Cliente de la API HTTP de RabbitMQ Management (la misma que usa la Management UI en el puerto 15672).
 * Usa el mismo usuario que AMQP. El vhost "/" va como {@code %2F} en la URL; por eso se arman URI absolutas,
 * para que no se vuelva a codificar.
 */
@Component
public class ClienteManagement {

    /** Columnas que se piden, para no traer el detalle completo de cada cola. */
    private static final String COLUMNAS = "name,node,messages,messages_ready,messages_unacknowledged,consumers";

    private final RestClient rest;
    private final String base;

    public ClienteManagement(MqAdminProperties propiedades, RabbitProperties rabbit, RestClient.Builder builder) {
        this.base = propiedades.managementUrl().replaceAll("/+$", "");
        // HTTP/1.1 fijo: por defecto el HttpClient del JDK intenta subir a HTTP/2 sin TLS (h2c) y el servidor de la
        // API de Management corta algunos POST con "EOF reached while reading".
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory fabrica = new JdkClientHttpRequestFactory(http);
        fabrica.setReadTimeout(Duration.ofSeconds(10));
        this.rest = builder
                .requestFactory(fabrica)
                .defaultHeaders(h -> h.setBasicAuth(rabbit.getUsername(), rabbit.getPassword() == null ? "" : rabbit.getPassword()))
                .build();
    }

    public List<ColaManagement> colas() {
        return llamar(() -> rest.get()
                .uri(URI.create(base + "/api/queues/%2F?columns=" + COLUMNAS))
                .retrieve()
                .body(new ParameterizedTypeReference<List<ColaManagement>>() {
                }));
    }

    /**
     * Publica un mensaje en un exchange. Devuelve {@code true} si RabbitMQ lo enrutó a al menos una cola.
     * (La API de Management lo informa en la respuesta: {@code {"routed": true}}.)
     */
    public boolean publicar(String exchange, String routingKey, String cuerpoJson, Map<String, Object> propiedades) {
        Map<String, Object> peticion = Map.of(
                "properties", propiedades,
                "routing_key", routingKey,
                "payload", cuerpoJson,
                "payload_encoding", "string");
        String exchangeEnUrl = URLEncoder.encode(exchange, StandardCharsets.UTF_8);
        Map<?, ?> respuesta = llamar(() -> rest.post()
                .uri(URI.create(base + "/api/exchanges/%2F/" + exchangeEnUrl + "/publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(peticion)
                .retrieve()
                .body(Map.class));
        return respuesta != null && Boolean.TRUE.equals(respuesta.get("routed"));
    }

    private <T> T llamar(java.util.function.Supplier<T> llamada) {
        try {
            return llamada.get();
        } catch (RestClientResponseException respuesta) {
            HttpStatus status = HttpStatus.resolve(respuesta.getStatusCode().value());
            if (status == HttpStatus.NOT_FOUND) {
                throw new ErrorApi(HttpStatus.NOT_FOUND, "RabbitMQ no encontró el recurso (¿existe el exchange?)", respuesta);
            }
            if (status == HttpStatus.UNAUTHORIZED) {
                throw new ErrorApi(HttpStatus.SERVICE_UNAVAILABLE,
                        "La API de Management rechazó las credenciales de RabbitMQ", respuesta);
            }
            throw new ErrorApi(HttpStatus.SERVICE_UNAVAILABLE,
                    "La API de Management respondió " + respuesta.getStatusCode().value(), respuesta);
        } catch (ResourceAccessException sinConexion) {
            throw new ErrorApi(HttpStatus.SERVICE_UNAVAILABLE,
                    "No se pudo conectar con la API de Management de RabbitMQ", sinConexion);
        }
    }

    /** Lo que se usa de cada cola (los contadores pueden faltar si la cola es nueva). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ColaManagement(String name, String node, Long messages,
                                 @JsonProperty("messages_ready") Long messagesReady,
                                 @JsonProperty("messages_unacknowledged") Long messagesUnacknowledged,
                                 Integer consumers) {
    }
}
