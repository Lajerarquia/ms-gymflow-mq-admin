package cl.duoc.gymflow.mqadmin.error;

import java.time.Instant;
import org.springframework.http.HttpStatus;

/** Mismo formato de error que el resto de los servicios de GymFlow. */
public record ErrorResponse(String timestamp, int status, String error, String message, String path) {

    public static ErrorResponse of(HttpStatus status, String message, String path) {
        return new ErrorResponse(Instant.now().toString(), status.value(), status.getReasonPhrase(), message, path);
    }
}
