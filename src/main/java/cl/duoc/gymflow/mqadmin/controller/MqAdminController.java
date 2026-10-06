package cl.duoc.gymflow.mqadmin.controller;

import cl.duoc.gymflow.mqadmin.dto.ColaResponse;
import cl.duoc.gymflow.mqadmin.dto.MensajePruebaRequest;
import cl.duoc.gymflow.mqadmin.dto.MensajePruebaResponse;
import cl.duoc.gymflow.mqadmin.dto.ReprocesoResponse;
import cl.duoc.gymflow.mqadmin.service.ReprocesadorDlq;
import cl.duoc.gymflow.mqadmin.service.ServicioColas;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administración de RabbitMQ para GymFlow. <b>No se expone a internet</b>: en la EC2 su puerto solo escucha en
 * 127.0.0.1 y se usa con un túnel SSH. Por eso no valida JWT.
 */
@RestController
@RequestMapping("/api/mq")
public class MqAdminController {

    private final ServicioColas servicioColas;
    private final ReprocesadorDlq reprocesador;

    public MqAdminController(ServicioColas servicioColas, ReprocesadorDlq reprocesador) {
        this.servicioColas = servicioColas;
        this.reprocesador = reprocesador;
    }

    /** Colas de GymFlow ({@code q.cmd.*}) con su conteo de mensajes y consumidores. */
    @GetMapping("/queues")
    public List<ColaResponse> colas() {
        return servicioColas.listar();
    }

    /** Publica un mensaje de prueba (envuelto en el envelope común) en cmd.direct o cmd.topic. */
    @PostMapping("/test-messages")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MensajePruebaResponse publicarPrueba(@Valid @RequestBody MensajePruebaRequest datos) {
        return servicioColas.publicarPrueba(datos);
    }

    /** Mueve los mensajes de una DLQ a su cola principal ({@code q.cmd.email.dlq} → {@code q.cmd.email}). */
    @PostMapping("/queues/{dlq}/reprocess")
    public ReprocesoResponse reprocesar(@PathVariable String dlq, @RequestParam(required = false) Integer max) {
        return reprocesador.reprocesar(dlq, max);
    }
}
