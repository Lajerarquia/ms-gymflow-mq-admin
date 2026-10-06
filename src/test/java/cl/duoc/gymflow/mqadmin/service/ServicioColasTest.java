package cl.duoc.gymflow.mqadmin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cl.duoc.gymflow.mqadmin.dto.ColaResponse;
import cl.duoc.gymflow.mqadmin.dto.MensajePruebaRequest;
import cl.duoc.gymflow.mqadmin.error.ErrorApi;
import cl.duoc.gymflow.mqadmin.management.ClienteManagement;
import cl.duoc.gymflow.mqadmin.management.ClienteManagement.ColaManagement;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

/** Reglas de ServicioColas con la API de Management simulada. */
class ServicioColasTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ClienteManagement management = mock(ClienteManagement.class);
    private final ServicioColas servicio = new ServicioColas(management, objectMapper);

    @Test
    void listar_muestraSoloLasColasDeGymflow_ordenadas_yMarcaLasDlq() {
        when(management.colas()).thenReturn(List.of(
                new ColaManagement("q.cmd.email.dlq", "rabbit@rabbit1", 2L, 2L, 0L, 0),
                new ColaManagement("otra.cola", "rabbit@rabbit1", 9L, 9L, 0L, 1),
                new ColaManagement("q.cmd.email", "rabbit@rabbit2", 1L, 0L, 1L, 1),
                // Cola recién creada: la API todavía no trae contadores
                new ColaManagement("q.cmd.invoice", "rabbit@rabbit1", null, null, null, null)));

        List<ColaResponse> colas = servicio.listar();

        assertThat(colas).extracting(ColaResponse::name).containsExactly("q.cmd.email", "q.cmd.email.dlq", "q.cmd.invoice");
        assertThat(colas.get(0)).isEqualTo(new ColaResponse("q.cmd.email", "rabbit@rabbit2", 1, 0, 1, 1, null));
        assertThat(colas.get(1).dlqDe()).isEqualTo("q.cmd.email");
        assertThat(colas.get(2)).isEqualTo(new ColaResponse("q.cmd.invoice", "rabbit@rabbit1", 0, 0, 0, 0, null));
    }

    @Test
    void publicarPrueba_envuelveEnElEnvelopeComun() throws Exception {
        when(management.publicar(anyString(), anyString(), anyString(), anyMap())).thenReturn(true);
        JsonNode payload = objectMapper.readTree("{\"cliente\":\"José\"}");

        var respuesta = servicio.publicarPrueba(new MensajePruebaRequest("cmd.direct", "invoice.gen", "INVOICE_GENERAR", null, payload));

        ArgumentCaptor<String> cuerpo = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> propiedades = ArgumentCaptor.forClass(Map.class);
        verify(management).publicar(eq("cmd.direct"), eq("invoice.gen"), cuerpo.capture(), propiedades.capture());
        JsonNode envelope = objectMapper.readTree(cuerpo.getValue());
        assertThat(envelope.get("type").asText()).isEqualTo("INVOICE_GENERAR");
        assertThat(envelope.get("eventId").asText()).isEqualTo(respuesta.eventId());
        assertThat(envelope.get("timestamp").asText()).isNotBlank();
        assertThat(envelope.get("traceId").asText()).startsWith("mq-admin-");
        assertThat(envelope.get("correlationId").asText()).isEqualTo("mq-admin-prueba");
        assertThat(envelope.get("payload")).isEqualTo(payload);
        assertThat(propiedades.getValue()).containsEntry("message_id", respuesta.eventId())
                .containsEntry("delivery_mode", 2).containsEntry("content_type", "application/json");
        assertThat(respuesta.routed()).isTrue();
    }

    @Test
    void publicarPrueba_conEventIdDado_loRespeta_ySinTipoUsaElDeprueba() {
        when(management.publicar(anyString(), anyString(), anyString(), anyMap())).thenReturn(true);

        var respuesta = servicio.publicarPrueba(new MensajePruebaRequest("cmd.topic", "email.send", null, "evento-fijo", null));

        assertThat(respuesta.eventId()).isEqualTo("evento-fijo");
        assertThat(respuesta.type()).isEqualTo("MENSAJE_PRUEBA");
    }

    @Test
    void publicarPrueba_enElDlx_responde400_sinPublicar() {
        assertThatThrownBy(() -> servicio.publicarPrueba(new MensajePruebaRequest("cmd.dead.dlx", "email", null, null, null)))
                .isInstanceOf(ErrorApi.class)
                .hasMessage("Solo se puede publicar en cmd.direct o cmd.topic")
                .extracting(e -> ((ErrorApi) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(management, never()).publicar(anyString(), anyString(), anyString(), any());
    }

    @Test
    void publicarPrueba_sinColaDeDestino_responde422() {
        when(management.publicar(anyString(), anyString(), anyString(), anyMap())).thenReturn(false);

        assertThatThrownBy(() -> servicio.publicarPrueba(new MensajePruebaRequest("cmd.direct", "nadie.escucha", null, null, null)))
                .isInstanceOf(ErrorApi.class)
                .hasMessageContaining("no llegó a ninguna cola")
                .extracting(e -> ((ErrorApi) e).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
