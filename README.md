# ms-gymflow-mq-admin

Administración de RabbitMQ para GymFlow (EP2): estado de las colas, mensajes de prueba y reproceso de las DLQ.
Sin base de datos y **sin puertos publicados**: en `ec2-apps` solo lo alcanza ms-gymflow-bff por la red interna de
Docker, que lo expone como `/api/admin/mq/**` (solo rol Admin, mismo filtro JWT). Por eso este servicio no valida JWT.

No declara la topología (la dueña es ms-gymflow-notify). Solo opera sobre las colas `q.cmd.*`, publica únicamente en
`cmd.direct` y `cmd.topic`, y reprocesa únicamente las 3 DLQ del caso.

## API

| Método y ruta | Descripción |
|---|---|
| `GET /api/mq/queues` | Colas `q.cmd.*` con `messages`, `ready`, `unacked`, `consumers`, `node` y `dlqDe` (API de Management) |
| `POST /api/mq/test-messages` | Publica un mensaje de prueba envuelto en el envelope común (202). 422 si no llegó a ninguna cola |
| `POST /api/mq/queues/{dlq}/reprocess?max=` | Mueve los mensajes de la DLQ a su cola principal (máx. 500 por llamada) |

Mensaje de prueba:
```json
{"exchange": "cmd.direct", "routingKey": "invoice.gen", "type": "INVOICE_GENERAR",
 "payload": {"socio": "José Pérez", "plan": "PLUS"}}
```
`type` es opcional (por defecto `MENSAJE_PRUEBA`, que notify no conoce y manda a la DLQ: sirve para demostrarla).
`eventId` es opcional; enviar dos veces el mismo demuestra la idempotencia de notify.

Reproceso:
```json
{"dlq": "q.cmd.email.dlq", "destino": "q.cmd.email", "movidos": 3, "quedanEnDlq": 0}
```

Errores en el JSON común `{"timestamp","status","error","message","path"}`: 400 datos inválidos, 404 no es una DLQ
de GymFlow, 409 la cola no existe (notify no ha arrancado), 422 mensaje sin cola de destino, 503 RabbitMQ o la API
de Management no responden.

## Decisiones

- **Listado y mensajes de prueba por la API de Management** (`/api/queues`, `/api/exchanges/.../publish`). La
  publicación por HTTP responde `{"routed": true|false}`, así se sabe al instante si el mensaje llegó a alguna cola.
  Los contadores de la API se refrescan cada ~5 s.
- **Reproceso por AMQP con publisher confirms**, mensaje por mensaje: `basicGet` sin auto-ack → publicar en la cola
  principal → esperar la confirmación del broker → `basicAck` en la DLQ. Si algo falla a la mitad, el mensaje no
  confirmado vuelve a la DLQ: no se pierde. Cada mensaje lleva la cabecera `x-gymflow-reprocesos`.
- **Solo se mueven los mensajes que había al empezar.** Si un mensaje vuelve a fallar, notify lo devuelve a la DLQ al
  instante; sin ese límite el reproceso lo tomaría una y otra vez (lo detectó la prueba local y lo cubre
  `MqAdminRabbitTest.siLosMensajesVuelvenAFallar_seMuevenUnaSolaVez`).
- **HTTP/1.1 fijo** para la API de Management: el `HttpClient` del JDK intenta HTTP/2 sin TLS y el servidor de
  RabbitMQ corta algunos POST.

## Ejecutar

```bash
RABBITMQ_PASSWORD=<contraseña> ./mvnw spring-boot:run      # puerto 8084
curl -s localhost:8084/api/mq/queues
```

En AWS se usa a través del BFF: `GET https://<api-gateway>/api/admin/mq/queues` con el token de un Admin
(ver `infra/docs/rabbitmq.md`). Rutas: `/api/admin/mq/queues`, `/api/admin/mq/test-messages`,
`/api/admin/mq/queues/{dlq}/reprocess`.

## Pruebas

```bash
./mvnw test
```

- `ServicioColasTest`: filtro y orden de colas, envelope del mensaje de prueba, 400 y 422 (API de Management simulada).
- `MqAdminRabbitTest`: contra RabbitMQ 3.13 real con Testcontainers (listado, publicación, reproceso, rebote, errores).
  **Necesita Docker**; si no hay Docker, se salta.
