package cl.duoc.gymflow.mqadmin.service;

import cl.duoc.gymflow.mqadmin.config.MqAdminProperties;
import cl.duoc.gymflow.mqadmin.config.Topologia;
import cl.duoc.gymflow.mqadmin.dto.ReprocesoResponse;
import cl.duoc.gymflow.mqadmin.error.ErrorApi;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Devuelve los mensajes de una DLQ a su cola principal para que ms-gymflow-notify los procese otra vez
 * (por ejemplo, después de corregir el problema que los hizo fallar).
 * <p>
 * Por cada mensaje, en un canal propio con publisher confirms:
 * <ol>
 *   <li>{@code basicGet} sin auto-ack: el mensaje queda "tomado" pero sigue en la DLQ;</li>
 *   <li>se publica igual (cuerpo y propiedades) en la cola principal, por el exchange por defecto;</li>
 *   <li>se espera la confirmación de RabbitMQ;</li>
 *   <li>recién entonces {@code basicAck} en la DLQ.</li>
 * </ol>
 * Si algo falla a la mitad, el canal se cierra sin ACK y RabbitMQ devuelve el mensaje a la DLQ: no se pierde.
 * (Lo peor que puede pasar es que quede en ambas colas; notify descarta el duplicado por {@code eventId}.)
 */
@Service
public class ReprocesadorDlq {

    private static final Logger log = LoggerFactory.getLogger(ReprocesadorDlq.class);
    private static final long ESPERA_CONFIRMACION_MS = 5000;
    /** Cuántas veces se ha reprocesado el mensaje, para verlo en la Management UI o en el log de notify. */
    static final String CABECERA_REPROCESOS = "x-gymflow-reprocesos";

    private final ConnectionFactory connectionFactory;
    private final int maximo;

    public ReprocesadorDlq(ConnectionFactory connectionFactory, MqAdminProperties propiedades) {
        this.connectionFactory = connectionFactory;
        this.maximo = propiedades.reprocesoMaximo();
    }

    public ReprocesoResponse reprocesar(String dlq, Integer max) {
        String destino = Topologia.DLQ_A_COLA.get(dlq);
        if (destino == null) {
            throw new ErrorApi(HttpStatus.NOT_FOUND, "'" + dlq + "' no es una DLQ de GymFlow. Opciones: "
                    + String.join(", ", Topologia.DLQ_A_COLA.keySet().stream().sorted().toList()));
        }
        int tope = max == null ? maximo : max;
        if (tope < 1 || tope > maximo) {
            throw new ErrorApi(HttpStatus.BAD_REQUEST, "El parámetro 'max' debe estar entre 1 y " + maximo);
        }

        try (Connection conexion = connectionFactory.createConnection();
             Channel canal = conexion.createChannel(false)) {
            int enDlq = exigirQueExista(canal, dlq);
            exigirQueExista(canal, destino);
            canal.confirmSelect();

            // Solo los mensajes que había al empezar. Si uno vuelve a fallar, notify lo devuelve a la DLQ al instante;
            // sin este límite el reproceso lo tomaría otra vez y rebotaría hasta llegar al tope. Como la cola es FIFO,
            // los que regresan quedan detrás de los originales y no se alcanzan a tomar.
            int limite = Math.min(tope, enDlq);
            int movidos = 0;
            while (movidos < limite) {
                GetResponse mensaje = canal.basicGet(dlq, false);
                if (mensaje == null) {
                    break;
                }
                canal.basicPublish("", destino, false, conCabeceraDeReproceso(mensaje.getProps()), mensaje.getBody());
                canal.waitForConfirmsOrDie(ESPERA_CONFIRMACION_MS);
                canal.basicAck(mensaje.getEnvelope().getDeliveryTag(), false);
                movidos++;
            }
            long quedan = canal.messageCount(dlq);
            log.info("Reproceso de {}: {} mensajes movidos a {}; quedan {}", dlq, movidos, destino, quedan);
            return new ReprocesoResponse(dlq, destino, movidos, quedan);
        } catch (IOException | TimeoutException fallo) {
            throw new ErrorApi(HttpStatus.SERVICE_UNAVAILABLE,
                    "No se pudo completar el reproceso de " + dlq + "; los mensajes no movidos siguen en la DLQ", fallo);
        } catch (InterruptedException interrumpido) {
            Thread.currentThread().interrupt();
            throw new ErrorApi(HttpStatus.SERVICE_UNAVAILABLE, "Reproceso interrumpido", interrumpido);
        }
    }

    /**
     * queueDeclarePassive falla si la cola no existe (y cierra el canal), sin crearla. Si existe, devuelve cuántos
     * mensajes listos tiene.
     */
    private int exigirQueExista(Channel canal, String cola) {
        try {
            return canal.queueDeclarePassive(cola).getMessageCount();
        } catch (IOException noExiste) {
            throw new ErrorApi(HttpStatus.CONFLICT, "La cola '" + cola
                    + "' no existe en RabbitMQ. ¿Ya arrancó ms-gymflow-notify (es quien declara la topología)?", noExiste);
        }
    }

    private static AMQP.BasicProperties conCabeceraDeReproceso(AMQP.BasicProperties originales) {
        Map<String, Object> cabeceras = originales.getHeaders() == null ? new HashMap<>() : new HashMap<>(originales.getHeaders());
        Object previas = cabeceras.get(CABECERA_REPROCESOS);
        int veces = previas instanceof Number numero ? numero.intValue() : 0;
        cabeceras.put(CABECERA_REPROCESOS, veces + 1);
        return originales.builder().headers(cabeceras).build();
    }
}
