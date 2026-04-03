package exception;

import java.time.LocalDateTime;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GenericException extends RuntimeException{
    private String correlationId;
    private ErrorCode errorCode;
    private String service;
    private Object additionalInfo;
    private LocalDateTime timestamp;
    private String message;
    private HttpStatus statusCode;


    public GenericException(String message, String correlationId, ErrorCode errorCode, String service, HttpStatus statusCode) {
        this.message = message;
        this.correlationId = correlationId;
        this.errorCode = errorCode;
        this.service = service;
        this.statusCode = statusCode;
    }
}
