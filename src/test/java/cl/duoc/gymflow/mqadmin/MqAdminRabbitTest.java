package cl.duoc.gymflow.mqadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * La API completa contra un RabbitMQ 3.13 real (AMQP y API de Management) en Docker.
 * La topología del flujo de email se declara aquí, igual que la declara ms-gymflow-notify.
 * Si el equipo no tiene Docker, la clase se salta (no falla).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class MqAdminRabbitTest {

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management");

    private static final Duration ESPERA = Duration.ofSeconds(20);

    @DynamicPropertySource
    static void rabbit(DynamicPropertyRegistry registro) {
        registro.add("spring.rabbitmq.addresses", () -> RABBIT.getHost() + ":" + RABBIT.getAmqpPort());
        registro.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registro.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        registro.add("gymflow.mq-admin.management-url", RABBIT::getHttpUrl);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AmqpAdmin admin;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ConnectionFactory connectionFactory;

    @BeforeEach
    void topologiaDeEmail() {
        DirectExchange direct = new DirectExchange("cmd.direct", true, false);
        TopicExchange topic = new TopicExchange("cmd.topic", true, false);
        DirectExchange dlx = new DirectExchange("cmd.dead.dlx", true, false);
        Queue cola = QueueBuilder.durable("q.cmd.email").deadLetterExchange("cmd.dead.dlx").deadLetterRoutingKey("email").build();
        Queue dlq = QueueBuilder.durable("q.cmd.email.dlq").build();
        admin.declareExchange(direct);
        admin.declareExchange(topic);
        admin.declareExchange(dlx);
        admin.declareQueue(cola);
        admin.declareQueue(dlq);
        admin.declareBinding(BindingBuilder.bind(cola).to(direct).with("email.send"));
        admin.declareBinding(BindingBuilder.bind(cola).to(topic).with("email.*"));
        admin.declareBinding(BindingBuilder.bind(dlq).to(dlx).with("email"));
        admin.purgeQueue("q.cmd.email", false);
        admin.purgeQueue("q.cmd.email.dlq", false);
        admin.deleteQueue("q.cmd.checkin.dlq");
    }

    @Test
    void listarColas_muestraConteosDesdeLaApiDeManagement() throws Exception {
        dejarEnDlq("{\"eventId\":\"a\"}");
        dejarEnDlq("{\"eventId\":\"b\"}");

        // Las estadísticas de la API de Management se refrescan cada ~5 s
        await().atMost(ESPERA).untilAsserted(() -> mvc.perform(get("/api/mq/queues"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'q.cmd.email.dlq')].messages").value(2))
                .andExpect(jsonPath("$[?(@.name == 'q.cmd.email.dlq')].dlqDe").value("q.cmd.email"))
                .andExpect(jsonPath("$[?(@.name == 'q.cmd.email')].messages").value(0)));
    }

    @Test
    void publicarMensajeDePrueba_llegaALaColaConElEnvelope() throws Exception {
        String respuesta = mvc.perform(post("/api/mq/test-messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"exchange\":\"cmd.topic\",\"routingKey\":\"email.reminder\",\"type\":\"EMAIL_RECORDATORIO\","
                                + "\"payload\":{\"reservationId\":12}}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.routed").value(true))
                .andReturn().getResponse().getContentAsString();
        String eventId = objectMapper.readTree(respuesta).get("eventId").asText();

        Message mensaje = rabbitTemplate.receive("q.cmd.email", 5000);
        assertThat(mensaje).isNotNull();
        assertThat(mensaje.getMessageProperties().getMessageId()).isEqualTo(eventId);
        JsonNode envelope = objectMapper.readTree(mensaje.getBody());
        assertThat(envelope.get("type").asText()).isEqualTo("EMAIL_RECORDATORIO");
        assertThat(envelope.get("eventId").asText()).isEqualTo(eventId);
        assertThat(envelope.get("payload").get("reservationId").asInt()).isEqualTo(12);
    }

    @Test
    void publicarConRoutingKeySinBinding_responde422() throws Exception {
        mvc.perform(post("/api/mq/test-messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"exchange\":\"cmd.direct\",\"routingKey\":\"nadie.escucha\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("no llegó a ninguna cola")))
                .andExpect(jsonPath("$.path").value("/api/mq/test-messages"));
    }

    @Test
    void publicarSinRoutingKey_responde400() throws Exception {
        mvc.perform(post("/api/mq/test-messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"exchange\":\"cmd.direct\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("La routing key es obligatoria"));
    }

    @Test
    void reprocesarDlq_mueveLosMensajesALaColaPrincipal_sinCambiarlos() throws Exception {
        dejarEnDlq("{\"eventId\":\"1\"}");
        dejarEnDlq("{\"eventId\":\"2\"}");
        dejarEnDlq("{\"eventId\":\"3\"}");

        mvc.perform(post("/api/mq/queues/q.cmd.email.dlq/reprocess"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.destino").value("q.cmd.email"))
                .andExpect(jsonPath("$.movidos").value(3))
                .andExpect(jsonPath("$.quedanEnDlq").value(0));

        assertThat(admin.getQueueInfo("q.cmd.email.dlq").getMessageCount()).isZero();
        assertThat(admin.getQueueInfo("q.cmd.email").getMessageCount()).isEqualTo(3);
        Message primero = rabbitTemplate.receive("q.cmd.email", 5000);
        assertThat(new String(primero.getBody(), StandardCharsets.UTF_8)).isEqualTo("{\"eventId\":\"1\"}");
        assertThat((Integer) primero.getMessageProperties().getHeader("x-gymflow-reprocesos")).isEqualTo(1);
    }

    @Test
    void reprocesarConMax_mueveSoloEsaCantidad() throws Exception {
        dejarEnDlq("{\"eventId\":\"1\"}");
        dejarEnDlq("{\"eventId\":\"2\"}");
        dejarEnDlq("{\"eventId\":\"3\"}");

        mvc.perform(post("/api/mq/queues/q.cmd.email.dlq/reprocess").param("max", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.movidos").value(1))
                .andExpect(jsonPath("$.quedanEnDlq").value(2));
    }

    /**
     * Caso real: el mensaje vuelve a fallar y el consumidor lo devuelve a la DLQ al instante. El reproceso debe
     * mover solo los que había al empezar, no tomar una y otra vez los que regresan.
     */
    @Test
    void siLosMensajesVuelvenAFallar_seMuevenUnaSolaVez() throws Exception {
        dejarEnDlq("{\"eventId\":\"1\"}");
        dejarEnDlq("{\"eventId\":\"2\"}");
        SimpleMessageListenerContainer consumidorQueRechaza = new SimpleMessageListenerContainer(connectionFactory);
        consumidorQueRechaza.setQueueNames("q.cmd.email");
        consumidorQueRechaza.setMessageListener(mensaje -> {
            throw new AmqpRejectAndDontRequeueException("vuelve a fallar");
        });
        consumidorQueRechaza.start();
        try {
            mvc.perform(post("/api/mq/queues/q.cmd.email.dlq/reprocess"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.movidos").value(2));
            await().atMost(ESPERA).untilAsserted(() ->
                    assertThat(admin.getQueueInfo("q.cmd.email.dlq").getMessageCount()).isEqualTo(2));
        } finally {
            consumidorQueRechaza.stop();
        }
    }

    @Test
    void reprocesarUnaColaQueNoEsDlq_responde404() throws Exception {
        mvc.perform(post("/api/mq/queues/q.cmd.email/reprocess"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("no es una DLQ de GymFlow")));
    }

    @Test
    void reprocesarCuandoLaColaNoExiste_responde409() throws Exception {
        mvc.perform(post("/api/mq/queues/q.cmd.checkin.dlq/reprocess"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("no existe en RabbitMQ")));
    }

    @Test
    void maxFueraDeRango_responde400() throws Exception {
        mvc.perform(post("/api/mq/queues/q.cmd.email.dlq/reprocess").param("max", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("El parámetro 'max' debe estar entre 1 y 500"));
    }

    /** Pone un mensaje directo en la DLQ (exchange por defecto), como si notify lo hubiera rechazado. */
    private void dejarEnDlq(String cuerpo) {
        MessageProperties propiedades = new MessageProperties();
        propiedades.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbitTemplate.send("", "q.cmd.email.dlq", new Message(cuerpo.getBytes(StandardCharsets.UTF_8), propiedades));
    }
}
