package exception;

import java.time.LocalDateTime;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExceptionDTO {
    private String correlationId;
    private ErrorCode errorCode;
    private String service;
    private Object additionalInfo;
    private LocalDateTime timestamp;
    private String message;
    private HttpStatus statusCode;
}
