package cl.duoc.gymflow.mqadmin.dto;

/**
 * Resultado de reprocesar una DLQ.
 *
 * @param movidos   mensajes que pasaron de la DLQ a la cola principal
 * @param quedanEnDlq mensajes que siguen en la DLQ (si se alcanzó el máximo por llamada)
 */
public record ReprocesoResponse(String dlq, String destino, int movidos, long quedanEnDlq) {
}
