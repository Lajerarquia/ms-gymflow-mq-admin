package cl.duoc.gymflow.mqadmin.error;

import org.springframework.http.HttpStatus;

/** Error con su código HTTP y un mensaje en español para el cliente. */
public class ErrorApi extends RuntimeException {

    private final HttpStatus status;

    public ErrorApi(HttpStatus status, String mensaje) {
        super(mensaje);
        this.status = status;
    }

    public ErrorApi(HttpStatus status, String mensaje, Throwable causa) {
        super(mensaje, causa);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
