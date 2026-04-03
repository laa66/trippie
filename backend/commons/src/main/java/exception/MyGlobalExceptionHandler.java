package exception;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class MyGlobalExceptionHandler {

    @ExceptionHandler(GenericException.class)
    public ResponseEntity<ExceptionDTO> handleGenericException(GenericException ex) {

        ExceptionDTO dto = toDTO(ex);

        return new ResponseEntity<>(dto, ex.getStatusCode());
    }

    
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ExceptionDTO> handleOtherException(Exception ex) {

        GenericException generic = new GenericException(
                ex.getMessage(),
                UUID.randomUUID().toString(),
                ErrorCode.INTERNAL_SERVER_ERROR,
                "MY-SERVICE",
                HttpStatus.INTERNAL_SERVER_ERROR
        ); 

        ExceptionDTO dto = toDTO(generic);

        return new ResponseEntity<>(dto, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private ExceptionDTO toDTO(GenericException ex) {
        ExceptionDTO dto = new ExceptionDTO();
        dto.setCorrelationId(ex.getCorrelationId());
        dto.setErrorCode(ex.getErrorCode());
        dto.setService(ex.getService());
        dto.setAdditionalInfo(ex.getAdditionalInfo());
        dto.setTimestamp(ex.getTimestamp());
        dto.setMessage(ex.getMessage());
        dto.setStatusCode(ex.getStatusCode());
        return dto;
    }
}
